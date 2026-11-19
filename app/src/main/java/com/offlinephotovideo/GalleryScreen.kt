package com.offlinephotovideo

import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen() {
    val ctx = LocalContext.current
    val loader = remember {
        ImageLoader.Builder(ctx).components { add(VideoFrameDecoder.Factory()) }.crossfade(true).build()
    }

    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); if (Store.deleteExpired() > 0) tick++ }
    }
    // Foto/video kiriman tamu langsung muncul di galeri.
    val received = Sharing.received
    LaunchedEffect(received) { tick++ }
    val all = remember(tick) { Store.deleteExpired(); Store.list() }

    val filters = listOf("Semua", "Foto", "Video", "Foto terbaru", "Video terbaru")
    var filter by remember { mutableIntStateOf(0) }
    val shown = when (filter) {
        1 -> all.filter { !it.isVideo }
        2 -> all.filter { it.isVideo }
        3 -> all.filter { !it.isVideo }.take(10)
        4 -> all.filter { it.isVideo }.take(10)
        else -> all
    }

    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<MediaItem?>(null) }
    var deleteTarget by remember { mutableStateOf<List<MediaItem>?>(null) }
    var qrMode by remember { mutableStateOf<String?>(null) }

    fun toggle(n: String) {
        selected = if (n in selected) selected - n else selected + n
        if (selected.isEmpty()) selecting = false
    }

    fun startShare(items: List<MediaItem>) {
        Sharing.startShare(items.map { it.file })
            .onSuccess { qrMode = "share"; selecting = false; selected = emptySet() }
            .onFailure { Toast.makeText(ctx, it.message ?: "Gagal membagikan", Toast.LENGTH_LONG).show() }
    }

    fun startJoin() {
        Sharing.startJoin()
            .onSuccess { qrMode = "join" }
            .onFailure { Toast.makeText(ctx, it.message ?: "Gagal membuat undangan", Toast.LENGTH_LONG).show() }
    }

    val selectedItems = shown.filter { it.name in selected }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selecting) {
                Text("${selected.size} dipilih", Modifier.weight(1f))
                TextButton(onClick = { selected = shown.map { it.name }.toSet() }) { Text("Semua") }
                TextButton(onClick = { if (selectedItems.isNotEmpty()) deleteTarget = selectedItems }) { Text("Hapus") }
                TextButton(onClick = { if (selectedItems.isNotEmpty()) startShare(selectedItems) }) { Text("QR") }
                TextButton(onClick = { selecting = false; selected = emptySet() }) { Text("Batal") }
            } else {
                Text("Galeri", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (Sharing.joinUrl != null) qrMode = "join" else startJoin() }) { Text("UNDANG") }
                TextButton(onClick = { selecting = true }) { Text("PILIH") }
            }
        }

        if (Sharing.joinUrl != null) {
            Banner(
                "📸 Undangan foto aktif (${Sharing.received} masuk)",
                onQr = { qrMode = "join" },
                onStop = { Sharing.stopJoin(); if (qrMode == "join") qrMode = null }
            )
        }
        if (Sharing.shareUrl != null) {
            Banner(
                "📡 Sharing aktif (${Sharing.shareCount} file)",
                onQr = { qrMode = "share" },
                onStop = { Sharing.stopShare(); if (qrMode == "share") qrMode = null }
            )
        }

        LazyRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(filters) { i, label ->
                FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(label) })
            }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Belum ada foto/video") }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(110.dp),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(shown, key = { it.name }) { item ->
                    Cell(
                        item, loader, item.name in selected, selecting,
                        onClick = { if (selecting) toggle(item.name) else viewing = item },
                        onLong = { selecting = true; if (item.name !in selected) selected = selected + item.name }
                    )
                }
            }
        }
    }

    viewing?.let { item ->
        Viewer(item, loader, onClose = { viewing = null }, onDelete = { deleteTarget = listOf(item) })
    }

    deleteTarget?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus ${targets.size} file?") },
            text = { Text("File yang dihapus tidak dapat dikembalikan.") },
            confirmButton = {
                Button(onClick = {
                    targets.forEach { it.file.delete() }
                    deleteTarget = null; viewing = null; selecting = false; selected = emptySet(); tick++
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal") } }
        )
    }

    val mode = qrMode
    val url = if (mode == "join") Sharing.joinUrl else if (mode == "share") Sharing.shareUrl else null
    if (mode != null && url != null) {
        val bmp = remember(url) { Sharing.qrBitmap(url) }
        val isJoin = mode == "join"
        AlertDialog(
            onDismissRequest = { qrMode = null },
            title = { Text(if (isJoin) "Scan untuk kirim foto & video" else "Scan untuk mengunduh") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(bmp.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(260.dp))
                    Text(url, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        if (isJoin)
                            "Tamu cukup scan pakai kamera HP apa saja, lalu tekan AMBIL FOTO atau REKAM VIDEO. Tidak perlu instal apa pun. HP tamu harus di Wi-Fi/hotspot yang sama dan aplikasi ini tetap terbuka. Masuk: ${Sharing.received} file."
                        else
                            "${Sharing.shareCount} file. HP lain harus di Wi-Fi/hotspot yang sama. Biarkan aplikasi tetap terbuka.",
                        fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (isJoin) Sharing.stopJoin() else Sharing.stopShare()
                    qrMode = null
                }) { Text(if (isJoin) "Stop Undangan" else "Stop Sharing") }
            },
            dismissButton = { TextButton(onClick = { qrMode = null }) { Text("Tutup") } }
        )
    }
}

@Composable
private fun Banner(text: String, onQr: () -> Unit, onStop: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp)
        TextButton(onClick = onQr) { Text("QR") }
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Cell(
    item: MediaItem, loader: ImageLoader, isSelected: Boolean, selecting: Boolean,
    onClick: () -> Unit, onLong: () -> Unit
) {
    Box(Modifier.aspectRatio(1f).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        AsyncImage(
            model = item.file, imageLoader = loader, contentDescription = item.name,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
        )
        if (item.isVideo) {
            Text("▶", color = Color.White, fontSize = 28.sp, modifier = Modifier.align(Alignment.Center))
        }
        item.expiresAt?.let {
            Text(
                "⏳ " + Store.humanRemaining(it - System.currentTimeMillis()), color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(horizontal = 4.dp)
            )
        }
        if (selecting) {
            Text(if (isSelected) "✅" else "⬜", modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
    }
}

@Composable
private fun Viewer(item: MediaItem, loader: ImageLoader, onClose: () -> Unit, onDelete: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (item.isVideo) {
                        AndroidView(
                            factory = { c ->
                                VideoView(c).apply {
                                    setVideoPath(item.file.path)
                                    setMediaController(MediaController(c).also { it.setAnchorView(this) })
                                    setOnPreparedListener { start() }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = item.file, imageLoader = loader, contentDescription = item.name,
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Column(Modifier.padding(12.dp)) {
                    Text(item.name, color = Color.White, fontSize = 14.sp)
                    Text(
                        Store.dateStr(item.created) + " • " + Store.humanSize(item.size),
                        color = Color.LightGray, fontSize = 12.sp
                    )
                    item.expiresAt?.let {
                        Text("Berakhir dalam " + Store.humanRemaining(it - now), color = Color(0xFFFFB74D), fontSize = 12.sp)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onClose) { Text("Tutup") }
                        Button(onClick = onDelete) { Text("Hapus") }
                    }
                }
            }
        }
    }
}
