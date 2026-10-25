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
        // 词汇表里的专有名词（抖音→TikTok 等）直接替换，其余片段才交给引擎，
        // 引擎只会按字面翻（抖音→trembling）。
        val pieces = splitByGlossary(context, text, source)
        val outputs = ArrayList<String>()
        val pair = if (source == "zh") 0 else 1
        for (piece in pieces) {
            if (piece.fixed != null) {
                outputs += piece.fixed
            } else if (piece.text.isNotBlank()) {
                val failed = ensureLoaded(context)
                if (failed != null) return failed
                val out = nativeTranslate(handle, pair, piece.text.trim())
                    ?: return Result.Fail("error", nativeLastError())
                outputs += out.trim()
            }
        }
        val joined = if (to == "en") {
            outputs.joinToString(" ").replace(Regex("\\s+([,.;:!?])"), "$1")
        } else {
            outputs.joinToString("")
        }
        return Result.Ok(joined)
    }

    /** [fixed] 非空 = 词汇表命中的专有名词（已是目标语言写法），不再翻译。 */
    private class Piece(val text: String, val fixed: String?)

    @Volatile private var glossary: List<Pair<String, String>>? = null

    /** assets/translate-glossary.tsv：每行「中文<TAB>English」，# 开头为注释。 */
    private fun loadGlossary(context: Context): List<Pair<String, String>> {
        glossary?.let { return it }
        val loaded = try {
            context.assets.open("translate-glossary.tsv").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .mapNotNull { line ->
                        val cut = line.indexOf('\t')
                        if (cut <= 0 || cut == line.length - 1) null
                        else line.substring(0, cut).trim() to line.substring(cut + 1).trim()
                    }
                    .toList()
            }
        } catch (e: java.io.IOException) {
            emptyList()
        }
        glossary = loaded
        return loaded
    }

    private fun splitByGlossary(context: Context, text: String, source: String): List<Piece> {
        val entries = loadGlossary(context)
        if (entries.isEmpty()) return listOf(Piece(text, null))
        val pieces = ArrayList<Piece>()
        if (source == "zh") {
            val terms = entries.sortedByDescending { it.first.length }
            val plain = StringBuilder()
            var i = 0
            while (i < text.length) {
                val hit = terms.firstOrNull { text.startsWith(it.first, i) }
                if (hit != null) {
                    if (plain.isNotEmpty()) {
                        pieces += Piece(plain.toString(), null)
                        plain.setLength(0)
                    }
                    pieces += Piece(hit.first, hit.second)
                    i += hit.first.length
                } else {
                    plain.append(text[i])
                    i += 1
                }
            }
            if (plain.isNotEmpty()) pieces += Piece(plain.toString(), null)
        } else {
            val byEnglish = entries.associate { it.second.lowercase() to it.first }
            val alternation = entries.map { it.second }.sortedByDescending { it.length }
                .joinToString("|") { Regex.escape(it) }
            val regex = Regex("(?<![A-Za-z0-9])(?:$alternation)(?![A-Za-z0-9])", RegexOption.IGNORE_CASE)
            var last = 0
            for (match in regex.findAll(text)) {
                if (match.range.first > last) pieces += Piece(text.substring(last, match.range.first), null)
                val zh = byEnglish[match.value.lowercase()]
                pieces += if (zh != null) Piece(match.value, zh) else Piece(match.value, null)
                last = match.range.last + 1
            }
            if (last < text.length) pieces += Piece(text.substring(last), null)
        }
        return pieces
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
