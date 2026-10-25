package com.feelime.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * 内置离线翻译（中文 <-> 英语）。引擎是 native/feelime-translate（slimt +
 * bergamot 模型），模型文件随安装包放在 assets/translate-models，首次使用
 * 时复制到应用私有目录再加载。全程不联网、不依赖别的 App。
 *
 * 线程：翻译在单独的后台线程串行执行（模型加载也在那里，首次约一两秒），
 * [onResult] 回主线程恰好一次。
 */
object OfflineTranslator {
    sealed class Result {
        data class Ok(val text: String) : Result()

        /** [kind]：lib_missing（没打进原生库）/ models_missing（没打进模型）/
         *  unsupported（只支持中英）/ error。 */
        data class Fail(val kind: String, val message: String? = null) : Result()
    }

    private const val ASSET_DIR = "translate-models"
    /** 换模型文件时递增，旧目录会被新目录替代。 */
    private const val MODEL_REV = 1
    private val MODEL_FILES = listOf(
        "model.zhen.intgemm.alphas.bin", "vocab.zhen.spm", "lex.50.50.zhen.s2t.bin",
        "model.enzh.intgemm.alphas.bin", "srcvocab.enzh.spm", "trgvocab.enzh.spm",
        "lex.50.50.enzh.s2t.bin",
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "feelime-translate").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var handle = 0L

    @JvmStatic external fun nativeLoad(dir: String): Long
    @JvmStatic external fun nativeTranslate(handle: Long, pair: Int, text: String): String?
    @JvmStatic external fun nativeLastError(): String

    /** [from] 空串 = 自动检测（含汉字按中文，否则按英文）；[to] 为 zh 或 en。 */
    fun translate(context: Context, text: String, from: String, to: String, onResult: (Result) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val result = try {
                run(app, text, from, to)
            } catch (e: Throwable) {
                Result.Fail("error", e.javaClass.simpleName)
            }
            main.post { onResult(result) }
        }
    }

    private fun run(context: Context, text: String, from: String, to: String): Result {
        val source = if (from.isEmpty()) {
            if (text.any { it in '\u4e00'..'\u9fff' }) "zh" else "en"
        } else {
            from
        }
        if (source != "zh" && source != "en") return Result.Fail("unsupported")
        if (to != "zh" && to != "en") return Result.Fail("unsupported")
        if (source == to) return Result.Ok(text)
        val engine = ensureLoaded(context)
        if (engine is Result.Fail) return engine
        val pair = if (source == "zh") 0 else 1
        val out = nativeTranslate(handle, pair, text)
            ?: return Result.Fail("error", nativeLastError())
        return Result.Ok(out)
    }

    /** 成功返回 null（句柄已就绪），失败返回对应的 Fail。 */
    private fun ensureLoaded(context: Context): Result.Fail? = synchronized(lock) {
        if (handle != 0L) return null
        try {
            System.loadLibrary("feelime_translate")
        } catch (e: UnsatisfiedLinkError) {
            return Result.Fail("lib_missing", e.message)
        }
        val dir = File(context.filesDir, "translate-models-v$MODEL_REV")
        val marker = File(dir, ".ok")
        if (!marker.exists()) {
            dir.deleteRecursively()
            dir.mkdirs()
            for (name in MODEL_FILES) {
                try {
                    context.assets.open("$ASSET_DIR/$name").use { input ->
                        File(dir, name).outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (e: java.io.IOException) {
                    dir.deleteRecursively()
                    return Result.Fail("models_missing", name)
                }
            }
            marker.writeText("ok")
        }
        val loaded = nativeLoad(dir.absolutePath)
        if (loaded == 0L) return Result.Fail("error", nativeLastError())
        handle = loaded
        null
    }
}
