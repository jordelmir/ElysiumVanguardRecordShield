package com.elysium.vanguard.recordshield.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elysium.vanguard.recordshield.R
import com.elysium.vanguard.recordshield.RecordShieldApplication
import com.elysium.vanguard.recordshield.domain.model.*
import com.elysium.vanguard.recordshield.domain.repository.ChunkRepository
import com.elysium.vanguard.recordshield.domain.repository.RecordingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * ============================================================================
 * RecordingService — Anti-Sabotage Foreground Service
 * ============================================================================
 *
 * Why Foreground Service:
 *   Android kills background services after ~1 minute. A foreground service
 *   with FOREGROUND_SERVICE_TYPE_CAMERA | FOREGROUND_SERVICE_TYPE_MICROPHONE
 *   guarantees continuous recording even when:
 *   - Screen is off
 *   - Device is locked
 *   - User switches to another app
 *
 * Why WakeLock:
 *   Even foreground services can't prevent CPU deep sleep on some OEMs
 *   (Huawei, Xiaomi, Samsung). A PARTIAL_WAKE_LOCK keeps CPU active while
 *   allowing the screen to turn off — critical for evidence capture.
 *
 * Lifecycle:
 *   START_RECORDING → creates chunks every CHUNK_DURATION_MS
 *   STOP_RECORDING → finalizes last chunk, updates recording status
 *   Service destruction → marks recording as "interrupted"
 * ============================================================================
 */
