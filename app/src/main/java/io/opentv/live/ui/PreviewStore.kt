package io.opentv.live.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import io.opentv.live.App
import java.io.File

/**
 * 频道预览图仓库：抓到的首帧存在 files/previews 下，内存再缓存一份。
 * 播放源没有台标时，卡片小窗口用这里的真实画面做预览。
 */
object PreviewStore {

    const val WIDTH = 320
    const val HEIGHT = 180

    /** 预览图有效期：超过 24 小时会重新抓一帧替换 */
    private const val VALIDITY_MS = 24L * 60 * 60 * 1000

    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()

    private val dir: File by lazy { File(App.get().filesDir, "previews") }

    /** 取已生成的预览图；超过有效期也先返回旧图，等后台抓好再替换 */
    fun get(id: String): Bitmap? {
        memory.get(id)?.let { return it }
        val file = File(dir, "$id.jpg")
        if (!file.exists()) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        memory.put(id, bitmap)
        return bitmap
    }

    /** 是否还有效（文件存在且未超过 24 小时） */
    fun has(id: String): Boolean {
        val file = File(dir, "$id.jpg")
        if (!file.exists()) return false
        return System.currentTimeMillis() - file.lastModified() < VALIDITY_MS
    }

    fun put(id: String, source: Bitmap) {
        val scaled = if (source.width == WIDTH && source.height == HEIGHT) {
            source
        } else {
            Bitmap.createScaledBitmap(source, WIDTH, HEIGHT, true)
        }
        memory.put(id, scaled)
        runCatching {
            dir.mkdirs()
            File(dir, "$id.jpg").outputStream().use {
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, it)
            }
        }
    }

    /** 本次运行内抓帧失败的频道不再反复重试，避免一直占用带宽 */
    fun isFailed(id: String): Boolean = failed.contains(id)

    fun markFailed(id: String) {
        failed.add(id)
    }
}
