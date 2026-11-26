package com.feelime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 手写重排：先验解析、笔数扣分、常用度和笔画数怎样改变候选顺序。 */
class HandwritingRerankTest {
    private val priors = HandwritingRerank.parsePriors(
        sequenceOf(
            "你\t972978\t7",
            "个\t246257\t3",
            "仆\t821\t4",
            "不\t590801\t4",
            "斥\t996\t5",
            "坏行没有制表符",
            "x\tabc\t3",
            "\t5\t5",
        ),
    )

    private fun cands(vararg pairs: Pair<String, Float>) = pairs.map { InkCandidate(it.first, it.second) }
    private fun texts(list: List<InkCandidate>) = list.map { it.text }

    @Test
    fun parsePriorsKeepsGoodRowsAndSkipsBadOnes() {
        assertEquals(5, priors.size)
        assertEquals(CharPrior(972978, 7), priors["你"])
    }

    @Test
    fun strokePenaltyToleratesOneStrokeEitherWay() {
        assertEquals(0f, HandwritingRerank.strokePenalty(7, 7), 0f)
        assertEquals(0f, HandwritingRerank.strokePenalty(6, 7), 0f)
        assertEquals(0f, HandwritingRerank.strokePenalty(8, 7), 0f)
        // 少写 4 笔（超出容差 3 笔）、多写 3 笔（超出容差 2 笔）。
        assertEquals(3 * HandwritingRerank.MISSING_STROKE_PENALTY, HandwritingRerank.strokePenalty(3, 7), 1e-6f)
        assertEquals(2 * HandwritingRerank.EXTRA_STROKE_PENALTY, HandwritingRerank.strokePenalty(10, 7), 1e-6f)
        // 笔数或标准笔画数不明不扣分。
        assertEquals(0f, HandwritingRerank.strokePenalty(0, 7), 0f)
        assertEquals(0f, HandwritingRerank.strokePenalty(7, 0), 0f)
    }

    @Test
    fun adjustmentIsClampedSoPriorsCannotOverrideAConfidentModel() {
        val huge = CharPrior(Int.MAX_VALUE, 3)
        assertTrue(HandwritingRerank.adjustment(huge, 3) <= HandwritingRerank.MAX_SHIFT)
        val harsh = CharPrior(1, 30)
        assertEquals(-HandwritingRerank.MAX_SHIFT, HandwritingRerank.adjustment(harsh, 2), 0f)
    }

    @Test
    fun strokeCountAndFrequencyPullTheRightCharUpFromTheModelsWrongTop() {
        // 截图里的情形：模型把潦草的「你」排在「个/仆/不」后面，写了 7 笔。
        val model = cands("个" to 0.30f, "仆" to 0.25f, "不" to 0.20f, "斥" to 0.10f, "你" to 0.05f)
        val out = HandwritingRerank.rerank(model, writtenStrokes = 7, priors = priors)
        assertEquals("你", out.first().text)
        // 返回的 score 仍是模型原概率，不是重排分。
        assertEquals(0.05f, out.first().score, 0f)
    }

    @Test
    fun fewStrokesStillFavourTheShortChar() {
        // 只写了 3 笔：「个」应保持第一，「你」不会靠先验翻上来。
        val model = cands("个" to 0.50f, "你" to 0.05f)
        assertEquals(listOf("个", "你"), texts(HandwritingRerank.rerank(model, 3, priors)))
    }

    @Test
    fun aConfidentModelIsNotOverriddenByWeakPriors() {
        // 概率差 1000 倍（ln ≈ 6.9）超过最大调整量，先验推不动。
        val model = cands("斥" to 0.9f, "你" to 0.0009f)
        assertEquals("斥", HandwritingRerank.rerank(model, 7, priors).first().text)
    }

    @Test
    fun withoutPriorsOrWithOneCandidateTheOrderIsUntouchedAndTruncated() {
        val model = cands("个" to 0.5f, "仆" to 0.3f, "不" to 0.2f)
        assertEquals(listOf("个", "仆"), texts(HandwritingRerank.rerank(model, 7, emptyMap(), limit = 2)))
        assertEquals(listOf("个"), texts(HandwritingRerank.rerank(model.take(1), 7, priors)))
    }

    @Test
    fun unknownCharsAreNotPenalisedByStrokesAndGetTheFrequencyFloor() {
        val prior = priors["你"]
        val unknown = HandwritingRerank.adjustment(null, 7)
        assertEquals(0f, unknown, 1e-6f)
        assertTrue(HandwritingRerank.adjustment(prior, 7) > unknown)
    }

    @Test
    fun outputIsCappedAtTheLimit() {
        val many = (1..40).map { InkCandidate("字$it", 1f / (it + 1)) }
        assertEquals(HandwritingRerank.OUTPUT_LIMIT, HandwritingRerank.rerank(many, 5, priors).size)
    }
}
