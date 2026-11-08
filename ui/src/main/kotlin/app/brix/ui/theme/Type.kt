package app.brix.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.brix.ui.R

private val OpenSans = FontFamily(
    Font(R.font.open_sans, weight = FontWeight.Light),
    Font(R.font.open_sans, weight = FontWeight.Normal),
    Font(R.font.open_sans, weight = FontWeight.Medium),
    Font(R.font.open_sans, weight = FontWeight.SemiBold),
    Font(R.font.open_sans, weight = FontWeight.Bold),
    Font(R.font.open_sans, weight = FontWeight.ExtraBold),
)

private fun TextStyle.withOpenSans() = copy(fontFamily = OpenSans)

private val DefaultTypography = Typography()

val Typography = DefaultTypography.copy(
    displayLarge = DefaultTypography.displayLarge.withOpenSans(),
    displayMedium = DefaultTypography.displayMedium.withOpenSans(),
    displaySmall = DefaultTypography.displaySmall.withOpenSans(),
    headlineLarge = DefaultTypography.headlineLarge.withOpenSans(),
    headlineMedium = DefaultTypography.headlineMedium.withOpenSans(),
    headlineSmall = DefaultTypography.headlineSmall.withOpenSans(),
    titleMedium = DefaultTypography.titleMedium.withOpenSans(),
    titleSmall = DefaultTypography.titleSmall.withOpenSans(),
    bodyLarge = TextStyle(
        fontFamily = OpenSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
    ),
    bodyMedium = DefaultTypography.bodyMedium.withOpenSans(),
    bodySmall = DefaultTypography.bodySmall.withOpenSans(),
    labelLarge = DefaultTypography.labelLarge.withOpenSans(),
    labelMedium = DefaultTypography.labelMedium.withOpenSans(),
    labelSmall = DefaultTypography.labelSmall.withOpenSans(),
    titleLarge = TextStyle(
        fontFamily = OpenSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
)
