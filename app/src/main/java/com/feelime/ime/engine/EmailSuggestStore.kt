package com.feelime.ime.engine

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * 邮箱记忆（2026-11-26 用户：「记住我打过的邮箱」，对标 Gboard：敲 `rfg`
 * 候选里直接出 `rfg59419@gmail.com`）。
 *
 * 学习：英文直输模式下逐键敲出一个完整邮箱，并以空格/标点收尾、点了 @ 域名
 * 候选、或离开邮箱类输入框时，记一次。粘贴的不学（调用方只在「光标向前一个
 * 字符」的输入上传 learn）。
 * 补全：光标前这一串（字母数字和 `._%+-@`）是某个已存邮箱的真前缀就给出候选，
 * 次数多、最近用过的在前。
 *
 * 邮箱存在 files/email-learned.tsv（邮箱<TAB>次数<TAB>最近使用毫秒），不进
 * 备份、不外传；密码框/终端框/禁建议框/无痕学习由调用方挡住。
 *
 * 纯逻辑（提取、判定、排序）与 Android 无关，便于 JVM 单测。
 */
object EmailSuggestStore {
    /** 取光标前文本的字数（后缀窗口），要容得下一个完整邮箱。 */
    const val WINDOW = 96
    const val MIN_PREFIX = 2
    private const val MAX_EMAIL = 80
    private const val MAX_SUGGESTIONS = 3
    private const val MAX_STORED = 200
    private const val FILE_NAME = "email-learned.tsv"

    private val EMAIL = Regex(
        "^[A-Za-z0-9._%+-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?" +
            "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)*\\.[A-Za-z]{2,}$",
    )

    private class Entry(var count: Int, var lastUsed: Long)

    private val lock = Any()
    private var learned: MutableMap<String, Entry>? = null
    private var dirty = false
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "feelime-email-learn").apply { isDaemon = true }
    }

    private fun isAsciiAlnum(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun isTokenChar(c: Char): Boolean = isAsciiAlnum(c) || c in "._%+-@"

    /** 学习用的收尾字符：空白和常见标点。句点不算——`a@b.com.cn` 敲到
     *  `a@b.com.` 时不能提前把 `a@b.com` 记下来。 */
    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || c in ",;:!?)]}>\"”"

    internal fun isEmail(text: String): Boolean =
        text.length <= MAX_EMAIL && EMAIL.matches(text)

    /** 光标前末尾连续的「邮箱字符」串；空、超长、含多个 @ 返回 null。 */
    internal fun trailingToken(before: String): String? {
        var start = before.length
        while (start > 0 && isTokenChar(before[start - 1])) start--
        while (start < before.length && !isAsciiAlnum(before[start])) start++
        val token = before.substring(start)
        if (token.isEmpty() || token.length > MAX_EMAIL) return null
        if (token.count { it == '@' } > 1) return null
        return token
    }

    /** 补全用的前缀：末尾那一串，至少 [MIN_PREFIX] 个字符。 */
    internal fun completionToken(before: String): String? =
        trailingToken(before)?.takeIf { it.length >= MIN_PREFIX }

    /** 光标前文本恰好以一个完整邮箱结尾（点了 @ 域名候选之后就是这样）。 */
    internal fun trailingEmail(before: String): String? =
        trailingToken(before)?.takeIf { isEmail(it) }

    /** 刚被空格/标点收尾的完整邮箱；否则 null。用于学习。 */
    internal fun finishedEmail(before: String): String? {
        if (before.length < 2) return null
        if (!isBoundary(before[before.length - 1])) return null
        return trailingEmail(before.substring(0, before.length - 1))
    }

    /** 整个输入框的内容恰好是一个邮箱（离开邮箱框时用）。 */
    internal fun wholeEmail(text: String): String? =
        text.trim().takeIf { isEmail(it) }

    /** 补全排序（纯函数）：邮箱要以 [token]（不分大小写）开头且比它长；次数
     *  多的在前，次数相同最近用过的在前。 */
    internal fun rank(token: String, entries: List<Triple<String, Int, Long>>): List<String> {
        val lower = token.lowercase()
        return entries
            .filter { (email, _, _) -> email.startsWith(lower) && email.length > lower.length }
            .sortedWith(compareByDescending<Triple<String, Int, Long>> { it.second }
                .thenByDescending { it.third })
            .take(MAX_SUGGESTIONS)
            .map { it.first }
    }

    fun suggest(context: Context, token: String): List<String> {
        if (token.length < MIN_PREFIX) return emptyList()
        val snapshot = synchronized(lock) {
            ensureLoaded(context).map { (email, entry) -> Triple(email, entry.count, entry.lastUsed) }
        }
        return rank(token, snapshot)
    }

    /** 记一次这个邮箱（统一小写）。 */
    fun learn(context: Context, rawEmail: String) {
        val email = rawEmail.trim().lowercase()
        if (!isEmail(email)) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val map = ensureLoaded(context)
            val entry = map[email]
            if (entry == null) {
                map[email] = Entry(1, now)
                if (map.size > MAX_STORED) prune(map)
            } else {
                entry.count += 1
                entry.lastUsed = now
            }
            dirty = true
        }
        scheduleSave(context)
    }

    /** 清掉所有记住的邮箱（设置页「清除已记住的邮箱」）。 */
    fun clear(context: Context) {
        synchronized(lock) {
            ensureLoaded(context).clear()
            dirty = true
        }
        scheduleSave(context)
    }

    private fun prune(map: MutableMap<String, Entry>) {
        val victims = map.entries
            .sortedWith(compareBy<Map.Entry<String, Entry>> { it.value.count }
                .thenBy { it.value.lastUsed })
            .take(MAX_STORED / 10).map { it.key }
        victims.forEach { map.remove(it) }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun ensureLoaded(context: Context): MutableMap<String, Entry> {
        learned?.let { return it }
        val map = LinkedHashMap<String, Entry>()
        runCatching {
            val source = file(context)
            if (source.isFile) {
                source.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size != 3) return@forEachLine
                    val count = parts[1].toIntOrNull() ?: return@forEachLine
                    val last = parts[2].toLongOrNull() ?: return@forEachLine
                    if (isEmail(parts[0])) map[parts[0]] = Entry(count, last)
                }
            }
        }
        learned = map
        return map
    }

    private fun scheduleSave(context: Context) {
        val app = context.applicationContext
        writer.execute {
            val lines = synchronized(lock) {
                if (!dirty) null
                else {
                    dirty = false
                    learned.orEmpty().map { (email, entry) ->
                        "$email\t${entry.count}\t${entry.lastUsed}"
                    }
                }
            }
            if (lines != null) {
                runCatching {
                    val target = file(app)
                    val temp = File(app.filesDir, "$FILE_NAME.tmp")
                    temp.writeText(lines.joinToString("\n", postfix = "\n"))
                    if (!temp.renameTo(target)) {
                        target.delete()
                        temp.renameTo(target)
                    }
                }
            }
        }
    }
}
