package com.elysium.vanguard.recordshield.ui.screen.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.graphicsLayer
import androidx.camera.view.PreviewView
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.viewinterop.AndroidView
import com.elysium.vanguard.recordshield.service.RecordingService
import com.elysium.vanguard.recordshield.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random
import android.os.PowerManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import com.elysium.vanguard.recordshield.domain.model.RecordingType

/**
 * ============================================================================
 * HomeScreen — The Command Center
 * ============================================================================
 *
 * Design: Neo-futuristic dark interface with:
 *   - Subtle Matrix Rain background animation
 *   - Massive pulsing recording button with animated glow
 *   - Glassmorphism status cards
 *   - "Breathing" interface that feels alive
 * ============================================================================
 */
@Composable
fun HomeScreen(
    onNavigateToGallery: () -> Unit
) {
    val context = LocalContext.current
    val isRecording by RecordingService.isRecording.collectAsState()
    val elapsedSeconds by RecordingService.elapsedSeconds.collectAsState()
    val currentType by RecordingService.currentRecordingType.collectAsState()
    val haptics = LocalHapticFeedback.current
    val configuration = LocalConfiguration.current
    val isLargeScreen = configuration.screenWidthDp > 600
    val screenWidth = configuration.screenWidthDp.dp
    val scrollState = rememberScrollState()

    // Design Tokens - Scaled for Foldables
    val mainPadding = if (isLargeScreen) 32.dp else 12.dp
    val buttonSize = if (isLargeScreen) 220.dp else 150.dp
    val previewWidthFraction = if (isLargeScreen) 0.6f else 0.5f // Drastically reduced for narrow screens
    val gridColumns = if (isLargeScreen) 4 else 2

    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    // Strict OS-level back button interception
    BackHandler(enabled = isRecording) {
        // Prevent backing out of the app when actively recording for evidence integrity
        Toast.makeText(context, "Cannot exit while recording evidence.", Toast.LENGTH_SHORT).show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        // Layer 1: Matrix Rain Background (subtle, opacated)
        MatrixRainBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(mainPadding)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            TopBar(onNavigateToGallery = onNavigateToGallery)

            Spacer(modifier = Modifier.height(24.dp))

            // Battery Optimization Warning Card
            if (!isIgnoringBatteryOptimizations) {
                BatteryOptimizationCard(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                })
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Recording Timer (visible during recording)
            if (isRecording) {
                RecordingTimer(elapsedSeconds = elapsedSeconds, isLargeScreen = isLargeScreen)
                Spacer(modifier = Modifier.height(32.dp))
            }

            // LIVELY STATUS TEXT
            Text(
                text = if (isRecording) "RECORDING IN PROGRESS" else "READY TO RECORD",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 2.sp,
                color = if (isRecording) RecordingRed else MatrixGreen
            )

            Spacer(modifier = Modifier.height(16.dp))

            RecordButton(
                isRecording = isRecording,
                buttonSize = buttonSize,
                onToggle = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (isRecording) {
                        RecordingService.stopRecording(context)
                    } else {
                        RecordingService.startVideoRecording(context)
                    }
                }
            )

            if (!isRecording) {
                Spacer(modifier = Modifier.height(16.dp))
                ModeSelector(
                    isLargeScreen = isLargeScreen,
                    onVideoSelected = { RecordingService.startVideoRecording(context) },
                    onAudioSelected = { RecordingService.startAudioRecording(context) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // LIVE EVIDENCE PREVIEW (Controlled Height to prevent overlap)
            LivePreview(
                isRecording = isRecording, 
                recordingType = currentType,
                modifier = Modifier
                    .fillMaxWidth(previewWidthFraction)
                    .height(if (isLargeScreen) 300.dp else 160.dp) // Fixed height is safer than aspectRatio here
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Responsive Status Grid
            val statusItems = remember {
                listOf(
                    StatusItem("ENCRYPTION", "AES-256", Icons.Default.Shield, MatrixGreen),
                    StatusItem("LOCATION", "PROTECTED", Icons.Default.GpsFixed, MatrixGreen),
                    StatusItem("STORAGE", "ENCRYPTED", Icons.Default.Folder, MatrixGreen),
                    StatusItem("SIGNAL", "SECURE", Icons.Default.Wifi, MatrixGreen)
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
            ) {
                items(statusItems) { item ->
                    StatusCardUi(item, isLargeScreen)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun LivePreview(isRecording: Boolean, recordingType: RecordingType?, modifier: Modifier = Modifier) {
    DisposableEffect(Unit) {
        onDispose {
            RecordingService.previewSurfaceProvider = null
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlassSurface)
            .border(1.dp, if (isRecording) RecordingRed.copy(alpha = 0.5f) else MatrixGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        // ALWAYS keep AndroidView in the hierarchy so the surface provider survives transitions
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    this.scaleType = PreviewView.ScaleType.FILL_CENTER
                    RecordingService.previewSurfaceProvider = this.surfaceProvider
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isRecording) {
            if (recordingType == RecordingType.AUDIO) {
                // Dim the camera if it's Audio mode to make it clear we are only recording audio
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = RecordingRed,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "AUDIO RECORDING ACTIVE",
                            color = RecordingRed,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // Top-left LIVE badge
            val infiniteTransition = rememberInfiniteTransition(label = "live_pulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulse_scale"
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(RecordingRed.copy(alpha = 0.8f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                        .clip(CircleShape)
                        .background(Color.White)
                    )
                    Text("LIVE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            // Standby Overlay
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = MatrixGreen.copy(alpha = 0.5f),
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        "PREVIEW STANDBY",
                        color = MatrixGreen.copy(alpha = 0.5f),
                        fontSize = 10.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

data class StatusItem(
    val label: String,
    val value: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val color: Color
)

@Composable
fun StatusCardUi(item: StatusItem, isLargeScreen: Boolean) {
    val iconSize = if (isLargeScreen) 18.dp else 14.dp
    val labelSize = if (isLargeScreen) 11.sp else 9.sp
    val valueSize = if (isLargeScreen) 13.sp else 11.sp

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(GlassSurface)
            .border(0.5.dp, GlassBorder, RoundedCornerShape(8.dp))
            .padding(if (isLargeScreen) 12.dp else 10.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(item.icon, null, tint = item.color, modifier = Modifier.size(iconSize))
                Text(item.label, color = item.color, fontSize = labelSize, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(item.value, color = TextSecondary, fontSize = valueSize, fontWeight = FontWeight.Light)
        }
    }
}

// ============================================================================
// MASSIVE RECORDING BUTTON with Animated Glow
// ============================================================================

@Composable
fun RecordButton(
    isRecording: Boolean,
    buttonSize: androidx.compose.ui.unit.Dp,
    onToggle: () -> Unit
) {
    val ringSize = buttonSize * 0.85f
    val innerSize = buttonSize * 0.7f
    val iconSize = if (buttonSize > 180.dp) 56.dp else 40.dp
    // Pulsing glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    val glowRadius by infiniteTransition.animateFloat(
        initialValue = 20f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowRadius"
    )

    val buttonColor = if (isRecording) RecordingRed else MatrixGreen
    val glowColor = if (isRecording) RecordingRedGlow else MatrixGreenGlow

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(buttonSize)
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(buttonSize)
                .drawBehind {
                    drawCircle(
                        color = buttonColor.copy(alpha = glowAlpha * 0.4f),
                        radius = size.minDimension / 2 + glowRadius
                    )
                    drawCircle(
                        color = buttonColor.copy(alpha = glowAlpha * 0.2f),
                        radius = size.minDimension / 2 + glowRadius * 1.5f
                    )
                }
        )

        // Glass border ring
        Box(
            modifier = Modifier
                .size(ringSize)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            buttonColor.copy(alpha = 0.6f),
                            buttonColor.copy(alpha = 0.1f),
                            buttonColor.copy(alpha = 0.4f)
                        )
                    ),
                    shape = CircleShape
                )
                .background(GlassSurface, CircleShape)
        )

        // Inner button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(innerSize)
                .shadow(
                    elevation = 16.dp,
                    shape = CircleShape,
                    ambientColor = buttonColor,
                    spotColor = buttonColor
                )
                .clip(CircleShape)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            buttonColor,
                            buttonColor.copy(alpha = 0.7f)
                        )
                    )
                )
                .clickable(onClick = onToggle)
        ) {
            if (isRecording) {
                // Stop icon (square)
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = "Stop Recording",
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            } else {
                // Shield icon
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Start Recording",
                    tint = DeepBlack,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

// ============================================================================
// MATRIX RAIN BACKGROUND (Subtle, opacated)
// ============================================================================

@Composable
fun MatrixRainBackground() {
    val matrixChars = remember { "01アイウエオカキクケコ" }
    val columns = 20
    val drops = remember {
        List(columns) { mutableStateOf(Random.nextFloat() * 100) }
    }

    // Animate drops falling
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            drops.forEach { drop ->
                drop.value = if (drop.value > 100) Random.nextFloat() * -10 else drop.value + 0.5f
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val charSize = size.width / columns

        drops.forEachIndexed { index, drop ->
            val x = index * charSize
            val y = (drop.value / 100f) * size.height

            // Draw a fading trail of characters
            for (trail in 0..5) {
                val trailY = y - (trail * charSize * 1.5f)
                if (trailY > 0 && trailY < size.height) {
                    drawCircle(
                        color = MatrixGreen.copy(alpha = (0.08f - trail * 0.012f).coerceAtLeast(0f)),
                        radius = 2f,
                        center = Offset(x + charSize / 2, trailY)
                    )
                }
            }
        }
    }
}

// ============================================================================
// TOP BAR
// ============================================================================

@Composable
fun TopBar(onNavigateToGallery: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "RECORD SHIELD",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                letterSpacing = 2.sp
            )
            Text(
                text = "ELYSIUM VANGUARD",
                style = MaterialTheme.typography.labelSmall,
                color = MatrixGreen,
                letterSpacing = 3.sp
            )
        }

        // Gallery button
        IconButton(
            onClick = onNavigateToGallery,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurface)
                .border(1.dp, GlassBorder, RoundedCornerShape(12.dp))
        ) {
            Icon(
                imageVector = Icons.Default.VideoLibrary,
                contentDescription = "Gallery",
                tint = ElectricBlue
            )
        }
    }
}

// ============================================================================
// RECORDING TIMER
// ============================================================================

@Composable
fun RecordingTimer(elapsedSeconds: Long, isLargeScreen: Boolean) {
    val hours = elapsedSeconds / 3600
    val minutes = (elapsedSeconds % 3600) / 60
    val seconds = elapsedSeconds % 60

    // Blinking dot animation
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Blinking red dot
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(RecordingRed.copy(alpha = dotAlpha))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = String.format("%02d:%02d:%02d", hours, minutes, seconds),
            fontSize = if (isLargeScreen) 56.sp else 36.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = if (isLargeScreen) 4.sp else 2.sp
        )
    }
}

// ============================================================================
// MODE SELECTOR (Video/Audio)
// ============================================================================

@Composable
fun ModeSelector(
    isLargeScreen: Boolean,
    onVideoSelected: () -> Unit,
    onAudioSelected: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (isLargeScreen) 16.dp else 8.dp)
    ) {
        // Video mode
        GlassButton(
            icon = Icons.Default.Videocam,
            label = "VIDEO",
            accentColor = ElectricBlue,
            isLargeScreen = isLargeScreen,
            onClick = onVideoSelected
        )

        // Audio mode
        GlassButton(
            icon = Icons.Default.Mic,
            label = "AUDIO",
            accentColor = DeepPurple,
            isLargeScreen = isLargeScreen,
            onClick = onAudioSelected
        )
    }
}

@Composable
fun GlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accentColor: Color,
    isLargeScreen: Boolean,
    onClick: () -> Unit
) {
    val hPadding = if (isLargeScreen) 32.dp else 24.dp
    val vPadding = if (isLargeScreen) 16.dp else 12.dp
    val iconSize = if (isLargeScreen) 28.dp else 22.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = hPadding, vertical = vPadding)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accentColor,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            letterSpacing = 2.sp
        )
    }
}

// ============================================================================
// STATUS CARD (Glassmorphism)
// ============================================================================

@Composable
fun StatusCard(isRecording: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatusItem(
                label = "STATUS",
                value = if (isRecording) "ACTIVE" else "STANDBY",
                color = if (isRecording) RecordingRed else MatrixGreen
            )
            StatusItem(
                label = "SECURITY",
                value = "ARMED",
                color = MatrixGreen
            )
            StatusItem(
                label = "CLOUD",
                value = "SYNCED",
                color = ElectricBlue
            )
        }
    }
}

@Composable
fun StatusItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextTertiary,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// ============================================================================
// BATTERY OPTIMIZATION CARD
// ============================================================================
@Composable
fun BatteryOptimizationCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = RecordingRed.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, RecordingRed)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = RecordingRed
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = "BATTERY OPTIMIZATION ACTIVE",
                    style = MaterialTheme.typography.labelMedium,
                    color = RecordingRed
                )
                Text(
                    text = "Tap to disable. OS may kill recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}
