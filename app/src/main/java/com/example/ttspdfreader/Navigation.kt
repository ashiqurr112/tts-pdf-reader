package com.example.ttspdfreader

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ttspdfreader.presentation.home.HomeScreen
import com.example.ttspdfreader.presentation.reader.ReaderScreen
import com.example.ttspdfreader.presentation.settings.SettingsScreen

@Composable
fun MainNavigation(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = "home",
        modifier = modifier
    ) {
        composable("home") {
            HomeScreen(
                onNavigateToReader = { encodedUri ->
                    navController.navigate("reader/$encodedUri")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                }
            )
        }

        composable(
            route = "reader/{uri}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType }
            )
        ) {
            ReaderScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
