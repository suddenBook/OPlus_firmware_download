package com.desmond.ofd.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.vector.ImageVector

enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    Home("home", "Home", Icons.Outlined.Home),
    Downloads("downloads", "Downloads", Icons.Outlined.Download),
    Settings("info", "Info", Icons.Outlined.Info);

    companion object {
        fun fromRoute(route: String?): TopDestination =
            entries.firstOrNull { it.route == route } ?: Home
    }
}