@AndroidEntryPoint
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START_VIDEO = "ACTION_START_VIDEO"
        const val ACTION_START_AUDIO = "ACTION_START_AUDIO"
        const val ACTION_STOP = "ACTION_STOP"
        const val NOTIFICATION_ID = 1001
        const val CHUNK_DURATION_MS = 10_000L // 10 seconds per chunk

        // Singleton state for UI observation
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentRecordingId = MutableStateFlow<String?>(null)
        val currentRecordingId: StateFlow<String?> = _currentRecordingId

        private val _elapsedSeconds = MutableStateFlow(0L)
        val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

        fun startVideoRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_VIDEO
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startAudioRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START_AUDIO
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    @Inject lateinit var recordingRepository: RecordingRepository
    @Inject lateinit var chunkRepository: ChunkRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentRecording: com.elysium.vanguard.recordshield.domain.model.Recording? = null
    private var chunkIndex = 0
    private var timerJob: Job? = null
    private var chunkJob: Job? = null

    // Audio recording with MediaRecorder
    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isAudioMode = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_VIDEO -> startRecording(RecordingType.VIDEO)
            ACTION_START_AUDIO -> startRecording(RecordingType.AUDIO)
            ACTION_STOP -> stopRecordingInternal()
        }
        // Why STICKY: If Android kills the service, it restarts it automatically.
        // This is the last-resort anti-sabotage mechanism.
        return START_STICKY
    }

    private fun startRecording(type: RecordingType) {
        if (_isRecording.value) {
            Log.w(TAG, "Recording already in progress, ignoring start request")
            return
        }

        // Start foreground immediately to avoid ANR on Android 12+
        startForeground(NOTIFICATION_ID, buildNotification("Preparing recording..."))

        // Acquire WakeLock
        acquireWakeLock()

        val recording = com.elysium.vanguard.recordshield.domain.model.Recording(
            id = UUID.randomUUID().toString(),
            deviceId = fetchDeviceId(),
            type = type
        )
        currentRecording = recording
        chunkIndex = 0
        isAudioMode = type == RecordingType.AUDIO

        serviceScope.launch {
            recordingRepository.createRecording(recording)
            _currentRecordingId.value = recording.id
            _isRecording.value = true
            _elapsedSeconds.value = 0

            // Start the timer
            startTimer()

            // Start chunked recording
            if (isAudioMode) {
                startAudioChunking(recording)
            } else {
                startVideoChunking(recording)
            }
        }
    }

    /**
     * Audio chunking: Records audio in CHUNK_DURATION_MS intervals.
     * Each chunk is a self-contained AAC file in internal storage.
     */
    private fun startAudioChunking(recording: com.elysium.vanguard.recordshield.domain.model.Recording) {
        chunkJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                val chunkFile = createChunkFile(recording.id, chunkIndex, "m4a")
                try {
                    recordAudioChunk(chunkFile)
                    // Compute hash and register chunk
                    val hash = computeSha256(chunkFile)
                    val chunk = EvidenceChunk(
                        recordingId = recording.id,
                        chunkIndex = chunkIndex,
                        localPath = chunkFile.absolutePath,
                        sizeBytes = chunkFile.length(),
                        durationMs = CHUNK_DURATION_MS.toInt(),
                        mimeType = "audio/mp4",
                        sha256Hash = hash
                    )
                    chunkRepository.insertChunk(chunk)
                    Log.i(TAG, "Audio chunk $chunkIndex saved: ${chunkFile.length()} bytes")
                    chunkIndex++
                    updateNotification("Recording audio • Chunk $chunkIndex saved")
                } catch (e: Exception) {
                    Log.e(TAG, "Audio chunk $chunkIndex failed", e)
                }
            }
        }
    }

    /**
     * Video chunking: Records video in CHUNK_DURATION_MS intervals using CameraX.
     * Each chunk is a self-contained MP4 file.
     */
    private fun startVideoChunking(recording: com.elysium.vanguard.recordshield.domain.model.Recording) {
        chunkJob = serviceScope.launch {
            while (isActive && _isRecording.value) {
                val chunkFile = createChunkFile(recording.id, chunkIndex, "mp4")
                try {
                    recordVideoChunk(chunkFile)
                    val hash = computeSha256(chunkFile)
                    val chunk = EvidenceChunk(
                        recordingId = recording.id,
                        chunkIndex = chunkIndex,
                        localPath = chunkFile.absolutePath,
                        sizeBytes = chunkFile.length(),
                        durationMs = CHUNK_DURATION_MS.toInt(),
                        mimeType = "video/mp4",
                        sha256Hash = hash
                    )
                    chunkRepository.insertChunk(chunk)
                    Log.i(TAG, "Video chunk $chunkIndex saved: ${chunkFile.length()} bytes")
                    chunkIndex++
                    updateNotification("Recording video • Chunk $chunkIndex saved")
                } catch (e: Exception) {
                    Log.e(TAG, "Video chunk $chunkIndex failed", e)
                }
            }
        }
    }

    /**
     * Records a single audio chunk using MediaRecorder.
     * Suspends for CHUNK_DURATION_MS then stops recording.
     */
    private suspend fun recordAudioChunk(outputFile: File) {
        withContext(Dispatchers.Main) {
            mediaRecorder = android.media.MediaRecorder(this@RecordingService).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000) // 128 kbps — good quality, small chunks
                setAudioSamplingRate(44_100)
                setMaxDuration(CHUNK_DURATION_MS.toInt())
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        }
        // Wait for chunk duration
        delay(CHUNK_DURATION_MS)
        withContext(Dispatchers.Main) {
            try {
                mediaRecorder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "MediaRecorder stop error (may be normal)", e)
            }
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }

    /**
     * Records a single video chunk using CameraX VideoCapture.
     * This method uses the CameraX recording API with a file output.
     */
    private suspend fun recordVideoChunk(outputFile: File) {
        // For video, we use CameraX's VideoCapture use case.
        // The actual CameraX binding is done on the Main thread.
        withContext(Dispatchers.Main) {
            val cameraProvider = ProcessCameraProvider.getInstance(this@RecordingService).get()
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .setExecutor(Executors.newSingleThreadExecutor())
                .build()
            val videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    androidx.lifecycle.ProcessLifecycleOwner.get(),
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    videoCapture
                )

                val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()
                val recording = videoCapture.output
                    .prepareRecording(this@RecordingService, fileOutputOptions)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(this@RecordingService)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.hasError()) {
                                Log.e(TAG, "Video chunk error: ${event.error}")
                            }
                        }
                    }

                // Record for chunk duration then stop
                delay(CHUNK_DURATION_MS)
                recording.stop()
                // Small delay for finalization
                delay(500)
            } finally {
                cameraProvider.unbindAll()
            }
        }
    }

    private fun stopRecordingInternal() {
        if (!_isRecording.value) return

        _isRecording.value = false
        timerJob?.cancel()
        chunkJob?.cancel()

        // Release MediaRecorder if audio mode
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (_: Exception) {}
        mediaRecorder = null

        // Update recording status
        serviceScope.launch {
            val current = currentRecording
            if (current != null) {
                recordingRepository.updateRecordingStatus(
                    current.id,
                    RecordingStatus.COMPLETED.value,
                    System.currentTimeMillis()
                )
            }
            _currentRecordingId.value = null
        }

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If destroyed without proper stop, mark as interrupted
        if (_isRecording.value) {
            _isRecording.value = false
            serviceScope.launch {
                val current = currentRecording
                if (current != null) {
                    recordingRepository.updateRecordingStatus(
                        current.id,
                        RecordingStatus.INTERRUPTED.value,
                        System.currentTimeMillis()
                    )
                }
            }
        }
        timerJob?.cancel()
        chunkJob?.cancel()
        releaseWakeLock()
        serviceScope.cancel()
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private fun createChunkFile(recordingId: String, index: Int, ext: String): File {
        val dir = File(filesDir, "recordings/$recordingId")
        dir.mkdirs()
        return File(dir, "chunk_${String.format("%05d", index)}.$ext")
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchDeviceId(): String {
        // Use Android Settings.Secure.ANDROID_ID as device fingerprint base
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
    }

    private fun startTimer() {
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                _elapsedSeconds.value++
            }
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RecordShield::RecordingWakeLock"
        ).apply {
            // Why 4 hours: Maximum reasonable recording session.
            // Prevents battery drain from orphaned wake locks.
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, RecordShieldApplication.RECORDING_CHANNEL_ID)
            .setContentTitle("🛡️ Record Shield Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true) // Can't be swiped away
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
