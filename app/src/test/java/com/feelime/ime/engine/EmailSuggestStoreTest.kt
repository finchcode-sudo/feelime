package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 邮箱记忆的纯逻辑：邮箱判定、光标前提取、收尾识别、补全排序。 */
class EmailSuggestStoreTest {

    @Test
    fun isEmailAcceptsOrdinaryAddressesOnly() {
        assertTrue(EmailSuggestStore.isEmail("rfg59419@gmail.com"))
        assertTrue(EmailSuggestStore.isEmail("a.b-c_d+tag@mail.example.co.uk"))
        assertFalse(EmailSuggestStore.isEmail("rfg59419@gmail"))
        assertFalse(EmailSuggestStore.isEmail("rfg59419@gmail."))
        assertFalse(EmailSuggestStore.isEmail("@gmail.com"))
        assertFalse(EmailSuggestStore.isEmail("a@@b.com"))
        assertFalse(EmailSuggestStore.isEmail("a@b.c"))
        assertFalse(EmailSuggestStore.isEmail("a b@c.com"))
        assertFalse(EmailSuggestStore.isEmail("a".repeat(80) + "@b.com"))
    }

    @Test
    fun completionTokenTakesTheRunBeforeTheCursor() {
        assertEquals("rfg", EmailSuggestStore.completionToken("mail me: rfg"))
        assertEquals("rfg59419", EmailSuggestStore.completionToken("rfg59419"))
        assertEquals("john.smi", EmailSuggestStore.completionToken("hi john.smi"))
        assertEquals("rfg59419@g", EmailSuggestStore.completionToken("你好rfg59419@g"))
        // 前导标点不算，太短不出，多个 @ 不出，空白后没有字符不出。
        assertEquals("ab", EmailSuggestStore.completionToken("...ab"))
        assertNull(EmailSuggestStore.completionToken("a"))
        assertNull(EmailSuggestStore.completionToken("a@b@c"))
        assertNull(EmailSuggestStore.completionToken("hello "))
    }

    @Test
    fun finishedEmailNeedsABoundaryRightAfterAFullAddress() {
        assertEquals("rfg59419@gmail.com", EmailSuggestStore.finishedEmail("to rfg59419@gmail.com "))
        assertEquals("a@b.org", EmailSuggestStore.finishedEmail("(a@b.org)"))
        assertEquals("a@b.org", EmailSuggestStore.finishedEmail("a@b.org,"))
        // 还在敲、没收尾、句点收尾（可能是 .com.cn）、半截域名都不算。
        assertNull(EmailSuggestStore.finishedEmail("a@b.org"))
        assertNull(EmailSuggestStore.finishedEmail("a@b.org."))
        assertNull(EmailSuggestStore.finishedEmail("a@b "))
        assertNull(EmailSuggestStore.finishedEmail("hello world "))
    }

    @Test
    fun trailingEmailSeesTheAddressAfterADomainChip() {
        assertEquals("rfg59419@gmail.com", EmailSuggestStore.trailingEmail("send to rfg59419@gmail.com"))
        assertNull(EmailSuggestStore.trailingEmail("send to rfg59419@"))
    }

    @Test
    fun wholeEmailTrimsAndRequiresTheWholeField() {
        assertEquals("a@b.com", EmailSuggestStore.wholeEmail("  a@b.com\n"))
        assertNull(EmailSuggestStore.wholeEmail("mail a@b.com"))
        assertNull(EmailSuggestStore.wholeEmail(""))
    }

    private val entries = listOf(
        Triple("rfg59419@gmail.com", 3, 100L),
        Triple("rfg@qq.com", 3, 200L),
        Triple("rfgx@163.com", 1, 300L),
        Triple("other@gmail.com", 9, 400L),
        Triple("rfg59419@qq.com", 2, 50L),
    )

    @Test
    fun rankPutsFrequentThenRecentFirstAndNeedsARealPrefix() {
        assertEquals(
            listOf("rfg@qq.com", "rfg59419@gmail.com", "rfg59419@qq.com"),
            EmailSuggestStore.rank("rfg", entries),
        )
        assertEquals(listOf("rfg59419@gmail.com", "rfg59419@qq.com"), EmailSuggestStore.rank("rfg5", entries))
        // 不分大小写；完整邮箱本身不再作为候选；半截域名也能补。
        assertEquals(listOf("rfg59419@gmail.com"), EmailSuggestStore.rank("RFG59419@g", entries))
        assertEquals(emptyList<String>(), EmailSuggestStore.rank("rfg59419@gmail.com", entries))
        assertEquals(emptyList<String>(), EmailSuggestStore.rank("zz", entries))
    }

    @Test
    fun rankCapsTheCandidateCount() {
        val many = (1..10).map { Triple("rfg$it@a.com", it, it.toLong()) }
        assertEquals(3, EmailSuggestStore.rank("rfg", many).size)
        assertEquals("rfg10@a.com", EmailSuggestStore.rank("rfg", many).first())
    }
}
