package com.hanzidictionary.core

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * FSRS-6 — the Free Spaced Repetition Scheduler (Open Spaced Repetition, MIT), the
 * scheduler Anki has defaulted to since 23.10. It models memory as three quantities:
 *
 *  - **stability** (S): days until recall probability decays to 90%,
 *  - **difficulty** (D): 1..10, how fast this item loses stability per review,
 *  - **retrievability** (R): probability of recall right now.
 *
 * Chosen over SM-2/Leitner because it is fitted to ~10k users' review histories and needs
 * roughly 20-30% fewer reviews for the same retention (spec §17). Implemented here from
 * the published formulas rather than taken from a library: it is pure arithmetic, the
 * `core` module deliberately carries no dependencies, and the community Kotlin port ships
 * no license file. Cross-checked against the reference implementation py-fsrs by the
 * golden interval sequence in FsrsTest.
 */
enum class Rating(val value: Int) {
    /** Forgot it — the only rating that counts as a lapse. */
    AGAIN(1),
    HARD(2),
    GOOD(3),
    EASY(4),
    ;

    companion object {
        fun of(value: Int): Rating = entries.first { it.value == value }
    }
}

/** Where a card sits in the short-term step ladder. Review = scheduled in days. */
enum class CardPhase { LEARNING, REVIEW, RELEARNING }

/**
 * One item's scheduling state. [stability]/[difficulty] are null until the first review.
 * Times are epoch millis. [reviews] counts every grading, [lapses] only the AGAIN ones —
 * the "knew this"/"didn't know" counters the practice UI shows as recalled/missed.
 */
data class CardState(
    val itemId: String,
    val kind: String,
    val phase: CardPhase = CardPhase.LEARNING,
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long,
    val lastReview: Long? = null,
    val reviews: Int = 0,
    val lapses: Int = 0,
) {
    val recalled: Int get() = reviews - lapses
}

private const val MINUTE_MS = 60_000L
internal const val DAY_MS = 86_400_000L

