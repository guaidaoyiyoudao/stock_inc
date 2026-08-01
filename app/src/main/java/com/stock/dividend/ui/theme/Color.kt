package com.stock.dividend.ui.theme

import androidx.compose.ui.graphics.Color

// ── Clear Sky Finance Palette ──────────────────────────────────────
// 设计语言：Ocean Blue（信任）/ Cool Slate（结构）/ Warm Gold（财富）。
// 每个色阶保留亮色（Light）+ 暗色（Dark）两套，M3 规范：亮色用 tone 40-90，暗色用 tone 20-60。

// Primary: Ocean Blue — clarity, trust, modernity
val Blue7 = Color(0xFF1E3A5F)
val Blue6 = Color(0xFF1D4ED8)
val Blue5 = Color(0xFF2563EB)
val Blue4 = Color(0xFF3B82F6)
val Blue3 = Color(0xFF60A5FA)
val Blue2 = Color(0xFF93C5FD)
val Blue1 = Color(0xFFDBEAFE)
val Blue0 = Color(0xFFEFF6FF)

// Primary 暗色变体（tone 20-60，更深沉适配深色背景）
val BlueDark7 = Color(0xFF0B1F3A)
val BlueDark5 = Color(0xFF5B8DEF)   // 暗色背景上的主色（更亮，保证对比度）
val BlueDark3 = Color(0xFF7DA3F5)
val BlueDark1 = Color(0xFF1E3252)   // 暗色 primaryContainer（深沉蓝调）
val BlueDark0 = Color(0xFF15243D)

// Secondary: Cool Slate — structure, depth
val Slate7 = Color(0xFF0F172A)
val Slate6 = Color(0xFF1E293B)
val Slate5 = Color(0xFF334155)
val Slate4 = Color(0xFF64748B)
val Slate3 = Color(0xFF94A3B8)
val Slate2 = Color(0xFFE2E8F0)
val Slate1 = Color(0xFFF1F5F9)

// Secondary 暗色变体
val SlateDark5 = Color(0xFFB4BFD0)  // 暗色 onSecondaryContainer（浅灰文字）
val SlateDark4 = Color(0xFF8E99AC)
val SlateDark2 = Color(0xFF2A3346)  // 暗色 secondaryContainer
val SlateDark1 = Color(0xFF1A2233)  // 暗色 surface/surfaceVariant

// Tertiary: Warm Gold — wealth, dividends
val Gold5 = Color(0xFF92400E)
val Gold4 = Color(0xFFB45309)
val Gold3 = Color(0xFFD97706)
val Gold2 = Color(0xFFFDE68A)
val Gold1 = Color(0xFFFEF3C7)

// Tertiary 暗色变体
val GoldDark3 = Color(0xFFF0B85A)   // 暗色背景上的金色（更亮）
val GoldDark1 = Color(0xFF3D2E14)   // 暗色 tertiaryContainer

// Finance data colors（语义色：涨/跌/中性）
val FinanceRed = Color(0xFFDC2626)
val FinanceGreen = Color(0xFF059669)
val FinanceRedLight = Color(0xFFFEE2E2)
val FinanceGreenLight = Color(0xFFD1FAE5)

// Finance 暗色变体（暗色背景上用更亮的红绿，保证可读性）
val FinanceRedDark = Color(0xFFF87171)       // 暗色红（更亮）
val FinanceGreenDark = Color(0xFF34D399)     // 暗色绿（更亮）
val FinanceRedContainerDark = Color(0xFF5C1A1A)   // 暗色红容器
val FinanceGreenContainerDark = Color(0xFF0D3D2E)  // 暗色绿容器

// Surfaces
// 亮色：带轻微蓝调的近白（NIA 同款手法，比纯白温润）
val SurfaceBackground = Color(0xFFFBFCFE)
val SurfaceElevated = Color(0xFFFFFFFF)
// 暗色：带蓝调的近黑（非纯黑，避免 OLED 死黑疲劳）
val SurfaceBackgroundDark = Color(0xFF0F1419)
val SurfaceElevatedDark = Color(0xFF1A2028)
