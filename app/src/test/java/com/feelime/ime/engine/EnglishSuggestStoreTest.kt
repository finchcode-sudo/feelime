package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 英文补全的纯逻辑：单词前缀提取、收尾词识别、补全排序/大小写。 */
class EnglishSuggestStoreTest {

    @Test
    fun trailingPrefixTakesTheWordBeingTyped() {
        assertEquals("wor", EnglishSuggestStore.trailingPrefix("hello wor"))
        assertEquals("po", EnglishSuggestStore.trailingPrefix("I like po"))
        // 汉字之后紧跟的英文词、连字符后的词、引号后的词都算独立单词。
        assertEquals("hello", EnglishSuggestStore.trailingPrefix("你好hello"))
        assertEquals("kno", EnglishSuggestStore.trailingPrefix("well-kno"))
        assertEquals("tis", EnglishSuggestStore.trailingPrefix("say 'tis"))
    }

    @Test
    fun trailingPrefixRejectsNonWordContexts() {
        assertNull(EnglishSuggestStore.trailingPrefix("a"))
        assertNull(EnglishSuggestStore.trailingPrefix("ok "))
        assertNull(EnglishSuggestStore.trailingPrefix("user@exam"))
        assertNull(EnglishSuggestStore.trailingPrefix("100px"))
        assertNull(EnglishSuggestStore.trailingPrefix("a".repeat(25)))
    }

    @Test
    fun finishedWordNeedsABoundaryRightAfterTheWord() {
        assertEquals("pork", EnglishSuggestStore.finishedWord("I like pork "))
        assertEquals("Hello", EnglishSuggestStore.finishedWord("Hello,"))
        assertNull(EnglishSuggestStore.finishedWord("Hello, "))
        assertNull(EnglishSuggestStore.finishedWord("pork"))
        assertNull(EnglishSuggestStore.finishedWord("a "))
        assertNull(EnglishSuggestStore.finishedWord("see user@mail.com "))
    }

    private val builtin = listOf(
        "GitHub" to "github", "pork" to "pork", "porch" to "porch", "port" to "port",
        "portal" to "portal", "porter" to "porter", "portrait" to "portrait", "porn" to "porn",
    )
    private val learned = listOf(
        Triple("pornhub", 3, "pornhub"),
        Triple("portfolio", 1, "portfolio"),
        Triple("iphone", 5, "iPhone"),
    )

    @Test
    fun learnedWordsOutrankBuiltinAndKeepCountOrder() {
        assertEquals(
            listOf("pornhub", "portfolio", "pork", "porch", "port", "portal"),
            EnglishSuggestStore.rank("por", learned, builtin),
        )
    }

    @Test
    fun suggestionsFollowTheCaseOfThePrefix() {
        assertEquals(
            listOf("Pornhub", "Portfolio", "Pork", "Porch", "Port", "Portal"),
            EnglishSuggestStore.rank("Por", learned, builtin),
        )
        assertEquals(
            listOf("PORNHUB", "PORTFOLIO", "PORK", "PORCH", "PORT", "PORTAL"),
            EnglishSuggestStore.rank("POR", learned, builtin),
        )
        // 带内部大写的学到词形原样保留。
        assertEquals(listOf("iPhone"), EnglishSuggestStore.rank("ip", learned, builtin))
    }

    @Test
    fun completeWordsAreNotSuggestedBackToTheUser() {
        assertEquals(emptyList<String>(), EnglishSuggestStore.rank("pornhub", learned, builtin))
    }
}
