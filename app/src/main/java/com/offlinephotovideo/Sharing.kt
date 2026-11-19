package com.offlinephotovideo

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Satu server lokal, dua fungsi (masing-masing punya token acak sendiri):
 *  - SHARE : tamu mengunduh file yang dipilih.
 *  - JOIN  : tamu memotret/merekam dengan HP mereka sendiri dan hasilnya dikirim ke galeri aplikasi ini.
 */
object Sharing {
    private var server: ShareServer? = null
    private var ip: String? = null
    private var port: Int = 0

    @Volatile internal var shareToken: String? = null
    @Volatile internal var joinToken: String? = null
    @Volatile internal var files: List<File> = emptyList()

    var shareUrl by mutableStateOf<String?>(null)
        private set
    var joinUrl by mutableStateOf<String?>(null)
        private set
    var shareCount by mutableIntStateOf(0)
        private set
    var received by mutableIntStateOf(0)
        private set

    private fun newToken(): String {
        val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun ensureServer(): Result<Unit> {
        if (server != null) return Result.success(Unit)
        val addr = localIp() ?: return Result.failure(
            IllegalStateException("Tidak ada jaringan lokal. Sambungkan HP ke Wi-Fi atau nyalakan hotspot.")
        )
        for (p in 8080..8090) {
            try {
                val s = ShareServer(p)
                s.start(60_000, false)
                server = s
                ip = addr
                port = p
                return Result.success(Unit)
            } catch (e: IOException) {
                // port dipakai, coba berikutnya
            }
        }
        return Result.failure(IllegalStateException("Server lokal gagal dijalankan."))
    }

    fun startShare(f: List<File>): Result<String> {
        val r = ensureServer()
        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        files = f.toList()
        shareCount = f.size
        val t = newToken()
        shareToken = t
        val u = "http://" + ip + ":" + port + "/share/" + t
        shareUrl = u
        return Result.success(u)
    }

    fun stopShare() {
        shareToken = null
        files = emptyList()
        shareUrl = null
        shareCount = 0
        stopServerIfIdle()
    }

    fun startJoin(): Result<String> {
        joinUrl?.let { return Result.success(it) }
        val r = ensureServer()
        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        received = 0
        val t = newToken()
        joinToken = t
        val u = "http://" + ip + ":" + port + "/join/" + t
        joinUrl = u
        return Result.success(u)
    }

    fun stopJoin() {
        joinToken = null
        joinUrl = null
        stopServerIfIdle()
    }

    fun stopAll() {
        stopShare()
        stopJoin()
    }

    private fun stopServerIfIdle() {
        if (shareToken == null && joinToken == null) {
            server?.stop()
            server = null
        }
    }

    internal fun onReceived() {
        synchronized(this) { received += 1 }
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) { null }

    fun qrBitmap(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) {
            bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        }
        return bmp
    }
}

private fun mimeOf(ext: String): String = when (ext.lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "webm" -> "video/webm"
    "3gp" -> "video/3gpp"
    "mkv" -> "video/x-matroska"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "gif" -> "image/gif"
    else -> "image/jpeg"
}

private fun extFromMime(m: String): String = when {
    m.startsWith("image/jpeg") -> "jpg"
    m.startsWith("image/png") -> "png"
    m.startsWith("image/webp") -> "webp"
    m.startsWith("image/heic") -> "heic"
    m.startsWith("image/heif") -> "heif"
    m.startsWith("video/mp4") -> "mp4"
    m.startsWith("video/quicktime") -> "mov"
    m.startsWith("video/webm") -> "webm"
    m.startsWith("video/3gpp") -> "3gp"
    else -> ""
}

