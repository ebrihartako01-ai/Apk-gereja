package com.offlinephotovideo

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Store.deleteExpired()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                // Layar tetap menyala selama sharing/undangan aktif supaya server tidak mati.
                val sharingActive = Sharing.shareUrl != null || Sharing.joinUrl != null
                DisposableEffect(sharingActive) {
                    if (sharingActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                Scaffold(bottomBar = {
                    NavigationBar {
                        listOf("📷" to "KAMERA", "🖼" to "GALERI", "⚙" to "PENGATURAN").forEachIndexed { i, (icon, label) ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Text(icon, fontSize = 20.sp) },
                                label = { Text(label, fontSize = 11.sp) }
                            )
                        }
                    }
                }) { pad ->
                    Box(Modifier.padding(pad).fillMaxSize()) {
                        when (tab) {
                            0 -> CameraScreen()
                            1 -> GalleryScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Store.deleteExpired() // cek tenggat setiap aplikasi dibuka kembali
    }

    override fun onDestroy() {
        if (isFinishing) Sharing.stopAll()
        super.onDestroy()
    }
}
