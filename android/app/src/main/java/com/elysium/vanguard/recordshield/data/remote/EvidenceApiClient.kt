package com.elysium.vanguard.recordshield.data.remote

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ============================================================================
 * EvidenceApiClient — Ktor HTTP Client for Vercel API
 * ============================================================================
 *
 * Why Ktor over Retrofit:
 *   - Native coroutine support (no Call adapters needed)
 *   - Streaming binary uploads via ByteArray without multipart overhead
 *   - Kotlin-first API design
 *   - CIO engine is lightweight and doesn't depend on OkHttp
 *
 * This client communicates exclusively with the Vercel Edge endpoint
 * at /api/upload-evidence. It handles:
 *   - Binary chunk uploads with integrity headers
 *   - Recording session creation
 *   - Device registration (future)
 * ============================================================================
 */
class EvidenceApiClient(
    private val baseUrl: String
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(Logging) {
            level = LogLevel.HEADERS // Why HEADERS not BODY: chunk binary bodies are massive
        }
        // Why: Increase timeouts for large chunk uploads over slow connections
        engine {
            requestTimeout = 30_000 // 30 seconds per chunk
            endpoint {
                connectTimeout = 10_000
                keepAliveTime = 5_000
            }
        }
    }

    /**
     * Upload a single evidence chunk to the Vercel endpoint.
     *
     * @param deviceId UUID of the registered device
     * @param deviceToken API key for authentication
     * @param recordingId UUID of the recording session
     * @param chunkIndex Zero-based index of this chunk
     * @param chunkData Raw binary data of the chunk
     * @param sha256Hash SHA-256 hex digest of chunkData
     * @param mimeType MIME type (e.g., "video/mp4", "audio/aac")
     * @return ChunkUploadResponse with storage path and confirmation
     * @throws ChunkUploadException on any failure
     */
    suspend fun uploadChunk(
        deviceId: String,
        deviceToken: String,
        recordingId: String,
        chunkIndex: Int,
        chunkData: ByteArray,
        sha256Hash: String,
        mimeType: String = "video/mp4"
    ): ChunkUploadResponse {
        val response: HttpResponse = client.post("$baseUrl/api/upload-evidence") {
            // Why custom headers instead of JSON body: The body IS the binary chunk.
            // Metadata goes in headers to avoid base64 encoding overhead.
            header("X-Device-Token", deviceToken)
            header("X-Device-Id", deviceId)
            header("X-Recording-Id", recordingId)
            header("X-Chunk-Index", chunkIndex.toString())
            header("X-Chunk-Hash", sha256Hash)
            contentType(ContentType.parse(mimeType))
            setBody(chunkData)
        }

        val bodyText = response.bodyAsText()

        return when (response.status) {
            HttpStatusCode.Created -> {
                json.decodeFromString<ChunkUploadResponse>(bodyText)
            }
            HttpStatusCode.Conflict -> {
                // Chunk already uploaded — treat as success (idempotent)
                ChunkUploadResponse(
                    success = true,
                    chunk = ChunkInfo(
                        index = chunkIndex,
                        storagePath = "",
                        sizeBytes = chunkData.size.toLong(),
                        sha256Hash = sha256Hash
                    )
                )
            }
            HttpStatusCode.Unauthorized -> {
                throw ChunkUploadException("Device authentication failed. Token may be expired.")
            }
            HttpStatusCode.UnprocessableEntity -> {
                throw ChunkUploadException("Integrity check failed. Chunk may be corrupted.")
            }
            else -> {
                throw ChunkUploadException(
                    "Upload failed with status ${response.status.value}: $bodyText"
                )
            }
        }
    }

    /**
     * Create a new recording session on the remote server.
     * The Vercel endpoint creates the recording in Supabase and returns the ID.
     */
    suspend fun createRemoteRecording(
        deviceId: String,
        deviceToken: String,
        recordingType: String
    ): String {
        val response: HttpResponse = client.post("$baseUrl/api/create-recording") {
            header("X-Device-Token", deviceToken)
            header("X-Device-Id", deviceId)
            contentType(ContentType.Application.Json)
            setBody("""{"recording_type": "$recordingType"}""")
        }

        if (response.status != HttpStatusCode.Created) {
            throw ChunkUploadException("Failed to create remote recording: ${response.bodyAsText()}")
        }

        val result = json.decodeFromString<CreateRecordingResponse>(response.bodyAsText())
        return result.recordingId
    }

    fun close() {
        client.close()
    }
}

// ============================================================================
// Response Models
// ============================================================================

@Serializable
data class ChunkUploadResponse(
    val success: Boolean,
    val warning: String? = null,
    val chunk: ChunkInfo? = null
)

@Serializable
data class ChunkInfo(
    val id: String? = null,
    val index: Int,
    val storagePath: String = "",
    val sizeBytes: Long = 0,
    val sha256Hash: String = "",
    val uploadedAt: String? = null
)

@Serializable
data class CreateRecordingResponse(
    val recordingId: String
)

class ChunkUploadException(message: String) : Exception(message)
