package com.offlinephotovideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import java.io.FileOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MediaItem(val file: File, val isVideo: Boolean) {
    val name: String get() = file.name
    val created: Long get() = file.lastModified()
    val size: Long get() = file.length()

    /** Waktu file akan dihapus (umur file + masa simpan), atau null jika tidak pernah dihapus. */
    val expiresAt: Long?
        get() = Store.retentionMinutes.takeIf { it > 0 }?.let { created + it * 60_000L }
}

object Store {
    private lateinit var app: Context
    fun init(c: Context) { app = c.applicationContext }

    private val prefs get() = app.getSharedPreferences("opv", Context.MODE_PRIVATE)

    /** Penyimpanan privat aplikasi: tidak bisa diakses aplikasi lain. */
    val root: File get() = app.filesDir
    private val photosDir get() = File(app.filesDir, "Photos").apply { mkdirs() }
    private val videosDir get() = File(app.filesDir, "Videos").apply { mkdirs() }

    val VIDEO_EXT = setOf("mp4", "mov", "webm", "3gp", "m4v", "mkv")
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")

    /** File kiriman tamu (lewat QR undangan): otomatis masuk folder foto atau video sesuai jenisnya. */
    fun newGuestFile(ext: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val video = ext in VIDEO_EXT
        val dir = if (video) videosDir else photosDir
        val prefix = if (video) "VID_TAMU_" else "IMG_TAMU_"
        return File(dir, prefix + ts + "_" + (System.nanoTime() % 1000) + "." + ext)
    }

    private val thumbLock = Any()

    private fun rotateByExif(f: File, b: Bitmap): Bitmap {
        val deg = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        if (deg == 0f) return b
        val m = Matrix().apply { postRotate(deg) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Thumbnail kecil (maks 480 px) untuk halaman tamu. Disimpan di cache supaya cepat dibuka berulang. */
    fun thumbFor(f: File): File? {
        val dir = File(app.cacheDir, "thumbs").apply { mkdirs() }
        val out = File(dir, f.name + "_" + f.lastModified() + ".jpg")
        if (out.exists()) return out
        synchronized(thumbLock) {
            if (out.exists()) return out
            val bmp: Bitmap? = try {
                if (f.extension.lowercase() in VIDEO_EXT) {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(f.path)
                        r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } finally {
                        r.release()
                    }
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    var sample = 1
                    while (o.outWidth / (sample * 2) >= 480 && o.outHeight / (sample * 2) >= 480) sample *= 2
                    val decoded = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    decoded?.let { rotateByExif(f, it) }
                }
            } catch (e: Throwable) { null }
            if (bmp == null) return null
            val k = 480f / maxOf(bmp.width, bmp.height)
            val scaled = if (k < 1f) {
                Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * k).toInt()), maxOf(1, (bmp.height * k).toInt()), true)
            } else bmp
            return try {
                val tmp = File(dir, out.name + ".tmp")
                FileOutputStream(tmp).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (tmp.renameTo(out)) out else null
            } catch (e: Exception) { null }
        }
    }

    /** 0 = tidak pernah dihapus. */
    var retentionMinutes: Long
        get() = prefs.getLong("ret", 0L)
        set(v) { prefs.edit().putLong("ret", v).apply() }

    /** 0 = kualitas maksimal, 1 = cepat. */
    var photoQuality: Int
        get() = prefs.getInt("pq", 0)
        set(v) { prefs.edit().putInt("pq", v).apply() }

    /** MAX (tertinggi yang didukung HP) / UHD / FHD / HD / SD. Jika tidak didukung, otomatis turun. */
    var videoQuality: String
        get() = prefs.getString("vq", "MAX") ?: "MAX"
        set(v) { prefs.edit().putString("vq", v).apply() }

    fun newFile(video: Boolean): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return if (video) File(videosDir, "VID_$ts.mp4") else File(photosDir, "IMG_$ts.jpg")
    }

    fun list(): List<MediaItem> {
        val p = photosDir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }.map { MediaItem(it, false) }
        val v = videosDir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 && !it.name.endsWith(".part") }.map { MediaItem(it, true) }
        return (p + v).sortedByDescending { it.created }
    }

    /** Hapus semua file yang umurnya sudah melewati masa simpan. Mengembalikan jumlah file terhapus. */
    fun deleteExpired(): Int {
        val now = System.currentTimeMillis()
        var n = 0
        list().forEach {
            val e = it.expiresAt
            if (e != null && now >= e && it.file.delete()) n++
        }
        return n
    }

    fun usedBytes(): Long = list().sumOf { it.size }
    fun freeBytes(): Long = app.filesDir.usableSpace
    fun isStorageLow(): Boolean = freeBytes() < 100L * 1024 * 1024

    fun humanSize(b: Long): String = when {
        b >= 1073741824L -> "%.1f GB".format(b / 1073741824.0)
        b >= 1048576L -> "%.1f MB".format(b / 1048576.0)
        b >= 1024L -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    fun humanRemaining(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val s = ms / 1000
        val d = s / 86400
        val h = s % 86400 / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (d > 0) "$d hari $h jam" else "%02d:%02d:%02d".format(h, m, sec)
    }

    fun dateStr(ms: Long): String = SimpleDateFormat("dd MMM yyyy HH:mm", Locale("id", "ID")).format(Date(ms))
}