class Fsrs(
    private val parameters: DoubleArray = DEFAULT_PARAMETERS,
    private val desiredRetention: Double = 0.9,
    /** Short-term steps a new card walks before it earns a day-scale interval. */
    private val learningSteps: List<Long> = listOf(MINUTE_MS, 10 * MINUTE_MS),
    private val relearningSteps: List<Long> = listOf(10 * MINUTE_MS),
    private val maximumIntervalDays: Long = 36_500L,
    /** Spreads same-day-scheduled cards so a big import doesn't come back as one lump. */
    private val enableFuzz: Boolean = true,
    private val random: Random = Random.Default,
) {
    private val decay = -parameters[20]

    /** Chosen so R(t = S) is exactly 0.9 — that identity is what makes S "days to 90%". */
    private val factor = 0.9.pow(1.0 / decay) - 1.0

    /** Probability the user still recalls this item at [now]; 0 for a never-studied card. */
    fun retrievability(card: CardState, now: Long): Double {
        val last = card.lastReview ?: return 0.0
        val stability = card.stability ?: return 0.0
        val elapsedDays = elapsedDays(last, now)
        return (1.0 + factor * elapsedDays / stability).pow(decay)
    }

    /** Applies a grading and returns the rescheduled card. */
    fun review(card: CardState, rating: Rating, now: Long): CardState {
        val daysSinceLastReview = card.lastReview?.let { elapsedDays(it, now) }
        var phase = card.phase
        var step = card.step
        var stability = card.stability
        var difficulty = card.difficulty
        val interval: Long

        // Stability/difficulty first: the interval is derived from the updated memory
        // state, and a same-day repeat uses the short-term formula instead of the
        // forgetting-curve one (no measurable decay has happened yet).
        fun updateMemory() {
            val s = stability
            val d = difficulty
            if (s == null || d == null) {
                stability = initialStability(rating)
                difficulty = initialDifficulty(rating, clamp = true)
            } else if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                stability = shortTermStability(s, rating)
                difficulty = nextDifficulty(d, rating)
            } else {
                stability = nextStability(d, s, retrievability(card, now), rating)
                difficulty = nextDifficulty(d, rating)
            }
        }

        when (card.phase) {
            CardPhase.LEARNING, CardPhase.RELEARNING -> {
                updateMemory()
                val steps = if (card.phase == CardPhase.LEARNING) learningSteps else relearningSteps
                val currentStep = step ?: 0
                if (steps.isEmpty() || (currentStep >= steps.size && rating != Rating.AGAIN)) {
                    phase = CardPhase.REVIEW
                    step = null
                    interval = reviewInterval(stability!!)
                } else {
                    when (rating) {
                        Rating.AGAIN -> {
                            step = 0
                            interval = steps[0]
                        }
                        Rating.HARD -> interval = when {
                            currentStep > 0 -> steps[currentStep]
                            steps.size == 1 -> (steps[0] * 1.5).toLong()
                            else -> (steps[0] + steps[1]) / 2
                        }
                        Rating.GOOD -> if (currentStep + 1 == steps.size) {
                            phase = CardPhase.REVIEW
                            step = null
                            interval = reviewInterval(stability!!)
                        } else {
                            step = currentStep + 1
                            interval = steps[currentStep + 1]
                        }
                        Rating.EASY -> {
                            phase = CardPhase.REVIEW
                            step = null
                            interval = reviewInterval(stability!!)
                        }
                    }
                }
            }
            CardPhase.REVIEW -> {
                val s = stability!!
                val d = difficulty!!
                stability = if (daysSinceLastReview != null && daysSinceLastReview < 1) {
                    shortTermStability(s, rating)
                } else {
                    nextStability(d, s, retrievability(card, now), rating)
                }
                difficulty = nextDifficulty(d, rating)

                if (rating == Rating.AGAIN && relearningSteps.isNotEmpty()) {
                    phase = CardPhase.RELEARNING
                    step = 0
                    interval = relearningSteps[0]
                } else {
                    interval = reviewInterval(stability!!)
                }
            }
        }

        return card.copy(
            phase = phase,
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = now + interval,
            lastReview = now,
            reviews = card.reviews + 1,
            lapses = card.lapses + if (rating == Rating.AGAIN) 1 else 0,
        )
    }

    /** Day-scale interval in millis, fuzzed (only Review-state cards ever get one). */
    private fun reviewInterval(stability: Double): Long {
        val days = nextIntervalDays(stability)
        return (if (enableFuzz) fuzzDays(days) else days) * DAY_MS
    }

    /** Days until R falls to [desiredRetention]; equals stability at the default 0.9. */
    fun nextIntervalDays(stability: Double): Long {
        val raw = (stability / factor) * (desiredRetention.pow(1.0 / decay) - 1.0)
        return raw.roundToLong().coerceIn(1L, maximumIntervalDays)
    }

    private fun initialStability(rating: Rating): Double =
        parameters[rating.value - 1].coerceAtLeast(STABILITY_MIN)

    private fun initialDifficulty(rating: Rating, clamp: Boolean): Double {
        val d = parameters[4] - exp(parameters[5] * (rating.value - 1)) + 1.0
        return if (clamp) d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY) else d
    }

    private fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val deltaDifficulty = -(parameters[6] * (rating.value - 3))
        // Linear damping: the closer D already is to 10, the less a bad grade moves it.
        val damped = difficulty + (10.0 - difficulty) * deltaDifficulty / 9.0
        // Mean reversion pulls D back toward the easy-default, so a card graded Good
        // forever converges instead of drifting.
        val reverted = parameters[7] * initialDifficulty(Rating.EASY, clamp = false) +
            (1 - parameters[7]) * damped
        return reverted.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }

    private fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = exp(parameters[17] * (rating.value - 3 + parameters[18])) *
            stability.pow(-parameters[19])
        if (rating == Rating.GOOD || rating == Rating.EASY) increase = increase.coerceAtLeast(1.0)
        return (stability * increase).coerceAtLeast(STABILITY_MIN)
    }

    private fun nextStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val next = if (rating == Rating.AGAIN) {
            forgetStability(difficulty, stability, retrievability)
        } else {
            recallStability(difficulty, stability, retrievability, rating)
        }
        return next.coerceAtLeast(STABILITY_MIN)
    }

    private fun forgetStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
    ): Double {
        val longTerm = parameters[11] *
            difficulty.pow(-parameters[12]) *
            ((stability + 1.0).pow(parameters[13]) - 1.0) *
            exp((1.0 - retrievability) * parameters[14])
        val shortTerm = stability / exp(parameters[17] * parameters[18])
        return minOf(longTerm, shortTerm)
    }

    /** Stability grows less for hard material, less again for already-stable material,
     * and most when recall was a close call (low R) — the spacing effect, quantified. */
    private fun recallStability(
        difficulty: Double,
        stability: Double,
        retrievability: Double,
        rating: Rating,
    ): Double {
        val hardPenalty = if (rating == Rating.HARD) parameters[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) parameters[16] else 1.0
        return stability * (
            1.0 + exp(parameters[8]) *
                (11.0 - difficulty) *
                stability.pow(-parameters[9]) *
                (exp((1.0 - retrievability) * parameters[10]) - 1.0) *
                hardPenalty *
                easyBonus
            )
    }

    private fun fuzzDays(intervalDays: Long): Long {
        if (intervalDays < 3) return intervalDays
        var delta = 1.0
        for ((start, end, f) in FUZZ_RANGES) {
            delta += f * maxOf(minOf(intervalDays.toDouble(), end) - start, 0.0)
        }
        val min = (intervalDays - delta).roundToLong().coerceAtLeast(2L)
        val max = (intervalDays + delta).roundToLong().coerceAtMost(maximumIntervalDays)
        return random.nextLong(min, maxOf(min, max) + 1)
    }

    private fun elapsedDays(from: Long, to: Long): Long = ((to - from) / DAY_MS).coerceAtLeast(0L)

    companion object {
        /** FSRS-6 defaults, optimised over several hundred million reviews from ~10k
         * users. Used verbatim: personalising them needs the optimiser, which is v2. */
        val DEFAULT_PARAMETERS = doubleArrayOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666,
            0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658,
            0.1542,
        )

        private const val STABILITY_MIN = 0.001
        private const val MIN_DIFFICULTY = 1.0
        private const val MAX_DIFFICULTY = 10.0

        /** (start, end, factor) — fuzz widens with the interval, capped at ±5% out long. */
        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.MAX_VALUE, 0.05),
        )
    }
}
