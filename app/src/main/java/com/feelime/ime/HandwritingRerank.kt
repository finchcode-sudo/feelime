package com.feelime.ime

import kotlin.math.ln
import kotlin.math.max

/** 一个字的先验：常用度权重（essay 词频）和标准笔画数。 */
data class CharPrior(val weight: Int, val strokes: Int)

/**
 * 手写候选重排（docs/design/handwriting.md §14）。
 *
 * 模型只看笔迹画成的位图，不知道写了几笔，也不知道哪些字常用，所以会把写得散的
 * "你"排成"个/仆/不"，或者把常用字压在生僻形近字后面。这里在模型前 [POOL] 个候选上
 * 加两个先验再排序：
 *  - 常用度：+ [FREQUENCY_ALPHA] × ln(常用度)，形近时常用字靠前；
 *  - 笔画数：写的笔数和标准笔画数差得远就扣分。连笔会让笔数偏少、断笔会让笔数偏多，
 *    所以各留 [STROKE_TOLERANCE] 笔的容差，少写的扣得比多写的轻一点。
 * 两项合计的调整量限制在 ±[MAX_SHIFT] nat 内：模型概率相差太悬殊的候选，先验推不动。
 *
 * 所有参数都是起始值（频率系数和 CyIME 的调参结果同量级），要靠真实样本评测后再调，
 * 见 scripts/eval_handwriting.py。纯函数，无 Android 依赖，JVM 单测直接覆盖。
 */
object HandwritingRerank {
    /** 从模型输出里取前多少个进重排。 */
    const val POOL = 30
    /** 重排后最多交给候选条几个。 */
    const val OUTPUT_LIMIT = 20

    const val FREQUENCY_ALPHA = 0.3f
    /** 常用度低于它（或表里没有）的字按它算，生僻字之间不再互相压。 */
    const val FREQUENCY_FLOOR = 20f
    const val STROKE_TOLERANCE = 1
    /** 写的笔数比标准多：每多一笔（超出容差）扣这么多。 */
    const val EXTRA_STROKE_PENALTY = 1.0f
    /** 写的笔数比标准少（连笔）：每少一笔（超出容差）扣这么多。 */
    const val MISSING_STROKE_PENALTY = 0.6f
    const val MAX_SHIFT = 4f

    /** 解析 char_prior.tsv：`字<TAB>常用度<TAB>标准笔画数`，坏行跳过。 */
    fun parsePriors(lines: Sequence<String>): Map<String, CharPrior> {
        val map = HashMap<String, CharPrior>()
        for (line in lines) {
            val parts = line.split('\t')
            if (parts.size < 3 || parts[0].isEmpty()) continue
            val weight = parts[1].toIntOrNull() ?: continue
            val strokes = parts[2].toIntOrNull() ?: continue
            map[parts[0]] = CharPrior(weight, strokes)
        }
        return map
    }

    /** 笔数不符的扣分（≥ 0）。[written] 不明（≤ 0）或标准笔画数未知时不扣。 */
    fun strokePenalty(written: Int, standard: Int): Float {
        if (written <= 0 || standard <= 0) return 0f
        val diff = written - standard
        return if (diff > 0) max(0, diff - STROKE_TOLERANCE) * EXTRA_STROKE_PENALTY
        else max(0, -diff - STROKE_TOLERANCE) * MISSING_STROKE_PENALTY
    }

    /** 先验给的调整量（nat）：常用度加分 − 笔数扣分，限制在 ±[MAX_SHIFT]。 */
    fun adjustment(prior: CharPrior?, writtenStrokes: Int): Float {
        val weight = max(prior?.weight?.toFloat() ?: 0f, FREQUENCY_FLOOR)
        val frequency = FREQUENCY_ALPHA * (ln(weight) - ln(FREQUENCY_FLOOR))
        val penalty = strokePenalty(writtenStrokes, prior?.strokes ?: 0)
        return (frequency - penalty).coerceIn(-MAX_SHIFT, MAX_SHIFT)
    }

    /**
     * 按「ln(模型概率) + 先验调整量」降序。[candidates] 应是模型的前 [POOL] 个（概率降序）；
     * 先验表为空（资源没读到）时原样截断，行为和没有重排一样。返回的 score 仍是模型原概率。
     */
    fun rerank(
        candidates: List<InkCandidate>,
        writtenStrokes: Int,
        priors: Map<String, CharPrior>,
        limit: Int = OUTPUT_LIMIT,
    ): List<InkCandidate> {
        if (priors.isEmpty() || candidates.size < 2) return candidates.take(limit)
        return candidates
            .sortedByDescending { candidate ->
                ln(max(candidate.score, 1e-12f)) + adjustment(priors[candidate.text], writtenStrokes)
            }
            .take(limit)
    }
}
