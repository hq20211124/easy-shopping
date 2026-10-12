package com.easyshopping.android.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌色（P0 占位，设计稿出图后替换 —— plans/android-frontend.md 1.3.3）
val BrandOrange = Color(0xFFFF5722)
val BrandOrangeDark = Color(0xFFE64A19)
val PriceRed = Color(0xFFF44336)
val SuccessGreen = Color(0xFF4CAF50)
val WarningAmber = Color(0xFFFF9800)
val TextPrimary = Color(0xFF212121)
val TextSecondary = Color(0xFF757575)
val Divider = Color(0xFFEEEEEE)
val PageBackground = Color(0xFFF5F5F5)

private val LightColors = lightColorScheme(
    primary = BrandOrange,
    onPrimary = Color.White,
    secondary = BrandOrangeDark,
    error = PriceRed,
    background = PageBackground,
    surface = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
)

private val DarkColors = darkColorScheme(
    primary = BrandOrange,
    onPrimary = Color.White,
    error = PriceRed,
)

@Composable
fun AppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
