package com.elysium.vanguard.recordshield.service

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.elysium.vanguard.recordshield.domain.model.UploadStatus
import com.elysium.vanguard.recordshield.domain.repository.ChunkRepository
import com.elysium.vanguard.recordshield.domain.repository.EvidenceUploadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 * UploadWorker — WorkManager-Based Evidence Upload
 * ============================================================================
 *
 * Why WorkManager over plain coroutines:
 *   - Survives process death (coroutines don't)
 *   - Resumes after device reboot
 *   - Applies exponential backoff automatically
 *   - Respects network constraints (only uploads on connected networks)
 *   - Works within Android's battery optimization framework
 *
 * This is the LAST LINE OF DEFENSE for evidence preservation. Even if
 * the recording service is killed, pending chunks in the Room database
 * will be picked up and uploaded by WorkManager.
 *
 * Scheduling: Enqueued as a periodic worker that runs every 15 minutes
 * AND triggered immediately after each chunk is created.
 * ============================================================================
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val chunkRepository: ChunkRepository,
    private val uploadRepository: EvidenceUploadRepository
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "UploadWorker"
        private const val WORK_NAME_PERIODIC = "evidence_upload_periodic"
        private const val WORK_NAME_IMMEDIATE = "evidence_upload_immediate"

        /**
         * Enqueue an immediate one-time upload attempt.
         * Called after each chunk is created during recording.
         */
        fun enqueueImmediate(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS // Start retry at 30s, then 60s, 120s, etc.
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    WORK_NAME_IMMEDIATE,
                    ExistingWorkPolicy.APPEND_OR_REPLACE, // Don't drop pending uploads
                    request
                )
        }

        /**
         * Schedule periodic upload sweep every 15 minutes.
         * Catches any chunks that the immediate worker missed.
         */
        fun schedulePeriodicUpload(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<UploadWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60, TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME_PERIODIC,
                    ExistingPeriodicWorkPolicy.KEEP, // Don't restart if already scheduled
                    request
                )
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "UploadWorker started — scanning for pending chunks")

        val pendingChunks = chunkRepository.getPendingChunks()
        if (pendingChunks.isEmpty()) {
            Log.i(TAG, "No pending chunks to upload")
            return Result.success()
        }

        Log.i(TAG, "Found ${pendingChunks.size} pending chunks to upload")

        // TODO: Read device credentials from EncryptedSharedPreferences
        // For now, use placeholder values that will be replaced in Phase 3
        val deviceId = "device-placeholder"
        val deviceToken = "token-placeholder"

        var failedCount = 0

        for (chunk in pendingChunks) {
            try {
                // Mark as uploading
                chunkRepository.updateChunkUploadStatus(chunk.id, UploadStatus.UPLOADING)

                // Read chunk file
                val file = File(chunk.localPath)
                if (!file.exists()) {
                    Log.w(TAG, "Chunk file missing: ${chunk.localPath}")
                    chunkRepository.updateChunkUploadStatus(chunk.id, UploadStatus.FAILED)
                    continue
                }

                val chunkData = file.readBytes()

                // Upload to Vercel
                val storagePath = uploadRepository.uploadChunk(
                    deviceId = deviceId,
                    deviceToken = deviceToken,
                    recordingId = chunk.recordingId,
                    chunkIndex = chunk.chunkIndex,
                    chunkData = chunkData,
                    sha256Hash = chunk.sha256Hash,
                    mimeType = chunk.mimeType
                )

                // Mark as uploaded
                chunkRepository.updateChunkUploadStatus(
                    chunk.id,
                    UploadStatus.UPLOADED,
                    storagePath
                )

                Log.i(TAG, "Chunk ${chunk.chunkIndex} uploaded successfully → $storagePath")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to upload chunk ${chunk.chunkIndex}", e)
                chunkRepository.updateChunkUploadStatus(chunk.id, UploadStatus.FAILED)
                failedCount++
            }
        }

        return if (failedCount > 0) {
            Log.w(TAG, "$failedCount chunks failed — will retry")
            Result.retry() // Triggers exponential backoff
        } else {
            Log.i(TAG, "All chunks uploaded successfully")
            Result.success()
        }
    }
}
