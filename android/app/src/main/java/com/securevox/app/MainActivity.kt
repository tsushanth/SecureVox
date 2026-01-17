package com.securevox.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.securevox.app.presentation.SecureVoxNavHost
import com.securevox.app.presentation.settings.ThemeMode
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.presentation.theme.SecureVoxTheme
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request microphone permission
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            // Observe theme preference from DataStore
            val themeMode by dataStore.data
                .map { prefs ->
                    ThemeMode.fromString(prefs[stringPreferencesKey("theme_mode")])
                }
                .collectAsState(initial = ThemeMode.SYSTEM)

            SecureVoxTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SecureVoxNavHost()
                }
            }
        }
    }
}
