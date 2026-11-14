package com.mangalens.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.mangalens.ai.capture.ScreenCaptureService
import com.mangalens.ai.data.EngineState
import com.mangalens.ai.data.Provider
import com.mangalens.ai.data.TranslationEntity
import com.mangalens.ai.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private class Rationale(val title: String, val body: String, val confirm: String, val action: () -> Unit)

    private var rationale by mutableStateOf<Rationale?>(null)
    private var waitingOverlay = false

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_START)
                    .putExtra(ScreenCaptureService.EXTRA_CODE, r.resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_DATA, data)
            )
            Toast.makeText(this, "Translation started. Open your manga reader.", Toast.LENGTH_LONG).show()
        } else Toast.makeText(this, "Screen capture permission was not granted.", Toast.LENGTH_SHORT).show()
    }
    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { askProjection() }

    private fun begin() {
        if (!Settings.canDrawOverlays(this)) {
            rationale = Rationale(
                "Display over other apps",
                "MangaLens draws the Arabic translation directly on top of your manga reader. The overlay never intercepts your touches, so you can keep swiping, scrolling and zooming.",
                "Open settings"
            ) {
                waitingOverlay = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            rationale = Rationale(
                "Notifications",
                "Android requires a visible notification while MangaLens watches your screen. It also gives you Pause and Stop buttons.",
                "Allow"
            ) { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
            return
        }
        askProjection()
    }

    private fun askProjection() {
        rationale = Rationale(
            "Screen capture",
            "MangaLens must read your screen to find the text on each page. Frames stay in memory only and are never saved. If you use a cloud translator, only the extracted text is sent, never images.\n\nIn the next dialog choose \"Entire screen\" (not a single app).",
            "Continue"
        ) { projectionLauncher.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    }

    private fun serviceAction(a: String) = startService(Intent(this, ScreenCaptureService::class.java).setAction(a))

    override fun onResume() {
        super.onResume()
        if (waitingOverlay && Settings.canDrawOverlays(this)) { waitingOverlay = false; begin() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MangaLensApp
        setContent {
            MangaLensTheme {
                var tab by remember { mutableIntStateOf(0) }
                val st by EngineState.state.collectAsState()
                val s by app.settings.settings.collectAsState()
                val keyVer by app.settings.keyVersion.collectAsState()
                val history by app.db.dao().history().collectAsState(initial = emptyList<TranslationEntity>())
                val scope = rememberCoroutineScope()
                val needsKey = remember(s.provider, keyVer) { s.provider != Provider.ON_DEVICE && app.settings.apiKey(s.provider).isBlank() }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.Settings, null) }, label = { Text("Settings") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.DateRange, null) }, label = { Text("History") })
                        }
                    }
                ) { pad ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                        when (tab) {
                            0 -> Dashboard(st, s, needsKey, onStart = { begin() },
                                onStop = { serviceAction(ScreenCaptureService.ACTION_STOP) },
                                onTogglePause = { serviceAction(ScreenCaptureService.ACTION_TOGGLE) },
                                onOpenSettings = { tab = 1 })
                            1 -> SettingsScreen(app.settings, s)
                            else -> HistoryScreen(history) { scope.launch { app.db.dao().clear() } }
                        }
                    }
                }

                rationale?.let { r ->
                    AlertDialog(
                        onDismissRequest = { rationale = null },
                        title = { Text(r.title) }, text = { Text(r.body) },
                        confirmButton = { TextButton(onClick = { rationale = null; r.action() }) { Text(r.confirm) } },
                        dismissButton = { TextButton(onClick = { rationale = null }) { Text("Cancel") } }
                    )
                }
            }
        }
    }
}
