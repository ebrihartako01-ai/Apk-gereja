package com.offlinephotovideo

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    content()
}

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var ret by remember { mutableLongStateOf(Store.retentionMinutes) }
    var custom by remember { mutableStateOf("") }
    var pq by remember { mutableIntStateOf(Store.photoQuality) }
    var vq by remember { mutableStateOf(Store.videoQuality) }
    var tick by remember { mutableIntStateOf(0) }
    val items = remember(tick) { Store.list() }

    val presets = listOf(
        "Tidak pernah dihapus" to 0L, "1 jam" to 60L, "6 jam" to 360L, "12 jam" to 720L,
        "24 jam" to 1440L, "3 hari" to 4320L, "7 hari" to 10080L, "30 hari" to 43200L
    )
    val isCustom = presets.none { it.second == ret }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Pengaturan", style = MaterialTheme.typography.titleLarge)

        Section("Auto Delete / Durasi Penyimpanan") {
            presets.forEach { (label, minutes) ->
                Choice(label, ret == minutes) { ret = minutes; Store.retentionMinutes = minutes }
            }
            Choice(if (isCustom) "Custom: ${"%.1f".format(ret / 60.0)} jam" else "Custom", isCustom) { }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it.filter { c -> c.isDigit() } },
                    label = { Text("Jam") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val h = custom.toLongOrNull()
                    if (h != null && h > 0) { ret = h * 60; Store.retentionMinutes = ret; custom = "" }
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Simpan") }
            }
            Text(
                "Foto/video dihapus otomatis setelah berumur sesuai pilihan. Pengecekan dilakukan saat aplikasi dibuka dan setiap 30 detik selama aplikasi aktif.",
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }

        Section("Kualitas Foto") {
            Choice("Maksimal", pq == 0) { pq = 0; Store.photoQuality = 0 }
            Choice("Cepat", pq == 1) { pq = 1; Store.photoQuality = 1 }
        }

        Section("Kualitas Video") {
            listOf("SD" to "SD (480p)", "HD" to "HD (720p)", "FHD" to "Full HD (1080p)", "UHD" to "4K (jika didukung)").forEach { (k, l) ->
                Choice(l, vq == k) { vq = k; Store.videoQuality = k }
            }
            Text("Jika HP tidak mendukung, otomatis memakai kualitas terdekat yang tersedia.", fontSize = 12.sp)
        }

        Section("Folder Penyimpanan") {
            Text(Store.root.path + "/Photos dan /Videos", fontSize = 12.sp)
            Text("Folder privat aplikasi, tidak bisa dibuka aplikasi lain.", fontSize = 12.sp)
        }

        Section("Informasi Storage") {
            Text("Digunakan aplikasi: ${Store.humanSize(items.sumOf { it.size })}")
            Text("Tersedia di HP: ${Store.humanSize(Store.freeBytes())}")
            Text("${items.count { !it.isVideo }} foto, ${items.count { it.isVideo }} video")
            Button(onClick = {
                val n = Store.deleteExpired(); tick++
                Toast.makeText(ctx, "$n file expired dihapus", Toast.LENGTH_SHORT).show()
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Hapus File Expired") }
        }

        Section("Tentang Aplikasi") {
            Text("Offline Photo & Video v1.0\nSemua data tersimpan lokal. Berbagi file hanya melalui Wi-Fi/hotspot lokal, tanpa cloud.", fontSize = 13.sp)
        }
    }
}
