package com.offlinephotovideo

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import fi.iki.elonen.NanoHTTPD.newChunkedResponse
import fi.iki.elonen.NanoHTTPD.newFixedLengthResponse
import java.io.File
import java.io.FileInputStream
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

/** Server lokal sementara. Hanya file terpilih + token acak yang bisa diakses. */
object Sharing {
    private var server: ShareServer? = null
    var url: String? = null
        private set
    var count: Int = 0
        private set

    val isActive: Boolean get() = server != null

    fun start(files: List<File>): Result<String> {
        stop()
        val ip = localIp() ?: return Result.failure(
            IllegalStateException("Tidak ada jaringan lokal. Sambungkan HP ke Wi-Fi atau nyalakan hotspot.")
        )
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        for (port in 8080..8090) {
            try {
                val s = ShareServer(port, token, files.toList())
                s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = s
                count = files.size
                val u = "http://$ip:$port/share/$token"
                url = u
                return Result.success(u)
            } catch (e: IOException) {
                // port dipakai, coba berikutnya
            }
        }
        return Result.failure(IllegalStateException("Server lokal gagal dijalankan."))
    }

    fun stop() {
        server?.stop()
        server = null
        url = null
        count = 0
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

private class ShareServer(port: Int, private val token: String, private val files: List<File>) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response = try {
        route(session)
    } catch (e: Exception) {
        newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Terjadi kesalahan")
    }

    private fun notFound() = newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Tidak ditemukan")

    private fun route(session: IHTTPSession): Response {
        val parts = session.uri.trim('/').split('/')
        if (parts.size < 2 || parts[0] != "share" || parts[1] != token) return notFound()
        if (parts.size == 2) return page()
        if (parts.size == 3 && parts[2] == "all") return zip()
        if (parts.size == 4 && parts[2] == "file") {
            val name = URLDecoder.decode(parts[3], "UTF-8")
            // Hanya cocok dengan nama file yang dipilih -> aman dari directory traversal.
            val f = files.firstOrNull { it.name == name && it.exists() } ?: return notFound()
            return serveFile(f, session, session.parms["dl"] == "1")
        }
        return notFound()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun page(): Response {
        val live = files.filter { it.exists() }
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Galeri Foto &amp; Video</title><style>body{font-family:sans-serif;margin:0;padding:12px;background:#111;color:#eee}h1{font-size:18px}.c{background:#1e1e1e;border-radius:10px;padding:8px;margin:12px 0}img,video{width:100%;max-height:70vh;object-fit:contain;border-radius:6px;background:#000}a.b{display:block;text-align:center;background:#1e88e5;color:#fff;padding:12px;border-radius:8px;margin-top:8px;text-decoration:none;font-weight:bold}small{color:#aaa}</style></head><body><h1>GALERI FOTO &amp; VIDEO</h1>""")
        if (live.isEmpty()) {
            sb.append("<p>File sudah tidak tersedia.</p>")
        } else {
            sb.append("<a class=\"b\" href=\"/share/$token/all\">DOWNLOAD SEMUA (ZIP)</a>")
            live.forEach { f ->
                val src = "/share/$token/file/" + URLEncoder.encode(f.name, "UTF-8")
                val media = if (f.extension == "mp4") "<video controls preload=\"metadata\" src=\"$src\"></video>"
                else "<img loading=\"lazy\" src=\"$src\">"
                sb.append("<div class=\"c\">$media<small>${esc(f.name)} • ${Store.humanSize(f.length())}</small><a class=\"b\" href=\"$src?dl=1\">DOWNLOAD</a></div>")
            }
        }
        sb.append("</body></html>")
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", sb.toString())
            .apply { addHeader("Cache-Control", "no-store") }
    }

    private fun serveFile(f: File, session: IHTTPSession, download: Boolean): Response {
        val mime = if (f.extension == "mp4") "video/mp4" else "image/jpeg"
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

    private fun zip(): Response {
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
