package com.feelime.ime.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** 剪贴板里的一张图片：原图落在 filesDir/clipboard_images/，[src] 是来源
 *  uri 的指纹（只存哈希，用于去重与「清空后不复活」抑制）。 */
data class ClipImage(
    val id: String,
    val time: Long,
    val mime: String,
    val file: String,
    val src: String,
)

/**
 * 剪贴板图片历史（与文字历史分开存，面板按时间合并显示）。
 *
 * - 抓取：把系统剪贴板里的 content:// 图片复制进应用私有目录（来源 uri
 *   的读权限只在剪贴板持有期间有效，必须当场拷贝），同时生成小缩略图。
 * - 上屏：经 FileProvider uri + InputConnectionCompat.commitContent 交给
 *   目标输入框（与 Gboard 同一机制，目标输入框必须声明支持该 MIME）。
 * - 上限：最多 [MAX_IMAGES] 张、单张 [MAX_BYTES]；超出淘汰最旧。
 * - 图片 id 以 'f' 开头，文字历史 id 以 '0' 开头，两个 id 空间不相交，
 *   面板的 removeClipboard(id) 据此分流。
 */
class ClipImageStore(private val context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("feelime_clipboard", Context.MODE_PRIVATE)
    private val dir = File(context.applicationContext.filesDir, DIR_NAME)
    private val idCounter = AtomicLong(System.currentTimeMillis())
    private val lock = Any()

    fun items(): List<ClipImage> = synchronized(lock) { readIndex() }

    /** @return true when a NEW image was stored (false: duplicate/unsupported/too large). */
    fun add(uri: Uri, mime: String, srcFingerprint: String): Boolean {
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> return false
        }
        synchronized(lock) {
            if (readIndex().any { it.src == srcFingerprint }) return false
        }
        dir.mkdirs()
        val id = nextId()
        val target = File(dir, "$id.$ext")
        val thumb = File(dir, "$id.thumb.jpg")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return false
            input.use { stream ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException("image too large")
                        out.write(buffer, 0, n)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
            writeThumb(target, bounds, thumb)
        } catch (e: Exception) {
            target.delete()
            thumb.delete()
            return false
        }
        synchronized(lock) {
            val next = ArrayList<ClipImage>()
            next.add(ClipImage(id, System.currentTimeMillis(), mime, target.name, srcFingerprint))
            next.addAll(readIndex())
            while (next.size > MAX_IMAGES) {
                val dropped = next.removeAt(next.size - 1)
                deleteFiles(dropped)
            }
            writeIndex(next)
        }
        return true
    }

    fun remove(id: String) {
        synchronized(lock) {
            val all = readIndex()
            all.firstOrNull { it.id == id }?.let { deleteFiles(it) }
            writeIndex(all.filter { it.id != id })
        }
    }

    fun clear() {
        synchronized(lock) {
            readIndex().forEach { deleteFiles(it) }
            writeIndex(emptyList())
        }
    }

    /** 缩略图 data: uri（键盘页 CSP 允许 img-src data:）；文件缺失返回 null。 */
    fun thumbDataUri(image: ClipImage): String? = runCatching {
        val file = File(dir, thumbName(image))
        if (!file.isFile) return null
        "data:image/jpeg;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }.getOrNull()

    /** FileProvider uri（authority 与 manifest 的 ${applicationId}.files 一致）。 */
    fun contentUri(image: ClipImage): Uri? = runCatching {
        val file = File(dir, image.file)
        if (!file.isFile) return null
        FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }.getOrNull()

    private fun thumbName(image: ClipImage) = image.id + ".thumb.jpg"

    private fun deleteFiles(image: ClipImage) {
        File(dir, image.file).delete()
        File(dir, thumbName(image)).delete()
    }

    private fun writeThumb(source: File, bounds: BitmapFactory.Options, dest: File) {
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= THUMB_PX) sample *= 2
        val decoded = BitmapFactory.decodeFile(
            source.path, BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: throw IOException("decode failed")
        val scale = THUMB_PX.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else decoded
        // JPEG 没有透明通道：先铺白底，避免透明 PNG 的缩略图变黑。
        val flat = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(scaled, 0f, 0f, null)
        }
        dest.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        flat.recycle()
        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()
    }

    private fun readIndex(): MutableList<ClipImage> {
        val raw = prefs.getString(KEY, "").orEmpty()
        if (raw.isEmpty()) return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index ->
                val o = array.getJSONObject(index)
                ClipImage(
                    o.getString("id"), o.getLong("time"), o.getString("mime"),
                    o.getString("file"), o.optString("src"),
                )
            }
        }.getOrDefault(mutableListOf())
    }

    private fun writeIndex(list: List<ClipImage>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject().put("id", it.id).put("time", it.time).put("mime", it.mime)
                    .put("file", it.file).put("src", it.src),
            )
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private fun nextId(): String = "f%011x".format(idCounter.incrementAndGet())

    private companion object {
        const val DIR_NAME = "clipboard_images"
        const val KEY = "images"
        const val MAX_IMAGES = 20
        const val MAX_BYTES = 8L * 1024 * 1024
        const val THUMB_PX = 160
    }
}
