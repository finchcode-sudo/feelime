package com.feelime.ime

import android.content.Context
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * 手写样本本地记录（docs/design/handwriting.md §14.1）。
 *
 * 用户在设置里主动打开「记录手写样本」后，每次点选（或空格确认）手写候选，就把这次的
 * 笔迹原文 + 选中的字 + 它在候选里的排名 + 前 10 个候选存一行到 files/ink-samples.jsonl。
 * 用途是离线评测和调参（scripts/eval_handwriting.py 回放这些样本），不用于任何在线
 * 功能：只存本机，不上传，不进备份；导出由用户在设置页手动选位置保存。
 * 最多保留 [MAX_SAMPLES] 条，超出时丢最旧的。
 *
 * 行格式（JSON，一行一条）：
 * {"truth":"你","rank":4,"top":["个","仆",...],"ink":{"w":..,"h":..,"strokes":[[[x,y],..],..]}}
 * ink 就是键盘页发给识别器的 payload 原文，回放时和线上同一条输入路径。
 *
 * 行构造、追加、计数、导出都是不依赖 Context 的纯函数（File/OutputStream），JVM 单测直接覆盖。
 */
object InkSampleStore {
    const val FILE_NAME = "ink-samples.jsonl"
    const val MAX_SAMPLES = 1000
    /** 超过上限多少条才真正截断一次，避免每条都重写整个文件。 */
    private const val TRIM_SLACK = 100
    private const val MAX_PAYLOAD_CHARS = 64 * 1024
    private const val TOP_KEPT = 10

    private val lock = Any()
    private var cachedCount = -1
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "feelime-ink-samples").apply { isDaemon = true }
    }

    internal fun jsonString(text: String): String {
        val out = StringBuilder(text.length + 2).append('"')
        for (c in text) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }

    /** 一条样本的行；payload 不是单行 JSON 对象、过长，或 truth/rank 不合理时返回 null。 */
    fun buildLine(payload: String, truth: String, rank: Int, top: List<String>): String? {
        val ink = payload.trim()
        if (ink.isEmpty() || ink.length > MAX_PAYLOAD_CHARS || ink.first() != '{' || ink.last() != '}') return null
        if (ink.contains('\n') || ink.contains('\r')) return null
        if (truth.isEmpty() || truth.length > 2 || rank < 0) return null
        val topJson = top.take(TOP_KEPT).joinToString(",", "[", "]") { jsonString(it) }
        return "{\"truth\":${jsonString(truth)},\"rank\":$rank,\"top\":$topJson,\"ink\":$ink}"
    }

    /** 追加一行并返回新的条数；条数超过上限 + 余量时截成最近 [maxSamples] 条。 */
    internal fun appendLine(file: File, line: String, count: Int, maxSamples: Int = MAX_SAMPLES): Int {
        file.appendText(line + "\n", Charsets.UTF_8)
        var n = count + 1
        if (n > maxSamples + TRIM_SLACK) {
            val kept = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.takeLast(maxSamples)
            file.writeText(kept.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
            n = kept.size
        }
        return n
    }

    internal fun countLines(file: File): Int {
        if (!file.isFile) return 0
        var n = 0
        file.forEachLine(Charsets.UTF_8) { if (it.isNotBlank()) n++ }
        return n
    }

    /** 把所有样本写成一个 JSON 数组（导出文件用）；返回条数。不关闭 [out]。 */
    internal fun writeJsonArray(file: File, out: OutputStream): Int {
        val sink = out.bufferedWriter(Charsets.UTF_8)
        sink.write("[")
        var n = 0
        if (file.isFile) {
            file.forEachLine(Charsets.UTF_8) { line ->
                if (line.isNotBlank()) {
                    if (n > 0) sink.write(",\n")
                    sink.write(line)
                    n++
                }
            }
        }
        sink.write("]\n")
        sink.flush()
        return n
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 后台线程追加，失败静默（记录是尽力而为，不能影响输入）。 */
    fun appendAsync(context: Context, line: String) {
        val app = context.applicationContext
        writer.execute {
            runCatching {
                synchronized(lock) {
                    val target = file(app)
                    if (cachedCount < 0) cachedCount = countLines(target)
                    cachedCount = appendLine(target, line, cachedCount)
                }
            }
        }
    }

    fun count(context: Context): Int = synchronized(lock) {
        if (cachedCount < 0) cachedCount = runCatching { countLines(file(context)) }.getOrDefault(0)
        cachedCount
    }

    fun clear(context: Context) {
        synchronized(lock) {
            runCatching { file(context).delete() }
            cachedCount = 0
        }
    }

    /** 导出到 [out]（设置页选好的保存位置）；返回条数。 */
    fun export(context: Context, out: OutputStream): Int = synchronized(lock) {
        writeJsonArray(file(context), out)
    }
}
