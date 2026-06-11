package com.example.ttspdfreader

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ttspdfreader.presentation.documents.DocumentsScreen
import com.example.ttspdfreader.presentation.home.HomeScreen
import com.example.ttspdfreader.presentation.reader.ReaderScreen
import com.example.ttspdfreader.presentation.settings.SettingsScreen
import com.example.ttspdfreader.presentation.tts.ModelDownloadScreen
import com.example.ttspdfreader.presentation.tts.VoiceSetupScreen

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Documents : Screen("documents", "Documents", Icons.Default.Description)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Documents,
    Screen.Settings
)

@Composable
fun MainNavigation(
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val currentRoute = currentDestination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToReader = { encodedUri ->
                        navController.navigate("reader/$encodedUri")
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
                    },
                    onNavigateToDocuments = {
                        navController.navigate(Screen.Documents.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
                )
            }
            
            composable(Screen.Documents.route) {
                DocumentsScreen(
                    onNavigateToReader = { encodedUri ->
                        navController.navigate("reader/$encodedUri")
                    },
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
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
                    },
                    onNavigateToDownload = {
                        navController.navigate("model_download")
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = {
                        navController.popBackStack()
                    },
                    onNavigateToDownload = {
                        navController.navigate("model_download")
                    },
                    onNavigateToVoiceSetup = {
                        navController.navigate("voice_setup")
                    },
                    modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
                )
            }

            composable("model_download") {
                ModelDownloadScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }

            composable("voice_setup") {
                VoiceSetupScreen(
                    onBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
