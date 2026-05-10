package com.desmond.ofd.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.desmond.ofd.ui.nav.DownloadsRoute
import com.desmond.ofd.ui.nav.HomeRoute
import com.desmond.ofd.ui.nav.InfoRoute
import com.desmond.ofd.ui.nav.ManualRoute
import com.desmond.ofd.ui.nav.TopDestination
import com.desmond.ofd.ui.screens.DownloadsScreen
import com.desmond.ofd.ui.screens.HomeScreen
import com.desmond.ofd.ui.screens.ManualScreen
import com.desmond.ofd.ui.screens.SettingsScreen

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = TopDestination.fromDestination(backStackEntry?.destination)

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { dest ->
                item(
                    selected = current == dest,
                    onClick = { navigateToDestination(navController, dest) },
                    icon = {
                        Icon(dest.icon, contentDescription = stringResource(dest.labelRes))
                    },
                    label = { Text(stringResource(dest.labelRes)) },
                )
            }
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = HomeRoute,
        ) {
            composable<HomeRoute> { HomeScreen() }
            composable<DownloadsRoute> { DownloadsScreen() }
            composable<ManualRoute> { ManualScreen() }
            composable<InfoRoute> { SettingsScreen() }
        }
    }
}

private fun navigateToDestination(navController: NavHostController, destination: TopDestination) {
    val route: Any = when (destination) {
        TopDestination.Home -> HomeRoute
        TopDestination.Downloads -> DownloadsRoute
        TopDestination.Manual -> ManualRoute
        TopDestination.Settings -> InfoRoute
    }
    navController.navigate(route) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

// Keep this here so future tests can reuse the same nav-options pattern.
@Suppress("unused")
private fun NavOptionsBuilder.standardTabBehavior(navController: NavHostController) {
    popUpTo(navController.graph.startDestinationId) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
