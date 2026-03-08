package com.elysium.vanguard.recordshield.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.elysium.vanguard.recordshield.service.UploadWorker
import com.elysium.vanguard.recordshield.ui.screen.gallery.GalleryScreen
import com.elysium.vanguard.recordshield.ui.screen.home.HomeScreen
import com.elysium.vanguard.recordshield.ui.screen.pin.PinScreen
import com.elysium.vanguard.recordshield.ui.screen.player.PlayerScreen
import com.elysium.vanguard.recordshield.ui.theme.DeepBlack
import com.elysium.vanguard.recordshield.ui.theme.RecordShieldTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * ============================================================================
 * MainActivity — Single Activity Architecture (Updated with Full Navigation)
 * ============================================================================
 *
 * Navigation Flow:
 *   home → [PIN gate if set] → gallery → player
 *
 * Anti-Sabotage Navigation:
 *   - Back press during recording is intercepted (future Phase 3 expansion)
 *   - Gallery always requires PIN re-verification
 * ============================================================================
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            android.util.Log.i("MainActivity", "$permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestRequiredPermissions()
        UploadWorker.schedulePeriodicUpload(this)

        setContent {
            RecordShieldTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DeepBlack
                ) {
                    RecordShieldApp()
                }
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }
}

/**
 * Full navigation graph with PIN gating.
 */
@Composable
fun RecordShieldApp() {
    val navController = rememberNavController()
    val viewModel: MainViewModel = hiltViewModel()

    val recordings by viewModel.recordings.collectAsState()
    val isPinVerified by viewModel.isPinVerified.collectAsState()
    val selectedChunks by viewModel.selectedRecordingChunks.collectAsState()

    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToGallery = {
                    if (viewModel.isPinSet()) {
                        // Require PIN verification before gallery access
                        viewModel.resetPinVerification()
                        navController.navigate("pin_gate")
                    } else {
                        navController.navigate("gallery")
                    }
                }
            )
        }

        composable("pin_gate") {
            PinScreen(
                title = "ACCESS VAULT",
                subtitle = "Enter PIN to view evidence",
                onPinVerified = {
                    navController.navigate("gallery") {
                        popUpTo("pin_gate") { inclusive = true }
                    }
                },
                onVerifyPin = { pin -> viewModel.verifyPin(pin) }
            )
        }

        composable("gallery") {
            GalleryScreen(
                recordings = recordings,
                onRecordingClick = { recording ->
                    viewModel.loadChunksForRecording(recording.id)
                    navController.navigate("player/${recording.id}")
                },
                onBackClick = {
                    navController.popBackStack()
                },
                onDeleteRecording = { recording ->
                    viewModel.deleteRecording(recording)
                }
            )
        }

        composable("player/{recordingId}") { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            PlayerScreen(
                recordingId = recordingId,
                chunks = selectedChunks,
                onBackClick = {
                    navController.popBackStack()
                }
            )
        }
    }
}
