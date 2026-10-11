package com.securevox.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.securevox.app.presentation.SecureVoxNavHost
import com.securevox.app.presentation.settings.ThemeMode
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.presentation.theme.SecureVoxTheme
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kreativekoala.ratingkit.RatingKit
import kotlinx.coroutines.flow.map

class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        // Permission result handled
    }

    private var unlocked by mutableStateOf(false)
    private var stoppedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RatingKit.trackAppOpen(this)

        // Request microphone permission
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        // A recreated activity (rotation, process death) re-delivers the launch intent;
        // only act on it the first time or a shared file would be imported twice.
        if (savedInstanceState == null) IncomingActions.handle(intent)

        setContent {
            // Observe theme preference from DataStore
            val themeMode by dataStore.data
                .map { prefs ->
                    ThemeMode.fromString(prefs[stringPreferencesKey("theme_mode")])
                }
                .collectAsState(initial = ThemeMode.SYSTEM)

            // null until DataStore has answered, so nothing is shown that the lock might hide.
            val lockEnabled by dataStore.data
                .map { prefs -> prefs[AppLock.KEY_ENABLED] ?: false }
                .collectAsState(initial = null)

            LaunchedEffect(lockEnabled) {
                // Keeps recordings out of the recents screen and screenshots while the lock is on.
                if (lockEnabled == true) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            SecureVoxTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        lockEnabled == null -> Unit
                        lockEnabled == true && !unlocked -> {
                            LaunchedEffect(Unit) { promptUnlock() }
                            LockScreen(onUnlock = ::promptUnlock)
                        }
                        else -> SecureVoxNavHost()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingActions.handle(intent)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > AppLock.RELOCK_AFTER_MS) {
            unlocked = false
        }
    }

    private fun promptUnlock() {
        AppLock.authenticate(this) { unlocked = true }
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
    }
}
