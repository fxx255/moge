package com.moge.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// ---- 方格本（日间）：方格作业纸 + 墨水蓝 + 荧光笔黄 + 红笔 ----
internal val Paper = Color(0xFFF7F5EF)
internal val PaperGrid = Color(0xFFDDE4EC)
internal val Ink = Color(0xFF1F3A8A)
internal val InkDeep = Color(0xFF0E1F52)
internal val InkText = Color(0xFF1A1B1E)
internal val InkMuted = Color(0xFF5F6570)
internal val Highlighter = Color(0xFFFFE066)
internal val RedPen = Color(0xFFD9362B)

// ---- 黑板（夜间）：墨绿板面 + 粉笔白 / 黄 / 蓝 / 红 ----
internal val Board = Color(0xFF1E2B26)
internal val BoardGrid = Color(0xFF2A3A33)
internal val ChalkWhite = Color(0xFFECEDE6)
internal val ChalkMuted = Color(0xFFA9B5AD)
internal val ChalkYellow = Color(0xFFF2D675)
internal val ChalkBlue = Color(0xFF8EC5FC)
internal val ChalkRed = Color(0xFFFF8A80)

internal val PaperColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F7),
    onPrimaryContainer = InkDeep,
    inversePrimary = Color(0xFFB4C5FF),
    secondary = Color(0xFF6B5A00),
    onSecondary = Color.White,
    secondaryContainer = Highlighter,
    onSecondaryContainer = Color(0xFF2A2300),
    tertiary = RedPen,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFCE3E0),
    onTertiaryContainer = Color(0xFF5C0F09),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Paper,
    onBackground = InkText,
    surface = Color.White,
    onSurface = InkText,
    surfaceVariant = Color(0xFFECE9E0),
    onSurfaceVariant = InkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = Color(0xFF2E3036),
    inverseOnSurface = Color(0xFFF1F0F4),
    outline = Color(0xFF7A808A),
    outlineVariant = Color(0xFFD3D6DB),
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE5E2D8),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF6),
    surfaceContainer = Color(0xFFF3F1EA),
    surfaceContainerHigh = Color(0xFFECE9E0),
    surfaceContainerHighest = Color(0xFFE5E2D8),
)

internal val ChalkColorScheme = darkColorScheme(
    primary = ChalkYellow,
    onPrimary = Color(0xFF2B2400),
    primaryContainer = Color(0xFF4A4320),
    onPrimaryContainer = Color(0xFFFFF0B3),
    inversePrimary = Color(0xFF6B5A00),
    secondary = ChalkBlue,
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF2C4A63),
    onSecondaryContainer = Color(0xFFD6EBFF),
    tertiary = ChalkRed,
    onTertiary = Color(0xFF5C0F09),
    tertiaryContainer = Color(0xFF5C2A26),
    onTertiaryContainer = Color(0xFFFFDAD5),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Board,
    onBackground = ChalkWhite,
    surface = Color(0xFF24332D),
    onSurface = ChalkWhite,
    surfaceVariant = Color(0xFF2E3F37),
    onSurfaceVariant = ChalkMuted,
    surfaceTint = Color.Transparent,
    inverseSurface = ChalkWhite,
    inverseOnSurface = Board,
    outline = Color(0xFF7F8E85),
    outlineVariant = Color(0xFF3B4C43),
    scrim = Color.Black,
    surfaceBright = Color(0xFF34463D),
    surfaceDim = Color(0xFF1A2621),
    surfaceContainerLowest = Color(0xFF1A2621),
    surfaceContainerLow = Color(0xFF22302A),
    surfaceContainer = Color(0xFF283831),
    surfaceContainerHigh = Color(0xFF2E3F37),
    surfaceContainerHighest = Color(0xFF34463D),
)

/**
 * Material 色板之外的「纸面」专用色：方格线、红色页边线、荧光笔、胶带、印章、便利贴。
 * 招牌组件（GridPaper / Highlighter / Tape / MarginLine / Stamp）只从这里取色，
 * 保证日夜两套主题切换时一起变。
 */
@Immutable
data class PaperColors(
    val isChalk: Boolean,
    val grid: Color,
    val marginLine: Color,
    val highlighter: Color,
    val tape: Color,
    val stamp: Color,
    val cardStroke: Color,
    val stickyNote: Color,
    val onStickyNote: Color,
    val scratch: Color,
)

internal val PaperExtras = PaperColors(
    isChalk = false,
    grid = PaperGrid,
    marginLine = Color(0xFFE8837B),
    highlighter = Highlighter.copy(alpha = 0.85f),
    tape = Color(0xFFF1E4B0).copy(alpha = 0.78f),
    stamp = RedPen,
    cardStroke = Color(0xFF2B2F36).copy(alpha = 0.85f),
    stickyNote = Color(0xFFFFF3A8),
    onStickyNote = Color(0xFF2A2300),
    scratch = Color(0xFFF1EFE8),
)

internal val ChalkExtras = PaperColors(
    isChalk = true,
    grid = BoardGrid,
    marginLine = ChalkRed.copy(alpha = 0.55f),
    highlighter = ChalkYellow.copy(alpha = 0.32f),
    tape = Color(0xFFC9C3A8).copy(alpha = 0.35f),
    stamp = ChalkRed,
    cardStroke = ChalkWhite.copy(alpha = 0.38f),
    stickyNote = Color(0xFF4A4320),
    onStickyNote = Color(0xFFFFF0B3),
    scratch = Color(0xFF22302A),
)
