package com.desmond.ofd.ui

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.desmond.ofd.ui.nav.TopDestination
import com.desmond.ofd.ui.screens.DownloadsScreen
import com.desmond.ofd.ui.screens.HomeScreen
import com.desmond.ofd.ui.screens.SettingsScreen

@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = TopDestination.fromRoute(backStackEntry?.destination?.route)

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopDestination.entries.forEach { dest ->
                item(
                    selected = current == dest,
                    onClick = { navigateToDestination(navController, dest) },
                    icon = { Icon(dest.icon, contentDescription = dest.label) },
                    label = { Text(dest.label) }
                )
            }
        }
    ) {
        NavHost(
            navController = navController,
            startDestination = TopDestination.Home.route
        ) {
            composable(TopDestination.Home.route) { HomeScreen() }
            composable(TopDestination.Downloads.route) { DownloadsScreen() }
            composable(TopDestination.Settings.route) { SettingsScreen() }
        }
    }
}

private fun navigateToDestination(
    navController: NavHostController,
    destination: TopDestination
) {
    navController.navigate(destination.route) {
        popUpTo(navController.graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