private class ShareServer(port: Int) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        text(Status.INTERNAL_ERROR, "Terjadi kesalahan")
    }

    private fun text(status: Status, msg: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    private fun notFound() = text(Status.NOT_FOUND, "Tidak ditemukan")

    private fun route(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size < 2) return notFound()
        val tok = parts[1]
        when (parts[0]) {
            "share" -> {
                val st = Sharing.shareToken
                if (st == null || tok != st) return notFound()
                val files = Sharing.files
                if (parts.size == 2) return page(st, files)
                if (parts.size == 3 && parts[2] == "all") return zip(files)
                if (parts.size == 4 && parts[2] == "file") {
                    val name = URLDecoder.decode(parts[3], "UTF-8")
                    // Hanya cocok dengan nama file yang dipilih -> aman dari directory traversal.
                    val f = files.firstOrNull { it.name == name && it.exists() } ?: return notFound()
                    return serveFile(f, session, session.parms["dl"] == "1")
                }
                return notFound()
            }
            "join" -> {
                val jt = Sharing.joinToken
                if (jt == null || tok != jt) return notFound()
                if (parts.size == 2) return joinPage(jt)
                if (parts.size == 3 && parts[2] == "upload") return upload(session)
                return notFound()
            }
        }
        return notFound()
    }

    // ---------------- Tamu mengirim foto/video ----------------

    private fun upload(session: IHTTPSession): Response {
        if (session.method != Method.PUT) return text(Status.BAD_REQUEST, "Metode tidak didukung")
        val len = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (len <= 0L) return text(Status.BAD_REQUEST, "Ukuran file tidak diketahui")
        if (len > 2L * 1024 * 1024 * 1024) {
            return text(Status.BAD_REQUEST, "File terlalu besar (maks 2 GB)").apply { addHeader("Connection", "close") }
        }
        if (Store.freeBytes() < len + 100L * 1024 * 1024) {
            return text(Status.INTERNAL_ERROR, "Penyimpanan HP penuh").apply { addHeader("Connection", "close") }
        }
        val rawName = session.parms["name"] ?: ""
        var ext = rawName.substringAfterLast('.', "").lowercase()
        if (ext !in Store.IMAGE_EXT && ext !in Store.VIDEO_EXT) ext = extFromMime(session.headers["content-type"] ?: "")
        if (ext.isEmpty()) return text(Status.BAD_REQUEST, "Hanya foto dan video yang diterima")

        // Baca body langsung dari socket ke file dengan buffer besar (jauh lebih cepat dari parseBody).
        val dest = Store.newGuestFile(ext)
        val part = File(dest.parentFile, dest.name + ".part")
        val input = session.inputStream
        val buf = ByteArray(64 * 1024)
        var remaining = len
        var ok = false
        try {
            FileOutputStream(part).use { out ->
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
            if (remaining == 0L && part.renameTo(dest)) ok = true
        } finally {
            if (!ok) part.delete()
        }
        if (!ok) {
            return text(Status.INTERNAL_ERROR, "Upload terputus, coba lagi").apply { addHeader("Connection", "close") }
        }
        Sharing.onReceived()
        return newFixedLengthResponse(Status.OK, "application/json", "{\"ok\":true}")
    }

    private fun joinPage(token: String): Response {
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Kirim Foto &amp; Video</title><style>body{font-family:sans-serif;margin:0;padding:16px;background:#111;color:#eee;text-align:center}h1{font-size:20px}.b{display:block;box-sizing:border-box;width:100%;margin:12px 0;padding:18px;border-radius:12px;background:#1e88e5;color:#fff;font-size:18px;font-weight:bold}.b.g{background:#2e7d32}.b.o{background:#555}#l{text-align:left;font-size:14px}.i{margin:6px 0;padding:8px;background:#1e1e1e;border-radius:8px;word-break:break-all}.p{height:6px;background:#333;border-radius:3px;margin-top:4px}.p div{height:6px;background:#1e88e5;border-radius:3px;width:0}small{color:#aaa}</style></head><body><h1>FOTO &amp; VIDEO ACARA</h1><p>Ambil foto atau video dengan kamera HP kamu. Hasilnya dikirim dengan kualitas asli kamera HP kamu ke galeri acara.</p>
<label class="b">&#128247; AMBIL FOTO<input type="file" accept="image/*" capture="environment" hidden onchange="go(this)"></label>
<label class="b g">&#127909; REKAM VIDEO<input type="file" accept="video/*" capture="environment" hidden onchange="go(this)"></label>
<label class="b o">&#128444; PILIH DARI GALERI HP<input type="file" accept="image/*,video/*" multiple hidden onchange="go(this)"></label>
<small>Ukuran file asli besar, jadi pengiriman butuh waktu. Jangan tutup halaman atau kunci layar sampai selesai.</small>
<p id="c"></p><div id="l"></div>
<script>
var done=0;
function go(inp){var fs=[].slice.call(inp.files);inp.value='';var q=function(){var f=fs.shift();if(!f)return;up(f,q)};q()}
function up(f,next){
 var d=document.createElement('div');d.className='i';d.textContent=f.name+' ('+(f.size/1048576).toFixed(1)+' MB)';
 var p=document.createElement('div');p.className='p';var bar=document.createElement('div');p.appendChild(bar);d.appendChild(p);
 var l=document.getElementById('l');l.insertBefore(d,l.firstChild);
 var x=new XMLHttpRequest();x.open('PUT','/join/@@T@@/upload?name='+encodeURIComponent(f.name));
 x.upload.onprogress=function(e){if(e.lengthComputable)bar.style.width=(100*e.loaded/e.total)+'%'};
 x.onload=function(){if(x.status<300){bar.style.background='#4caf50';bar.style.width='100%';done++;document.getElementById('c').textContent=done+' file terkirim'}else{d.appendChild(document.createTextNode(' - GAGAL: '+x.responseText))}next()};
 x.onerror=function(){d.appendChild(document.createTextNode(' - GAGAL: koneksi putus'));next()};
 x.send(f)}
</script></body></html>""".replace("@@T@@", token)
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", html)
            .apply { addHeader("Cache-Control", "no-store") }
    }

    // ---------------- Tamu mengunduh ----------------

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(token: String, files: List<File>): Response {
        val live = files.filter { it.exists() }
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Galeri Foto &amp; Video</title><style>body{font-family:sans-serif;margin:0;padding:12px;background:#111;color:#eee}h1{font-size:18px}.c{background:#1e1e1e;border-radius:10px;padding:8px;margin:12px 0}img,video{width:100%;max-height:70vh;object-fit:contain;border-radius:6px;background:#000}a.b{display:block;text-align:center;background:#1e88e5;color:#fff;padding:12px;border-radius:8px;margin-top:8px;text-decoration:none;font-weight:bold}small{color:#aaa}</style></head><body><h1>GALERI FOTO &amp; VIDEO</h1>""")
        if (live.isEmpty()) {
            sb.append("<p>File sudah tidak tersedia.</p>")
        } else {
            sb.append("<a class=\"b\" href=\"/share/" + token + "/all\">DOWNLOAD SEMUA (ZIP)</a>")
            live.forEach { f ->
                val src = "/share/" + token + "/file/" + URLEncoder.encode(f.name, "UTF-8")
                val isVideo = f.extension.lowercase() in Store.VIDEO_EXT
                val media = if (isVideo) "<video controls preload=\"metadata\" src=\"" + src + "\"></video>"
                else "<img loading=\"lazy\" src=\"" + src + "\">"
                sb.append("<div class=\"c\">" + media + "<small>" + esc(f.name) + " &bull; " + Store.humanSize(f.length()) +
                    "</small><a class=\"b\" href=\"" + src + "?dl=1\">DOWNLOAD</a></div>")
            }
        }
        sb.append("</body></html>")
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun serveFile(f: File, session: IHTTPSession, download: Boolean): Response {
        val mime = mimeOf(f.extension)
        val len = f.length()
        var start = 0L
        var end = len - 1
        var partial = false
        val range = session.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val r = range.removePrefix("bytes=").split("-")
            start = r[0].toLongOrNull() ?: 0L
            end = r.getOrNull(1)?.toLongOrNull() ?: (len - 1)
            if (end >= len) end = len - 1
            if (start > end || start >= len) {
                return newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "")
                    .apply { addHeader("Content-Range", "bytes */$len") }
            }
            partial = true
        }
        val fis = FileInputStream(f)
        fis.skip(start)
        val resp = newFixedLengthResponse(if (partial) Status.PARTIAL_CONTENT else Status.OK, mime, fis, end - start + 1)
        resp.addHeader("Accept-Ranges", "bytes")
        resp.addHeader("Cache-Control", "no-store")
        if (partial) resp.addHeader("Content-Range", "bytes $start-$end/$len")
        if (download) resp.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        return resp
    }

    private fun zip(files: List<File>): Response {
        val live = files.filter { it.exists() }
        val pin = PipedInputStream(65536)
        val pout = PipedOutputStream(pin)
        thread {
            try {
                ZipOutputStream(pout).use { z ->
                    live.forEach { f ->
                        z.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(z) }
                        z.closeEntry()
                    }
                }
            } catch (e: Exception) { /* klien memutus koneksi */ }
        }
        return newChunkedResponse(Status.OK, "application/zip", pin)
            .apply { addHeader("Content-Disposition", "attachment; filename=\"galeri.zip\"") }
    }
}
