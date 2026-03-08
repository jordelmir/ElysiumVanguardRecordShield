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
import com.elysium.vanguard.recordshield.service.RecordingService
import com.elysium.vanguard.recordshield.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack)
    ) {
        // Layer 1: Matrix Rain Background (subtle, opacated)
        MatrixRainBackground()

        // Layer 2: Main Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar
            TopBar(onNavigateToGallery = onNavigateToGallery)

            Spacer(modifier = Modifier.weight(1f))

            // Recording Timer (visible during recording)
            if (isRecording) {
                RecordingTimer(elapsedSeconds = elapsedSeconds)
                Spacer(modifier = Modifier.height(32.dp))
            }

            // Status Text
            Text(
                text = if (isRecording) "RECORDING IN PROGRESS" else "READY TO RECORD",
                style = MaterialTheme.typography.labelLarge,
                letterSpacing = 3.sp,
                color = if (isRecording) RecordingRed else MatrixGreen
            )

            Spacer(modifier = Modifier.height(24.dp))

            // === THE MASSIVE RECORDING BUTTON ===
            RecordButton(
                isRecording = isRecording,
                onToggle = {
                    if (isRecording) {
                        RecordingService.stopRecording(context)
                    } else {
                        RecordingService.startVideoRecording(context)
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Mode Selector (Video / Audio)
            if (!isRecording) {
                ModeSelector(
                    onVideoSelected = {
                        RecordingService.startVideoRecording(context)
                    },
                    onAudioSelected = {
                        RecordingService.startAudioRecording(context)
                    }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Bottom Status Card
            StatusCard(isRecording = isRecording)
        }
    }
}

// ============================================================================
// MASSIVE RECORDING BUTTON with Animated Glow
// ============================================================================

@Composable
fun RecordButton(
    isRecording: Boolean,
    onToggle: () -> Unit
) {
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
        modifier = Modifier.size(200.dp)
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(200.dp)
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
                .size(170.dp)
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
                .size(140.dp)
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
                    modifier = Modifier.size(56.dp)
                )
            } else {
                // Shield icon
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = "Start Recording",
                    tint = DeepBlack,
                    modifier = Modifier.size(56.dp)
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
fun RecordingTimer(elapsedSeconds: Long) {
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
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            letterSpacing = 4.sp
        )
    }
}

// ============================================================================
// MODE SELECTOR (Video/Audio)
// ============================================================================

@Composable
fun ModeSelector(
    onVideoSelected: () -> Unit,
    onAudioSelected: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Video mode
        GlassButton(
            icon = Icons.Default.Videocam,
            label = "VIDEO",
            accentColor = ElectricBlue,
            onClick = onVideoSelected
        )

        // Audio mode
        GlassButton(
            icon = Icons.Default.Mic,
            label = "AUDIO",
            accentColor = DeepPurple,
            onClick = onAudioSelected
        )
    }
}

@Composable
fun GlassButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(GlassSurface)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = accentColor,
            modifier = Modifier.size(28.dp)
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

// Helper extension for WindowInsetsPadding
@Composable
fun Modifier.statusBarsPadding(): Modifier = this.then(
    Modifier.windowInsetsPadding(WindowInsets.statusBars)
)

@Composable
fun Modifier.navigationBarsPadding(): Modifier = this.then(
    Modifier.windowInsetsPadding(WindowInsets.navigationBars)
)
