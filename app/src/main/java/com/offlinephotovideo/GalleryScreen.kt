package com.offlinephotovideo

import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    var viewIndex by remember { mutableStateOf<Int?>(null) }
    var cols by remember { mutableIntStateOf(3) }
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
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp).pointerInput(Unit) {
                    // Cubit dua jari: perbesar / perkecil ukuran thumbnail seperti galeri HP.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var acc = 1f
                        do {
                            val ev = awaitPointerEvent()
                            if (ev.changes.size > 1) {
                                acc *= ev.calculateZoom()
                                ev.changes.forEach { it.consume() }
                            }
                        } while (ev.changes.any { it.pressed })
                        if (acc > 1.25f) cols = (cols - 1).coerceAtLeast(2)
                        else if (acc < 0.8f) cols = (cols + 1).coerceAtMost(6)
                    }
                },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(shown, key = { it.name }) { item ->
                    Cell(
                        item, loader, item.name in selected, selecting,
                        onClick = { if (selecting) toggle(item.name) else viewIndex = shown.indexOf(item) },
                        onLong = { selecting = true; if (item.name !in selected) selected = selected + item.name }
                    )
                }
            }
        }
    }

    viewIndex?.let { idx ->
        Viewer(shown, idx, loader, onClose = { viewIndex = null }, onDelete = { deleteTarget = listOf(it) })
    }

    deleteTarget?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus ${targets.size} file?") },
            text = { Text("File yang dihapus tidak dapat dikembalikan.") },
            confirmButton = {
                Button(onClick = {
                    targets.forEach { it.file.delete() }
                    deleteTarget = null; viewIndex = null; selecting = false; selected = emptySet(); tick++
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(
    items: List<MediaItem>, startIndex: Int, loader: ImageLoader,
    onClose: () -> Unit, onDelete: (MediaItem) -> Unit
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.size - 1)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { zoomed = false }
    val current = items[pager.currentPage.coerceIn(0, items.size - 1)]

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()
                ) { page ->
                    val item = items[page]
                    val isCurrent = pager.currentPage == page
                    ZoomBox(onTap = { chrome = !chrome }, onZoomChange = { if (isCurrent) zoomed = it }) {
                        if (item.isVideo) {
                            if (isCurrent) VideoPlayer(item) else VideoThumb(item, loader)
                        } else {
                            AsyncImage(
                                model = item.file, imageLoader = loader, contentDescription = item.name,
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
                if (chrome) {
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x99000000))
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onClose) { Text("✕", color = Color.White, fontSize = 20.sp) }
                        Text("${pager.currentPage + 1} / ${items.size}", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showInfo = true }) { Text("ⓘ Detail", color = Color.White) }
                        TextButton(onClick = { onDelete(current) }) { Text("🗑 Hapus", color = Color.White) }
                    }
                }
            }
        }
    }
    if (showInfo) InfoDialog(current) { showInfo = false }
}

/** Kotak yang bisa diperbesar: cubit dua jari, ketuk dua kali, dan geser saat sudah diperbesar. */
@Composable
private fun ZoomBox(onTap: () -> Unit, onZoomChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val multi = ev.changes.size > 1
                        if (multi || scale > 1.01f) {
                            val z = ev.calculateZoom()
                            val pan = ev.calculatePan()
                            scale = (scale * z).coerceIn(1f, 5f)
                            val mx = size.width * (scale - 1f) / 2f
                            val my = size.height * (scale - 1f) / 2f
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-mx, mx),
                                (offset.y + pan.y).coerceIn(-my, my)
                            )
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (scale <= 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
    ) { content() }
}

@Composable
private fun VideoPlayer(item: MediaItem) {
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
}

@Composable
private fun VideoThumb(item: MediaItem, loader: ImageLoader) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AsyncImage(
            model = item.file, imageLoader = loader, contentDescription = null,
            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
        )
        Text("▶", color = Color.White, fontSize = 48.sp)
    }
}

@Composable
private fun InfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detail") },
        text = {
            Column {
                Text("Nama: " + item.name)
                Text("Jenis: " + (if (item.isVideo) "Video" else "Foto"))
                Text("Tanggal: " + Store.dateStr(item.created))
                Text("Ukuran: " + Store.humanSize(item.size))
                item.expiresAt?.let { Text("Berakhir dalam: " + Store.humanRemaining(it - now)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}
