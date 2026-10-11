package com.securevox.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.securevox.app.R

// Two families, two jobs. Hanken Grotesk carries every control and label; Source Serif 4 is
// reserved for transcripts, the one thing people sit and read. Both are bundled variable
// fonts (OFL), so nothing is fetched at runtime.
@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: FontWeight) = Font(
    R.font.hanken_grotesk, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

@OptIn(ExperimentalTextApi::class)
private fun serif(weight: FontWeight) = Font(
    R.font.source_serif4, weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.opticalSizing(17.sp)
    )
)

val UiFont = FontFamily(
    grotesk(FontWeight.Normal), grotesk(FontWeight.Medium),
    grotesk(FontWeight.SemiBold), grotesk(FontWeight.Bold)
)

val TranscriptFont = FontFamily(
    serif(FontWeight.Normal), serif(FontWeight.Medium), serif(FontWeight.SemiBold)
)

/** Transcript body: 18sp on 28sp leaves lines around 45 characters on a phone. */
val TranscriptStyle = TextStyle(
    fontFamily = TranscriptFont,
    fontWeight = FontWeight.Normal,
    fontSize = 18.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.1.sp
)

private fun ui(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) =
    TextStyle(
        fontFamily = UiFont,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp
    )

val Typography = Typography(
    displayLarge = ui(57, 64, tracking = -0.25),
    displayMedium = ui(45, 52),
    displaySmall = ui(36, 44),
    headlineLarge = ui(32, 40, FontWeight.SemiBold),
    headlineMedium = ui(28, 36, FontWeight.SemiBold),
    headlineSmall = ui(24, 32, FontWeight.SemiBold),
    titleLarge = ui(22, 28, FontWeight.SemiBold),
    titleMedium = ui(16, 22, FontWeight.SemiBold, 0.1),
    titleSmall = ui(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = ui(16, 24, tracking = 0.15),
    bodyMedium = ui(14, 20, tracking = 0.2),
    bodySmall = ui(12, 16, tracking = 0.3),
    labelLarge = ui(14, 20, FontWeight.Medium, 0.1),
    labelMedium = ui(12, 16, FontWeight.Medium, 0.3),
    labelSmall = ui(11, 16, FontWeight.Medium, 0.4)
)
