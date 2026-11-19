package com.offlinephotovideo

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private fun qualityOf(s: String) = when (s) {
    "UHD" -> Quality.UHD
    "HD" -> Quality.HD
    "SD" -> Quality.SD
    else -> Quality.FHD
}

@Composable
fun CameraScreen() {
    val ctx = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var hasCam by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    var hasMic by remember { mutableStateOf(granted(Manifest.permission.RECORD_AUDIO)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasCam = granted(Manifest.permission.CAMERA)
        hasMic = granted(Manifest.permission.RECORD_AUDIO)
    }
    val ask = { launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }
    LaunchedEffect(Unit) { if (!hasCam) ask() }

    if (!hasCam) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Izin kamera diperlukan untuk mengambil foto dan video.")
            Button(onClick = { ask() }, modifier = Modifier.padding(top = 16.dp)) { Text("Beri Izin") }
        }
    } else {
        CameraContent(hasMic)
    }
}

@Composable
private fun CamChip(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 14.sp,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0x88000000))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun CameraContent(hasMic: Boolean) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { ContextCompat.getMainExecutor(ctx) }
    val previewView = remember { PreviewView(ctx) }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var videoCapture by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var recording by remember { mutableStateOf<Recording?>(null) }

    var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var videoMode by remember { mutableStateOf(false) }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_AUTO) }
    var torchOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var timerSec by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var recSeconds by remember { mutableIntStateOf(0) }
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var expIndex by remember { mutableIntStateOf(0) }
    var expRange by remember { mutableStateOf<IntRange?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusLocked by remember { mutableStateOf(false) }
    var hintShown by remember { mutableStateOf(false) }

    val recState = rememberUpdatedState(recording)

    DisposableEffect(Unit) {
        val f = ProcessCameraProvider.getInstance(ctx)
        f.addListener({ provider = f.get() }, executor)
        onDispose { }
    }
    DisposableEffect(provider) {
        onDispose {
            recState.value?.stop()
            provider?.unbindAll()
        }
    }

    // Pasang kamera setiap kali kamera depan/belakang atau mode foto/video berubah.
    LaunchedEffect(provider, lens, videoMode) {
        val p = provider ?: return@LaunchedEffect
        try {
            val selector = CameraSelector.Builder().requireLensFacing(lens).build()
            if (!p.hasCamera(selector)) {
                message = "Kamera ini tidak tersedia di perangkat."
                return@LaunchedEffect
            }
            p.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val cam = if (!videoMode) {
                val ic = ImageCapture.Builder()
                    .setCaptureMode(
                        if (Store.photoQuality == 0) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                        else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .setFlashMode(flashMode).build()
                imageCapture = ic; videoCapture = null
                p.bindToLifecycle(owner, selector, preview, ic)
            } else {
                val rec = Recorder.Builder().setQualitySelector(
                    QualitySelector.from(qualityOf(Store.videoQuality), FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                ).build()
                val vc = VideoCapture.withOutput(rec)
                videoCapture = vc; imageCapture = null
                p.bindToLifecycle(owner, selector, preview, vc)
            }
            camera = cam
            hasFlash = cam.cameraInfo.hasFlashUnit()
            zoomRatio = 1f; expIndex = 0; torchOn = false
            val es = cam.cameraInfo.exposureState
            expRange = if (es.isExposureCompensationSupported)
                es.exposureCompensationRange.let { it.lower..it.upper } else null
            if (!hintShown) {
                hintShown = true
                message = "Ketuk layar untuk fokus. Tekan lama untuk mengunci fokus."
            }
        } catch (e: Exception) {
            message = "Kamera tidak dapat dibuka. Mungkin sedang dipakai aplikasi lain."
        }
    }

    LaunchedEffect(message) { if (message != null) { delay(3500); message = null } }
    LaunchedEffect(focusPoint) {
        if (focusPoint != null && !focusLocked) { delay(1500); focusPoint = null }
    }
    LaunchedEffect(recording) {
        recSeconds = 0
        while (recording != null) { delay(1000); recSeconds++ }
    }

    fun takePhoto() {
        val ic = imageCapture ?: return
        if (Store.isStorageLow()) { message = "Penyimpanan hampir penuh."; return }
        val file = Store.newFile(false)
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(), executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    Toast.makeText(ctx, "Foto tersimpan di galeri", Toast.LENGTH_SHORT).show()
                }
                override fun onError(e: ImageCaptureException) {
                    file.delete(); message = "Foto gagal disimpan."
                }
            })
    }

    fun startRecording() {
        val vc = videoCapture ?: return
        if (Store.isStorageLow()) { message = "Penyimpanan hampir penuh."; return }
        val file = Store.newFile(true)
        var pending = vc.output.prepareRecording(ctx, FileOutputOptions.Builder(file).build())
        if (hasMic) pending = pending.withAudioEnabled()
        recording = pending.start(executor) { ev ->
            if (ev is VideoRecordEvent.Finalize) {
                recording = null
                if (ev.hasError()) { file.delete(); message = "Video gagal disimpan." }
                else Toast.makeText(ctx, "Video tersimpan di galeri", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun focusAt(off: Offset, lock: Boolean) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(off.x, off.y)
        val b = FocusMeteringAction.Builder(pt)
        if (lock) b.disableAutoCancel()
        focusLocked = lock
        focusPoint = off
        try {
            cam.cameraControl.startFocusAndMetering(b.build())
        } catch (e: Exception) {
            message = "Fokus tidak didukung kamera ini."
        }
    }

    fun withTimer(action: () -> Unit) {
        scope.launch {
            var t = timerSec
            while (t > 0) { countdown = t; delay(1000); t-- }
            countdown = 0
            action()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Lapisan sentuhan: tap untuk fokus, cubit untuk zoom.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(camera) {
                    detectTapGestures(
                        onTap = { off -> focusAt(off, false) },
                        onLongPress = { off -> focusAt(off, true) }
                    )
                }
                .pointerInput(camera) {
                    detectTransformGestures { _, _, z, _ ->
                        val st = camera?.cameraInfo?.zoomState?.value ?: return@detectTransformGestures
                        val nr = (st.zoomRatio * z).coerceIn(st.minZoomRatio, st.maxZoomRatio)
                        camera?.cameraControl?.setZoomRatio(nr)
                        zoomRatio = nr
                    }
                }
        )

        focusPoint?.let { p ->
            Box(
                Modifier
                    .offset { IntOffset((p.x - 40.dp.toPx()).roundToInt(), (p.y - 40.dp.toPx()).roundToInt()) }
                    .size(80.dp)
                    .border(2.dp, if (focusLocked) Color(0xFFFFC107) else Color.White, RoundedCornerShape(12.dp))
            )
            if (focusLocked) {
                Text(
                    "Fokus terkunci", color = Color(0xFFFFC107), fontSize = 12.sp,
                    modifier = Modifier.offset { IntOffset((p.x - 40.dp.toPx()).roundToInt(), (p.y + 46.dp.toPx()).roundToInt()) }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (hasFlash) {
                if (videoMode) {
                    CamChip(if (torchOn) "🔦 On" else "🔦 Off") {
                        torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn)
                    }
                } else {
                    val label = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> "⚡ On"
                        ImageCapture.FLASH_MODE_OFF -> "⚡ Off"
                        else -> "⚡ Auto"
                    }
                    CamChip(label) {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
                            else -> ImageCapture.FLASH_MODE_AUTO
                        }
                        imageCapture?.flashMode = flashMode
                    }
                }
            } else Box(Modifier.size(1.dp))
            CamChip(if (timerSec == 0) "⏱ Off" else "⏱ ${timerSec}s") {
                timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }
            }
            if (zoomRatio > 1.01f) CamChip("%.1fx".format(zoomRatio)) { }
            if (recording == null) CamChip("🔄 Putar") {
                lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
                else CameraSelector.LENS_FACING_BACK
            }
        }

        if (countdown > 0) {
            Text("$countdown", color = Color.White, fontSize = 96.sp, modifier = Modifier.align(Alignment.Center))
        }
        if (recording != null) {
            Text(
                "● REC %02d:%02d".format(recSeconds / 60, recSeconds % 60), color = Color.Red, fontSize = 18.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
            )
        }
        message?.let {
            Text(
                it, color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(12.dp)
            )
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            expRange?.let { r ->
                Row(Modifier.padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("☀", color = Color.White)
                    Slider(
                        value = expIndex.toFloat(),
                        onValueChange = {
                            expIndex = it.roundToInt()
                            camera?.cameraControl?.setExposureCompensationIndex(expIndex)
                        },
                        valueRange = r.first.toFloat()..r.last.toFloat()
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(8.dp)) {
                CamChip(if (!videoMode) "● FOTO" else "FOTO") { if (recording == null) videoMode = false }
                CamChip(if (videoMode) "● VIDEO" else "VIDEO") { if (recording == null) videoMode = true }
            }
            Box(
                Modifier.size(76.dp).border(4.dp, Color.White, CircleShape).padding(8.dp)
                    .clip(CircleShape).background(if (recording != null) Color.Red else Color.White)
                    .clickable {
                        if (countdown > 0) return@clickable
                        if (videoMode) {
                            val r = recording
                            if (r != null) r.stop() else withTimer { startRecording() }
                        } else withTimer { takePhoto() }
                    }
            )
        }
    }
}
