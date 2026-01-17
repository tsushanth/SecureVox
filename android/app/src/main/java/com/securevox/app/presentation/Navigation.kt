package com.securevox.app.presentation

import androidx.compose.runtime.*
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.securevox.app.SecureVoxApp
import com.securevox.app.presentation.detail.RecordingDetailScreen
import com.securevox.app.presentation.onboarding.SetupScreen
import com.securevox.app.presentation.recordings.RecordingsScreen
import com.securevox.app.presentation.settings.CustomDictionaryScreen
import com.securevox.app.presentation.settings.FAQScreen
import com.securevox.app.presentation.settings.SettingsScreen
import com.securevox.app.whisper.WhisperModel

sealed class Screen(val route: String) {
    object Setup : Screen("setup")
    object Recordings : Screen("recordings")
    object RecordingDetail : Screen("recording/{recordingId}") {
        fun createRoute(recordingId: String) = "recording/$recordingId"
    }
    object Settings : Screen("settings")
    object FAQ : Screen("faq")
    object CustomDictionary : Screen("custom_dictionary")
}

@Composable
fun SecureVoxNavHost() {
    val navController = rememberNavController()
    val modelManager = remember { SecureVoxApp.instance.modelManager }

    // Check if model is already downloaded
    val needsSetup = remember {
        !modelManager.isModelDownloaded(WhisperModel.TINY)
    }

    val startDestination = if (needsSetup) Screen.Setup.route else Screen.Recordings.route

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                modelManager = modelManager,
                onSetupComplete = {
                    navController.navigate(Screen.Recordings.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Recordings.route) {
            RecordingsScreen(
                onRecordingClick = { recordingId ->
                    navController.navigate(Screen.RecordingDetail.createRoute(recordingId))
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.RecordingDetail.route,
            arguments = listOf(
                navArgument("recordingId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val recordingId = backStackEntry.arguments?.getString("recordingId") ?: return@composable
            RecordingDetailScreen(
                recordingId = recordingId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFAQ = { navController.navigate(Screen.FAQ.route) },
                onNavigateToCustomDictionary = { navController.navigate(Screen.CustomDictionary.route) }
            )
        }

        composable(Screen.FAQ.route) {
            FAQScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.CustomDictionary.route) {
            CustomDictionaryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
