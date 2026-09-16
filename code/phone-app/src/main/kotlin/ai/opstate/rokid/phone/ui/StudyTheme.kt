package ai.opstate.rokid.phone.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink=Color(0xFF20252D)
val Quiet=Color(0xFF747D8A)
val Canvas=Color(0xFFF5F6F8)
val Success=Color(0xFF287A64)
val Amber=Color(0xFFAA751B)
val Line=Color(0xFFE6E8EC)
private val StudyColors=lightColorScheme(
    primary=Ink,onPrimary=Color.White,primaryContainer=Color(0xFFDDECE2),onPrimaryContainer=Ink,
    secondary=Success,secondaryContainer=Color(0xFFE5EEE6),background=Canvas,onBackground=Ink,
    surface=Color.White,onSurface=Ink,surfaceVariant=Color(0xFFEEF0F3),onSurfaceVariant=Quiet,
    outline=Line,error=Color(0xFFAE4242),
)
private val StudyTypography=Typography(
    headlineLarge=TextStyle(fontFamily=FontFamily.SansSerif,fontWeight=FontWeight.Bold,fontSize=30.sp,lineHeight=38.sp),
    headlineMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=25.sp,lineHeight=32.sp),
    titleLarge=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=21.sp,lineHeight=29.sp),
    titleMedium=TextStyle(fontWeight=FontWeight.SemiBold,fontSize=16.sp,lineHeight=24.sp),
    bodyLarge=TextStyle(fontSize=16.sp,lineHeight=25.sp),
    bodyMedium=TextStyle(fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontSize=12.sp,lineHeight=19.sp),
    labelLarge=TextStyle(fontSize=14.sp,fontWeight=FontWeight.SemiBold),
    labelMedium=TextStyle(fontSize=12.sp,fontWeight=FontWeight.Medium),
    labelSmall=TextStyle(fontSize=10.sp,letterSpacing=0.6.sp,fontWeight=FontWeight.Medium),
)
@Composable fun StudyTheme(content: @Composable ()->Unit) { MaterialTheme(colorScheme=StudyColors,typography=StudyTypography,content=content) }
