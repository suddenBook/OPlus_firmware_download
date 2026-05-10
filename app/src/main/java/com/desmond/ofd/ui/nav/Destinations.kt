package com.desmond.ofd.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import com.desmond.ofd.R
import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes — Compose Navigation 2.8+ resolves these via Kotlin
 * Serialization, eliminating string-typo bugs at compile time.
 */
@Serializable object HomeRoute
@Serializable object DownloadsRoute
@Serializable object ManualRoute
@Serializable object InfoRoute

/** Top-level destination metadata used by the adaptive navigation suite (rail/bar/drawer). */
enum class TopDestination(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
    val matches: (NavDestination?) -> Boolean,
) {
    Home(R.string.nav_home, Icons.Outlined.Home, { it?.hasRoute<HomeRoute>() == true }),
    Downloads(R.string.nav_downloads, Icons.Outlined.Download, { it?.hasRoute<DownloadsRoute>() == true }),
    Manual(R.string.nav_manual, Icons.AutoMirrored.Outlined.MenuBook, { it?.hasRoute<ManualRoute>() == true }),
    Settings(R.string.nav_info, Icons.Outlined.Info, { it?.hasRoute<InfoRoute>() == true });

    companion object {
        fun fromDestination(destination: NavDestination?): TopDestination =
            entries.firstOrNull { it.matches(destination) } ?: Home
    }
}
