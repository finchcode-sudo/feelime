package com.feelime.ime

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手写样本记录：行构造的校验与转义、追加截断、计数、导出成 JSON 数组。 */
class InkSampleStoreTest {
    private val ink = """{"w":300,"h":200,"strokes":[[[1,2],[3,4]],[[5,6],[7,8]]]}"""

    private fun temp(): File = File.createTempFile("ink-samples", ".jsonl").also { it.deleteOnExit() }

    @Test
    fun buildLineEmbedsPayloadVerbatimWithTruthRankAndTopCandidates() {
        val line = InkSampleStore.buildLine(ink, "你", 4, listOf("个", "仆", "不"))
        assertEquals("""{"truth":"你","rank":4,"top":["个","仆","不"],"ink":$ink}""", line)
    }

    @Test
    fun buildLineKeepsAtMostTenTopCandidates() {
        val top = (1..15).map { "字$it" }
        val line = InkSampleStore.buildLine(ink, "你", 0, top)!!
        assertTrue(line.contains("字10"))
        assertTrue(!line.contains("字11"))
    }

    @Test
    fun buildLineRejectsBadInput() {
        assertNull(InkSampleStore.buildLine("", "你", 0, emptyList()))
        assertNull(InkSampleStore.buildLine("[1,2]", "你", 0, emptyList()))
        assertNull(InkSampleStore.buildLine("{\"w\":1,\n\"h\":2}", "你", 0, emptyList()))
        assertNull(InkSampleStore.buildLine(ink, "", 0, emptyList()))
        assertNull(InkSampleStore.buildLine(ink, "你好吗", 0, emptyList()))
        assertNull(InkSampleStore.buildLine(ink, "你", -1, emptyList()))
        assertNull(InkSampleStore.buildLine("{" + "x".repeat(70_000) + "}", "你", 0, emptyList()))
    }

    @Test
    fun jsonStringEscapesQuotesBackslashesAndControlChars() {
        assertEquals("\"a\\\"b\\\\c\\u000ad\"", InkSampleStore.jsonString("a\"b\\c\nd"))
    }

    @Test
    fun appendLineCountsAndTrimsToTheNewestSamplesOnlyPastTheSlack() {
        val file = temp()
        var n = 0
        // 上限 3、余量 100：写到 103 条之内都不截断，第 104 条触发截成最近 3 条。
        for (i in 1..103) n = InkSampleStore.appendLine(file, "line$i", n, maxSamples = 3)
        assertEquals(103, n)
        assertEquals(103, InkSampleStore.countLines(file))
        n = InkSampleStore.appendLine(file, "line104", n, maxSamples = 3)
        assertEquals(3, n)
        assertEquals(listOf("line102", "line103", "line104"), file.readLines())
    }

    @Test
    fun countLinesOfMissingFileIsZero() {
        assertEquals(0, InkSampleStore.countLines(File("/definitely/not/here.jsonl")))
    }

    @Test
    fun writeJsonArrayProducesAValidArrayAndReportsTheCount() {
        val file = temp()
        file.writeText("{\"a\":1}\n\n{\"a\":2}\n")
        val out = ByteArrayOutputStream()
        assertEquals(2, InkSampleStore.writeJsonArray(file, out))
        assertEquals("[{\"a\":1},\n{\"a\":2}]\n", out.toString("UTF-8"))
        val empty = ByteArrayOutputStream()
        assertEquals(0, InkSampleStore.writeJsonArray(File("/definitely/not/here.jsonl"), empty))
        assertEquals("[]\n", empty.toString("UTF-8"))
    }
}
