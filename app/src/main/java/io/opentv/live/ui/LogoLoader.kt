package io.opentv.live.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 台标 / 频道预览图加载器：内存 + 磁盘两级缓存，最多 3 个并发下载。
 * 没有引入第三方图片库，按缩略图尺寸做采样解码即可满足 4 列网格。
 */
object LogoLoader {

    private const val MAX_WIDTH = 420
    private const val DISK_DIR = "logos"

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var cacheDir: File? = null

    /**
     * 异步加载 [url] 到 [view]，回调在主线程执行。
     * 视图已被复用到别的地址时旧回调会被丢弃，避免串图。
     */
    fun load(context: Context, url: String, view: ImageView, onResult: (Boolean) -> Unit) {
        val target = url.trim()
        if (target.isEmpty()) {
            view.tag = null
            view.setImageDrawable(null)
            onResult(false)
            return
        }
        val dir = cacheDir ?: File(context.applicationContext.cacheDir, DISK_DIR).also { cacheDir = it }
        view.tag = target

        cache.get(target)?.let { bitmap ->
            view.setImageBitmap(bitmap)
            onResult(true)
            return
        }
        view.setImageDrawable(null)
        executor.execute {
            val bitmap = readDisk(dir, target) ?: download(target)?.also { saveDisk(dir, target, it) }
            if (bitmap != null) cache.put(target, bitmap)
            main.post {
                if (view.tag != target) return@post
                if (bitmap != null) {
                    view.setImageBitmap(bitmap)
                    onResult(true)
                } else {
                    onResult(false)
                }
            }
        }
    }

    private fun diskFile(dir: File, url: String): File =
        File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)

    private fun readDisk(dir: File, url: String): Bitmap? = runCatching {
        val file = diskFile(dir, url)
        if (file.exists()) decode(file.readBytes()) else null
    }.getOrNull()

    private fun saveDisk(dir: File, url: String, bitmap: Bitmap) {
        runCatching {
            dir.mkdirs()
            FileOutputStreamCompat.write(diskFile(dir, url), bitmap)
        }
    }

    private fun download(url: String): Bitmap? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) FreeTV/1.0")
        }
        try {
            conn.inputStream.use { decode(it.readBytes()) }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
    }

    private object FileOutputStreamCompat {
        fun write(file: File, bitmap: Bitmap) {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
