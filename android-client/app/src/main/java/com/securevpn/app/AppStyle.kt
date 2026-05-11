package com.securevpn.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

val Burgundy = Color(0xFF7A1313)
val BurgundyDark = Color(0xFF4A0A0A)
val BurgundyDeep = Color(0xFF2A0606)
val Gold = Color(0xFFD9B450)
val GoldLight = Color(0xFFE8D77A)
val GoldDeep = Color(0xFFA8862E)
val Bone = Color(0xFFF0E6CC)
val BoneLight = Color(0xFFF7EED8)
val Paper = Color(0xFFECE1C2)
val Ink = Color(0xFF1A1A1A)
val InkSoft = Color(0xFF5A4030)
val OrangeSignal = Color(0xFFE8A040)

val Playfair = FontFamily(
    Font(R.font.playfair_display_regular, FontWeight.Normal),
    Font(R.font.playfair_display_bold_italic, FontWeight.Bold, FontStyle.Italic),
    Font(R.font.playfair_display_black_italic, FontWeight.Black, FontStyle.Italic)
)

val PtSans = FontFamily(
    Font(R.font.pt_sans_regular, FontWeight.Normal),
    Font(R.font.pt_sans_bold, FontWeight.Bold),
    Font(R.font.pt_sans_italic, FontWeight.Normal, FontStyle.Italic)
)

val Russo = FontFamily(Font(R.font.russo_one_regular, FontWeight.Normal))
