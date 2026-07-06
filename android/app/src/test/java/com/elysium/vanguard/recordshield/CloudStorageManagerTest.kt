package com.elysium.vanguard.recordshield

import com.elysium.vanguard.recordshield.data.cloud.CloudChunkInfo
import com.elysium.vanguard.recordshield.data.cloud.CloudStorageProvider
import com.elysium.vanguard.recordshield.data.cloud.CloudUploadException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for CloudStorageProvider implementations.
 *
 * Why these tests matter:
 *   - Cloud upload is the only way evidence survives device seizure
 *   - Provider correctness ensures evidence integrity
 *   - Auth error handling prevents silent upload failures
 */
class CloudStorageProviderTest {

    private lateinit var fakeProvider: FakeCloudProvider

    @Before
    fun setup() {
        fakeProvider = FakeCloudProvider(
            providerId = "google_drive",
            displayName = "Google Drive",
            isConfigured = true
        )
    }

    @Test
    fun `provider returns correct id and name`() {
        assertEquals("google_drive", fakeProvider.providerId)
        assertEquals("Google Drive", fakeProvider.displayName)
    }

    @Test
    fun `isConfigured returns expected values`() = runTest {
        assertTrue(fakeProvider.isConfigured())
        fakeProvider.isConfigured = false
        assertFalse(fakeProvider.isConfigured())
    }

    @Test
    fun `uploadChunk returns storage path on success`() = runTest {
        fakeProvider.uploadResult = "recordings/rec-123/chunk_0.mp4"

        val result = fakeProvider.uploadChunk(
            recordingId = "rec-123",
            chunkIndex = 0,
            chunkData = ByteArray(1024),
            mimeType = "video/mp4",
            sha256Hash = "abc123def456"
        )

        assertEquals("recordings/rec-123/chunk_0.mp4", result)
        assertEquals("rec-123", fakeProvider.lastRecordingId)
    }

    @Test
    fun `uploadChunk throws CloudUploadException on failure`() = runTest {
        fakeProvider.shouldFail = true

        try {
            fakeProvider.uploadChunk(
                recordingId = "rec-123",
                chunkIndex = 0,
                chunkData = ByteArray(1024),
                mimeType = "video/mp4",
                sha256Hash = "abc123def456"
            )
            fail("Expected CloudUploadException")
        } catch (e: CloudUploadException) {
            assertEquals("google_drive", e.providerId)
            assertTrue(e.isRetryable)
        }
    }

    @Test
    fun `verifyChunk returns true for valid path`() = runTest {
        assertTrue(fakeProvider.verifyChunk("recordings/rec-123/chunk_0.mp4"))
    }

    @Test
    fun `getChunkDownloadUrl returns URL`() = runTest {
        val url = fakeProvider.getChunkDownloadUrl("recordings/rec-123/chunk_0.mp4")
        assertNotNull(url)
        assertTrue(url!!.contains("rec-123"))
    }

    @Test
    fun `listChunks returns empty list by default`() = runTest {
        val chunks = fakeProvider.listChunks("rec-123")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `deleteChunk returns true`() = runTest {
        assertTrue(fakeProvider.deleteChunk("recordings/rec-123/chunk_0.mp4"))
    }

    @Test
    fun `getStorageUsage returns configured value`() = runTest {
        fakeProvider.storageUsage = 1024L * 1024L * 5L // 5MB
        assertEquals(1024L * 1024L * 5L, fakeProvider.getStorageUsage())
    }

    @Test
    fun `refreshAuth returns true by default`() = runTest {
        assertTrue(fakeProvider.refreshAuth())
    }

    @Test
    fun `uploadChunk records lastRecordingId`() = runTest {
        fakeProvider.uploadChunk(
            recordingId = "rec-456",
            chunkIndex = 2,
            chunkData = ByteArray(2048),
            mimeType = "audio/mp4",
            sha256Hash = "xyz789"
        )
        assertEquals("rec-456", fakeProvider.lastRecordingId)
    }
}

/**
 * Fake CloudStorageProvider for testing.
 * Implements the real CloudStorageProvider interface.
 */
class FakeCloudProvider(
    override val providerId: String = "fake",
    override val displayName: String = "Fake Provider",
    var isConfigured: Boolean = true,
    var uploadResult: String = "fake/storage/path",
    var shouldFail: Boolean = false,
    var storageUsage: Long = 0L
) : CloudStorageProvider {

    var lastRecordingId: String? = null
        private set

    override suspend fun isConfigured(): Boolean = isConfigured

    override suspend fun uploadChunk(
        recordingId: String,
        chunkIndex: Int,
        chunkData: ByteArray,
        mimeType: String,
        sha256Hash: String
    ): String {
        lastRecordingId = recordingId
        if (shouldFail) {
            throw CloudUploadException("Upload failed", providerId, isRetryable = true)
        }
        return uploadResult
    }

    override suspend fun verifyChunk(storagePath: String): Boolean = true

    override suspend fun getChunkDownloadUrl(storagePath: String): String? =
        "https://fake.download/$storagePath"

    override suspend fun listChunks(recordingId: String): List<CloudChunkInfo> = emptyList()

    override suspend fun deleteChunk(storagePath: String): Boolean = true

    override suspend fun getStorageUsage(): Long = storageUsage

    override suspend fun refreshAuth(): Boolean = true
}
