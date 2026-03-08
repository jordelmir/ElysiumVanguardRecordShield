package com.elysium.vanguard.recordshield.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * ============================================================================
 * Neo-Futuristic Color System — Elysium Vanguard Record Shield
 * ============================================================================
 *
 * Design Philosophy:
 *   - Deep Black/Dark Grey base for immersive, high-contrast UI
 *   - Neon accents for action elements (recording button, active states)
 *   - Glassmorphism: semi-transparent surfaces with backdrop blur
 *   - Matrix-inspired green for primary brand identity
 *   - Electric blue and deep purple for secondary/tertiary accents
 * ============================================================================
 */

// ============================================================================
// BASE — Deep Space Background
// ============================================================================
val DeepBlack = Color(0xFF0A0A0F)
val DarkSurface = Color(0xFF12121A)
val CardSurface = Color(0xFF1A1A26)
val ElevatedSurface = Color(0xFF222233)
val SubtleBorder = Color(0xFF2A2A3D)

// ============================================================================
// NEON ACCENTS — Masculine, Phosphorescent Energy
// ============================================================================
val MatrixGreen = Color(0xFF00FF41)          // Primary — Matrix-style bright green
val MatrixGreenDim = Color(0xFF00CC33)       // Primary variant
val MatrixGreenGlow = Color(0x6600FF41)      // Glow overlay (40% opacity)
val MatrixGreenSubtle = Color(0x1A00FF41)    // Very subtle wash (10%)

val ElectricBlue = Color(0xFF00D4FF)         // Secondary — electric cyan blue
val ElectricBlueDim = Color(0xFF00A8CC)      // Secondary variant
val ElectricBlueGlow = Color(0x6600D4FF)     // Glow overlay

val DeepPurple = Color(0xFFBB86FC)           // Tertiary — royal purple
val DeepPurpleDim = Color(0xFF9966CC)        // Tertiary variant
val DeepPurpleGlow = Color(0x66BB86FC)       // Glow overlay

// ============================================================================
// STATUS COLORS
// ============================================================================
val RecordingRed = Color(0xFFFF1744)         // Active recording indicator
val RecordingRedGlow = Color(0x66FF1744)     // Red glow during recording
val WarningAmber = Color(0xFFFFAB00)         // Upload pending / warnings
val SuccessGreen = Color(0xFF00E676)         // Upload complete

// ============================================================================
// TEXT
// ============================================================================
val TextPrimary = Color(0xFFE8E8F0)          // High-contrast white
val TextSecondary = Color(0xFF9999B3)        // Muted grey
val TextTertiary = Color(0xFF666680)         // Very muted

// ============================================================================
// GLASSMORPHISM
// ============================================================================
val GlassSurface = Color(0x1AFFFFFF)         // 10% white overlay
val GlassBorder = Color(0x33FFFFFF)          // 20% white border
val GlassHighlight = Color(0x0DFFFFFF)       // 5% white inner highlight
