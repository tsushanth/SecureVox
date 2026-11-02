package com.securevox.app.presentation

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
import com.securevox.app.whisper.SetupGate

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

private const val KEY_SETUP_SKIPPED = "setup_skipped"

@Composable
fun SecureVoxNavHost() {
    val navController = rememberNavController()
    val modelManager = remember { SecureVoxApp.instance.modelManager }

    // First-run setup blocks the app only until a model is installed or the user chooses to continue
    // without one; after that, missing-model state is shown as a banner on the recordings screen.
    val prefs = LocalContext.current.getSharedPreferences("securevox_prefs", Context.MODE_PRIVATE)
    val needsSetup = remember {
        SetupGate.needsSetup(
            anyModelInstalled = modelManager.isAnyModelInstalled(),
            skipped = prefs.getBoolean(KEY_SETUP_SKIPPED, false)
        )
    }

    val startDestination = if (needsSetup) Screen.Setup.route else Screen.Recordings.route

    // Setup can be the first screen or opened on top of the recordings list (from the banner).
    fun leaveSetup() {
        if (!navController.popBackStack(Screen.Recordings.route, false)) {
            navController.navigate(Screen.Recordings.route) {
                popUpTo(Screen.Setup.route) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Screen.Setup.route) {
            SetupScreen(
                modelManager = modelManager,
                onSetupComplete = { leaveSetup() },
                onSkip = {
                    prefs.edit().putBoolean(KEY_SETUP_SKIPPED, true).apply()
                    leaveSetup()
                }
            )
        }

        composable(Screen.Recordings.route) {
            RecordingsScreen(
                onOpenSetup = { navController.navigate(Screen.Setup.route) { launchSingleTop = true } },
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
