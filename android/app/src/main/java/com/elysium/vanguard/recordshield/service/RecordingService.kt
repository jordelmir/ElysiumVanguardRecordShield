package com.elysium.vanguard.recordshield.service

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.Manifest
import android.content.pm.PackageManager
import com.elysium.vanguard.recordshield.R
import com.elysium.vanguard.recordshield.RecordShieldApplication
import com.elysium.vanguard.recordshield.domain.model.*
import com.elysium.vanguard.recordshield.domain.repository.ChunkRepository
import com.elysium.vanguard.recordshield.domain.repository.RecordingRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import androidx.lifecycle.LifecycleService
import androidx.annotation.CallSuper
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
class RecordingService : LifecycleService() {

    companion object {
        private const val TAG = "RecordingService"
        const val ACTION_START_VIDEO = "com.elysium.vanguard.recordshield.ACTION_START_VIDEO"
        const val ACTION_START_AUDIO = "com.elysium.vanguard.recordshield.ACTION_START_AUDIO"
        const val ACTION_STOP = "com.elysium.vanguard.recordshield.ACTION_STOP"
        const val ACTION_TOGGLE = "com.elysium.vanguard.recordshield.ACTION_TOGGLE"
        const val NOTIFICATION_ID = 1001
        const val CHUNK_DURATION_MS = 10_000L // 10 seconds per chunk

        // Singleton state for UI observation
        private val _isRecording = MutableStateFlow(false)
        val isRecording: StateFlow<Boolean> = _isRecording

        private val _currentRecordingId = MutableStateFlow<String?>(null)
        val currentRecordingId: StateFlow<String?> = _currentRecordingId

        private val _elapsedSeconds = MutableStateFlow(0L)
        val elapsedSeconds: StateFlow<Long> = _elapsedSeconds

        private val _currentRecordingType = MutableStateFlow<RecordingType?>(null)
        val currentRecordingType: StateFlow<RecordingType?> = _currentRecordingType

        // Static provider for Live Preview (Reactive)
        private val _previewSurfaceProvider = MutableStateFlow<Preview.SurfaceProvider?>(null)
        var previewSurfaceProvider: Preview.SurfaceProvider?
            get() = _previewSurfaceProvider.value
            set(value) { _previewSurfaceProvider.value = value }

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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var activeRecording: androidx.camera.video.Recording? = null
    private var isScreenOff = false
    
    // Mock Surface for Zero-Interruption background recording
    private var mockSurfaceTexture: android.graphics.SurfaceTexture? = null
    private var mockSurface: android.view.Surface? = null
    private val mockSurfaceProvider = Preview.SurfaceProvider { request ->
        if (mockSurfaceTexture == null) {
            mockSurfaceTexture = android.graphics.SurfaceTexture(0).apply {
                setDefaultBufferSize(request.resolution.width, request.resolution.height)
            }
            mockSurface = android.view.Surface(mockSurfaceTexture)
        }
        request.provideSurface(mockSurface!!, ContextCompat.getMainExecutor(this@RecordingService)) {
            // No-op cleanup
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    updateCameraBinding()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOff = false
                    updateCameraBinding()
                }
            }
        }
    }

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        // Observe surface provider changes
        serviceScope.launch {
            _previewSurfaceProvider.collect {
                updateCameraBinding()
            }
        }
    }

    @CallSuper
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    @Suppress("DEPRECATION")
    @CallSuper
    override fun onStart(intent: Intent?, startId: Int) {
        super.onStart(intent, startId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START_VIDEO -> startRecording(RecordingType.VIDEO)
            ACTION_START_AUDIO -> startRecording(RecordingType.AUDIO)
            ACTION_STOP -> stopRecordingInternal()
            ACTION_TOGGLE -> {
                if (_isRecording.value) {
                    stopRecordingInternal()
                } else {
                    startRecording(RecordingType.VIDEO)
                }
            }
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
        // Explicitly declare camera and microphone types for Android 14+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                buildNotification("Preparing recording..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("Preparing recording..."))
        }

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
        _currentRecordingType.value = type

        serviceScope.launch {
            recordingRepository.createRecording(recording)
            _currentRecordingId.value = recording.id
            _isRecording.value = true
            _elapsedSeconds.value = 0

            // Start the timer
            startTimer()
            
            // Setup Camera if possible (both Audio and Video should try to show preview if available)
            setupCamera()

            // Start chunked recording
            if (isAudioMode) {
                startAudioChunking(recording)
            } else {
                startVideoChunking(recording)
            }
        }
    }

    private fun setupCamera() {
        if (cameraProvider != null) return
        
        serviceScope.launch(Dispatchers.Main) {
            cameraProvider = withContext(Dispatchers.IO) {
                ProcessCameraProvider.getInstance(this@RecordingService).get()
            }
            
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .setExecutor(Executors.newSingleThreadExecutor())
                .build()
            
            videoCapture = VideoCapture.withOutput(recorder)
            updateCameraBinding()
        }
    }

    private fun updateCameraBinding() {
        val provider = cameraProvider ?: return
        if (!_isRecording.value && !isAudioMode) {
            // Service is active but not recording? Just clear if screen is off
            if (isScreenOff) provider.unbindAll()
            return
        }

        serviceScope.launch(Dispatchers.Main) {
            try {
                val uiSurfaceProvider = _previewSurfaceProvider.value
                val videoUseCase = if (isAudioMode) null else videoCapture
                
                // CRITICAL: Decide which surface provider to use. 
                // We PREFER the UI surface, but fallback to Mock Surface if UI is gone/screen is off.
                val activeSurfaceProvider = if (!isScreenOff && uiSurfaceProvider != null) {
                    uiSurfaceProvider
                } else {
                    mockSurfaceProvider
                }

                // If we are already bound to the correct set, don't re-bind to avoid flicker or reset
                val currentLifecycleOwner = this@RecordingService
                
                // Re-prepare Preview with the chosen provider
                if (previewUseCase == null) {
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .build()

                    previewUseCase = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                }
                previewUseCase?.setSurfaceProvider(activeSurfaceProvider)

                // Atomic Binding Check: Ensure all necessary use cases are bound without unbinding first
                val useCasesToBind = mutableListOf<UseCase>()
                previewUseCase?.let { useCasesToBind.add(it) }
                if (videoUseCase != null) {
                    useCasesToBind.add(videoUseCase)
                }

                if (useCasesToBind.isNotEmpty()) {
                    try {
                        // Atomic Binding: Ensure all necessary use cases are bound.
                        // We use an explicit Typed Array to avoid Kotlin vararg inference issues.
                        val useCaseArray = useCasesToBind.toTypedArray()
                        provider.bindToLifecycle(
                            currentLifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            *useCaseArray
                        )
                        Log.d(TAG, "Camera Atomic Binding: ${if (isScreenOff) "BACKGROUND" else "UI"} mode active")
                    } catch (e: Exception) {
                        Log.e(TAG, "Atomic bind failed - attempting standard recovery", e)
                        // Emergency recovery: Some sensors can't handle live binding switches
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            currentLifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            *useCasesToBind.toTypedArray()
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to update camera binding", e)
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
                    
                    // Professional Sync Pulse: Wait for OS to flush file
                    var finalSize = 0L
                    var retries = 0
                    while (retries < 5 && finalSize == 0L) {
                        delay(200)
                        finalSize = chunkFile.length()
                        retries++
                    }

                    if (chunkFile.exists() && finalSize > 0) {
                        val hash = computeSha256(chunkFile)
                        val chunk = EvidenceChunk(
                            recordingId = recording.id,
                            chunkIndex = chunkIndex,
                            localPath = chunkFile.absolutePath,
                            sizeBytes = finalSize,
                            durationMs = CHUNK_DURATION_MS.toInt(),
                            mimeType = "audio/mp4",
                            sha256Hash = hash
                        )
                        chunkRepository.insertChunk(chunk)
                        Log.i(TAG, "Audio chunk $chunkIndex saved: $finalSize bytes")
                        chunkIndex++
                        updateNotification("Recording audio • Chunk $chunkIndex saved")
                    } else {
                        Log.w(TAG, "Audio chunk $chunkIndex was empty after sync - discarding")
                        chunkFile.delete()
                    }
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
                    
                    // Professional Sync Pulse: Wait for OS to flush video file (larger files need more time)
                    var finalSize = 0L
                    var retries = 0
                    while (retries < 8 && finalSize == 0L) {
                        delay(200)
                        finalSize = chunkFile.length()
                        retries++
                    }

                    if (chunkFile.exists() && finalSize > 0) {
                        val hash = computeSha256(chunkFile)
                        val chunk = EvidenceChunk(
                            recordingId = recording.id,
                            chunkIndex = chunkIndex,
                            localPath = chunkFile.absolutePath,
                            sizeBytes = finalSize,
                            durationMs = CHUNK_DURATION_MS.toInt(),
                            mimeType = "video/mp4",
                            sha256Hash = hash
                        )
                        chunkRepository.insertChunk(chunk)
                        Log.i(TAG, "Video chunk $chunkIndex saved: $finalSize bytes")
                        chunkIndex++
                        updateNotification("Recording video • Chunk $chunkIndex saved")
                    } else {
                        Log.w(TAG, "Video chunk $chunkIndex was empty after sync - discarding")
                        chunkFile.delete()
                    }
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
        try {
            mediaRecorder = android.media.MediaRecorder(this@RecordingService).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setMaxDuration(CHUNK_DURATION_MS.toInt())
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaRecorder failed to start", e)
            return
        }
        
        var timePassed = 0L
        while (timePassed < CHUNK_DURATION_MS && _isRecording.value && currentCoroutineContext().isActive) {
            delay(100)
            timePassed += 100
        }

        try { mediaRecorder?.stop() } catch (_: Exception) {}
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    /**
     * Records a single video chunk using CameraX VideoCapture.
     * This method uses the CameraX recording API with a file output.
     */
    private suspend fun recordVideoChunk(outputFile: File) {
        val recorder = videoCapture?.output ?: throw IllegalStateException("Camera not setup")
        
        val finalizeDeferred = CompletableDeferred<Unit>()
        val fileOutputOptions = FileOutputOptions.Builder(outputFile).build()
        var recordingBuilder = recorder.prepareRecording(this@RecordingService, fileOutputOptions)
        
        if (ActivityCompat.checkSelfPermission(this@RecordingService, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordingBuilder = recordingBuilder.withAudioEnabled()
        }
        
        val activeRec = recordingBuilder.start(ContextCompat.getMainExecutor(this@RecordingService)) { event: VideoRecordEvent ->
            if (event is VideoRecordEvent.Finalize) {
                if (event.hasError()) {
                    Log.e(TAG, "Video chunk error: ${event.error}")
                }
                finalizeDeferred.complete(Unit)
            }
        }
        activeRecording = activeRec
        
        var timePassed = 0L
        while (timePassed < CHUNK_DURATION_MS && _isRecording.value && currentCoroutineContext().isActive) {
            delay(100)
            timePassed += 100
        }
        
        activeRec.stop()
        activeRecording = null
        finalizeDeferred.await()
    }

    private fun stopRecordingInternal() {
        if (!_isRecording.value) return

        _isRecording.value = false
        _currentRecordingType.value = null
        timerJob?.cancel()

        // We do NOT cancel chunkJob immediately. 
        // We set _isRecording.value to false, which signals the delay loops to finish, stops the recorder,
        // and safely waits for VideoRecordEvent.Finalize to complete.
        serviceScope.launch {
            chunkJob?.join()
            
            withContext(Dispatchers.Main) {
                try {
                    cameraProvider?.unbindAll()
                    Log.i(TAG, "Camera unbound successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unbinding camera", e)
                }
                cameraProvider = null
                videoCapture = null
                previewSurfaceProvider = null // Crucial: clear preview to avoid black screen on next run
            }

            val current = currentRecording
            if (current != null) {
                recordingRepository.updateRecordingStatus(
                    current.id,
                    RecordingStatus.COMPLETED.value,
                    System.currentTimeMillis()
                )
            }
            _currentRecordingId.value = null

            releaseWakeLock()
            
            // Cleanup Mock Surface
            mockSurface?.release()
            mockSurface = null
            mockSurfaceTexture?.release()
            mockSurfaceTexture = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @CallSuper
    override fun onDestroy() {
        try { unregisterReceiver(screenStateReceiver) } catch (_: Exception) {}
        super.onDestroy()
        // If destroyed without proper stop, mark as interrupted
        if (_isRecording.value) {
            _isRecording.value = false
            _currentRecordingType.value = null
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
        
        // Stop CameraX
        try { activeRecording?.stop() } catch (_: Exception) {}
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        
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
