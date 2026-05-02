package com.stock.dividend.ui.theme

import androidx.compose.ui.graphics.Color

// ── Clear Sky Finance Palette ──────────────────────────────────────

// Primary: Ocean Blue — clarity, trust, modernity
val Blue7 = Color(0xFF1E3A5F)
val Blue6 = Color(0xFF1D4ED8)
val Blue5 = Color(0xFF2563EB)
val Blue4 = Color(0xFF3B82F6)
val Blue3 = Color(0xFF60A5FA)
val Blue2 = Color(0xFF93C5FD)
val Blue1 = Color(0xFFDBEAFE)
val Blue0 = Color(0xFFEFF6FF)

// Secondary: Cool Slate — structure, depth
val Slate7 = Color(0xFF0F172A)
val Slate6 = Color(0xFF1E293B)
val Slate5 = Color(0xFF334155)
val Slate4 = Color(0xFF64748B)
val Slate3 = Color(0xFF94A3B8)
val Slate2 = Color(0xFFE2E8F0)
val Slate1 = Color(0xFFF1F5F9)

// Tertiary: Warm Gold — wealth, dividends
val Gold5 = Color(0xFF92400E)
val Gold4 = Color(0xFFB45309)
val Gold3 = Color(0xFFD97706)
val Gold2 = Color(0xFFFDE68A)
val Gold1 = Color(0xFFFEF3C7)

// Finance data colors
val FinanceRed = Color(0xFFDC2626)
val FinanceGreen = Color(0xFF059669)
val FinanceRedLight = Color(0xFFFEE2E2)
val FinanceGreenLight = Color(0xFFD1FAE5)

// Surfaces
val SurfaceBackground = Color(0xFFF8FAFC)
val SurfaceElevated = Color(0xFFFFFFFF)

// ── Glassmorphism Colors ───────────────────────────────────────────

object GlassColors {
    // Light theme glass surfaces
    val LightSurface = Color.White.copy(alpha = 0.75f)
    val LightSurfaceVariant = Color(0xFFF1F5F9).copy(alpha = 0.8f)
    val LightContainer = Color(0xFFEFF6FF).copy(alpha = 0.7f)
    val LightSecondaryContainer = Color(0xFFF1F5F9).copy(alpha = 0.7f)
    val LightBorder = Color.White.copy(alpha = 0.6f)
    val LightSurfaceBorder = Color.White.copy(alpha = 0.4f)

    // Dark theme glass surfaces
    val DarkSurface = Color(0xFF1E293B).copy(alpha = 0.75f)
    val DarkSurfaceVariant = Color(0xFF1E293B).copy(alpha = 0.7f)
    val DarkContainer = Color(0xFF1D4ED8).copy(alpha = 0.35f)
    val DarkSecondaryContainer = Color(0xFF334155).copy(alpha = 0.5f)
    val DarkBorder = Color.White.copy(alpha = 0.08f)
    val DarkSurfaceBorder = Color.White.copy(alpha = 0.06f)

    // Gradient background endpoints - light
    val LightGradientStart = Color(0xFFEFF6FF)
    val LightGradientEnd = Color(0xFFF0F4FF)

    // Gradient background endpoints - dark
    val DarkGradientStart = Color(0xFF0F172A)
    val DarkGradientEnd = Color(0xFF1A1F35)
}
