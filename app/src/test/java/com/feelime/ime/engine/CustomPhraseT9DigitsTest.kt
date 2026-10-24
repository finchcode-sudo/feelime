package com.feelime.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomPhraseT9DigitsTest {
    @Test
    fun lettersMapToT9Digits() {
        assertEquals("43234224482", CustomPhraseStore.t9Digits("hebeicaihua"))
        assertEquals("5", CustomPhraseStore.t9Digits("j"))
        assertEquals("7777", CustomPhraseStore.t9Digits("pqrs"))
        assertEquals("9999", CustomPhraseStore.t9Digits("wxyz"))
    }

    @Test
    fun nonLowercaseLettersAreRejected() {
        assertNull(CustomPhraseStore.t9Digits(""))
        assertNull(CustomPhraseStore.t9Digits("a;b"))
        assertNull(CustomPhraseStore.t9Digits("HE"))
        assertNull(CustomPhraseStore.t9Digits("a2"))
    }
}
