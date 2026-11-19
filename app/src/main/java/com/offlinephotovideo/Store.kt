package com.offlinephotovideo

import android.content.Context
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

    /** 0 = tidak pernah dihapus. */
    var retentionMinutes: Long
        get() = prefs.getLong("ret", 0L)
        set(v) { prefs.edit().putLong("ret", v).apply() }

    /** 0 = kualitas maksimal, 1 = cepat. */
    var photoQuality: Int
        get() = prefs.getInt("pq", 0)
        set(v) { prefs.edit().putInt("pq", v).apply() }

    /** SD / HD / FHD / UHD. Jika tidak didukung HP, otomatis turun ke yang tersedia. */
    var videoQuality: String
        get() = prefs.getString("vq", "FHD") ?: "FHD"
        set(v) { prefs.edit().putString("vq", v).apply() }

    fun newFile(video: Boolean): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return if (video) File(videosDir, "VID_$ts.mp4") else File(photosDir, "IMG_$ts.jpg")
    }

    fun list(): List<MediaItem> {
        val p = photosDir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }.map { MediaItem(it, false) }
        val v = videosDir.listFiles().orEmpty().filter { it.isFile && it.length() > 0 }.map { MediaItem(it, true) }
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
