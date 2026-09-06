package com.zhukongqwq.hanser.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 自绘主题：金黄「憨憨」风格（与 Windows 版 App.xaml 色板一致：金 #F5B301 / 浅金 #FEF3C7 / 深金 #D49A00 / 文字棕 #5C4300，灰阶 #374151/#6B7280/#E5E7EB）。 */
object HanserColors {
    val Background = Color(0xFFF9FAFB)      // 页面底（Windows F9FAFB）
    val Surface = Color(0xFFFFFFFF)         // 卡片/气泡面
    val Accent = Color(0xFFF5B301)          // 主金（AccentBrush）
    val AccentDeep = Color(0xFFA16207)      // 深金强调文字（对应 AccentPressed/AccentText 系）
    val AccentSoft = Color(0xFFFEF3C7)      // 浅金底（AccentSoftBrush）
    val TextPrimary = Color(0xFF374151)     // 主文字（Windows 374151）
    val TextSecondary = Color(0xFF6B7280)   // 次文字
    val BubbleUser = Color(0xFFF5B301)      // 用户气泡 = 主金
    val BubbleAssistant = Color(0xFFFEF3C7) // 助手气泡 = 浅金
    val BubbleText = Color(0xFF5C4300)      // 金底上的深棕文字（AccentTextBrush）
    val SystemGray = Color(0xFFF3F4F6)
    val Border = Color(0xFFFDE68A)          // 淡金边
}

private val LightColors = lightColorScheme(
    primary = HanserColors.Accent,
    onPrimary = Color.White,
    primaryContainer = HanserColors.AccentSoft,
    onPrimaryContainer = HanserColors.AccentDeep,
    secondary = HanserColors.AccentDeep,
    background = HanserColors.Background,
    surface = HanserColors.Surface,
    onBackground = HanserColors.TextPrimary,
    onSurface = HanserColors.TextPrimary,
    outline = HanserColors.Border,
)

@Composable
fun HanserTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        shapes = androidx.compose.material3.Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp)
        ),
        content = content
    )
}
