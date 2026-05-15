package com.securevpn.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun AppScreen.screenOrder(): Int = when (this) {
    AppScreen.Settings -> 0
    AppScreen.Routing -> 0
    AppScreen.Home -> 1
    AppScreen.Servers -> 1
    AppScreen.Speed -> 2
}

fun AppScreen.swipeTarget(direction: Int): AppScreen {
    val screens = listOf(AppScreen.Settings, AppScreen.Home, AppScreen.Speed)
    val currentIndex = screens.indexOf(if (this == AppScreen.Servers) AppScreen.Home else this).coerceAtLeast(0)
    return screens[(currentIndex + direction).coerceIn(0, screens.lastIndex)]
}

private data class NavItem(
    val screen: AppScreen,
    val icon: ImageVector,
    val label: String
)

private val NavItems = listOf(
    NavItem(AppScreen.Settings, Icons.Rounded.Settings, "устав"),
    NavItem(AppScreen.Home, Icons.Rounded.PowerSettingsNew, "связь"),
    NavItem(AppScreen.Speed, Icons.Rounded.Speed, "замер")
)

@Composable
fun BottomNav(
    current: AppScreen,
    onScreen: (AppScreen) -> Unit,
    modifier: Modifier = Modifier,
    light: Boolean = false
) {
    val base = if (light) Ink else Gold
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(55.dp)
            .border(1.dp, base.copy(alpha = 0.65f))
            .background(if (light) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.1f))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItems.forEach { item ->
            val selected = item.screen == current
            val navInteraction = remember(item.screen) { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = navInteraction,
                        indication = null
                    ) { onScreen(item.screen) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    item.icon,
                    contentDescription = null,
                    tint = if (selected) (if (light) Burgundy else GoldLight) else base.copy(alpha = 0.62f),
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    item.label.uppercase(),
                    color = if (selected) (if (light) Burgundy else GoldLight) else base.copy(alpha = 0.62f),
                    fontSize = 7.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
