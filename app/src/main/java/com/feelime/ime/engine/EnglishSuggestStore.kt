package com.feelime.ime.engine

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * 英文补全与学习（2026-11-02 用户：「Gboard 都知道我的喜好，怎么我的输入法
 * 不知道」）。英文 Direct 模式逐键上屏、没有组合，所以补全按「编辑框里光标
 * 前的真实文本」算：取末尾正在输入的单词前缀，从「学到的词（按次数）→ 内置
 * 技术词 → 内置高频词表」里找以它开头的词。
 *
 * 学习：用户敲出单词后紧跟空格/标点（单字符正向输入）就记一次；点补全候选也
 * 记一次。词存在 files/english-learned.tsv（key<TAB>次数<TAB>词形），不进
 * 备份、不外传；密码框/终端框/禁建议框由调用方挡住，不会走到这里。
 *
 * 纯逻辑（前缀提取、排序）与 Android 无关，便于 JVM 单测。
 */
object EnglishSuggestStore {
    /** 取光标前文本的字数（后缀窗口）。 */
    const val WINDOW = 48
    const val MIN_PREFIX = 2
    private const val MAX_WORD = 24
    private const val MIN_WORD = 2
    private const val MAX_SUGGESTIONS = 6
    private const val MAX_LEARNED = 3000
    private const val FILE_NAME = "english-learned.tsv"

    private class Entry(var count: Int, var form: String)

    private val lock = Any()
    private var learned: MutableMap<String, Entry>? = null
    private var dirty = false
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "feelime-english-learn").apply { isDaemon = true }
    }

    private fun isWordChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c == '\''

    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || c in ".,!?;:)]}\"”"

    /** 单词前一个字符是否允许它作为「一个独立英文词」的开头：行首/空白/开
     *  括号引号/连字符/汉字之后都行；紧贴 @ / . 数字等（邮箱、网址、文件名）
     *  不算。 */
    private fun startsAtWordBoundary(before: String, start: Int): Boolean {
        if (start == 0) return true
        val previous = before[start - 1]
        return previous.isWhitespace() || previous in "\"“‘'([{-—" ||
            previous.code >= 0x2E80
    }

    /** 光标前文本末尾正在输入的单词前缀；不是英文单词输入态（太短、太长、
     *  夹在邮箱/网址/数字里）返回 null。 */
    internal fun trailingPrefix(before: String): String? {
        var start = before.length
        while (start > 0 && isWordChar(before[start - 1])) start--
        while (start < before.length && before[start] == '\'') start++
        val prefix = before.substring(start)
        if (prefix.length < MIN_PREFIX || prefix.length > MAX_WORD) return null
        if (!startsAtWordBoundary(before, start)) return null
        return prefix
    }

    /** 刚被空格/标点收尾的英文单词（光标前文本最后一个字符是收尾字符）；
     *  否则 null。用于学习。 */
    internal fun finishedWord(before: String): String? {
        if (before.length < 2) return null
        if (!isBoundary(before[before.length - 1])) return null
        return trailingPrefix(before.substring(0, before.length - 1))
    }

    /** 补全排序（纯函数）：已学词按次数降序在前，其后内置词按给定顺序；
     *  都要以 [prefix]（不分大小写）开头且比它长；大小写跟随输入——首字母大写
     *  的前缀给首字母大写的词，全大写前缀给全大写词。 */
    internal fun rank(
        prefix: String,
        learnedWords: List<Triple<String, Int, String>>,
        builtin: List<Pair<String, String>>,
    ): List<String> {
        val lower = prefix.lowercase()
        val shout = prefix.length >= 2 && prefix.none { it.isLowerCase() }
        val capital = !shout && prefix[0].isUpperCase()
        val seen = HashSet<String>()
        val out = ArrayList<String>()

        fun add(key: String, form: String) {
            if (out.size >= MAX_SUGGESTIONS) return
            if (!key.startsWith(lower) || key.length <= lower.length) return
            if (!seen.add(key)) return
            val shown = when {
                shout -> form.uppercase()
                capital && form == form.lowercase() ->
                    form.replaceFirstChar { it.uppercase() }
                else -> form
            }
            out.add(shown)
        }

        learnedWords.sortedByDescending { it.second }.forEach { (key, _, form) -> add(key, form) }
        builtin.forEach { (form, key) -> add(key, form) }
        return out
    }

    /** 当前前缀的补全候选。 */
    fun suggest(context: Context, prefix: String): List<String> {
        if (prefix.length < MIN_PREFIX) return emptyList()
        val snapshot = synchronized(lock) {
            ensureLoaded(context).map { (key, entry) -> Triple(key, entry.count, entry.form) }
        }
        val builtin = CustomPhraseStore.DEFAULT_ENGLISH_WORDS +
            CustomPhraseStore.frequencyEnglishWords(context)
        return rank(prefix, snapshot, builtin)
    }

    /** 学一次这个词。词形：带内部大写（iPhone/GitHub）或短全大写（APK）
     *  按原样记，其余一律小写，句首大写不会污染词形。 */
    fun learn(context: Context, rawWord: String) {
        val word = rawWord.trim('\'')
        if (word.length < MIN_WORD || word.length > MAX_WORD || !word.any { it.isLetter() }) return
        val key = word.lowercase()
        val mixed = word.drop(1).any { it.isUpperCase() } &&
            (word.any { it.isLowerCase() } || word.length <= 4)
        val form = if (mixed) word else key
        synchronized(lock) {
            val map = ensureLoaded(context)
            val entry = map[key]
            if (entry == null) {
                map[key] = Entry(1, form)
                if (map.size > MAX_LEARNED) prune(map)
            } else {
                entry.count += 1
                if (mixed) entry.form = form
            }
            dirty = true
        }
        scheduleSave(context)
    }

    /** 清掉所有学到的词（预留给设置页；当前没有入口）。 */
    fun clear(context: Context) {
        synchronized(lock) {
            ensureLoaded(context).clear()
            dirty = true
        }
        scheduleSave(context)
    }

    private fun prune(map: MutableMap<String, Entry>) {
        val victims = map.entries.sortedBy { it.value.count }
            .take(MAX_LEARNED / 10).map { it.key }
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
                    if (parts[0].isNotEmpty() && parts[2].isNotEmpty()) {
                        map[parts[0]] = Entry(count, parts[2])
                    }
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
                    learned.orEmpty().map { (key, entry) -> "$key\t${entry.count}\t${entry.form}" }
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
