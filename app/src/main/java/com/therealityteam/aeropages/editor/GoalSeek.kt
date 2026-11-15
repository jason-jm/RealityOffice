package com.therealityteam.aeropages.editor

import kotlin.math.abs

/**
 * Excel's Goal Seek (单变量求解): find the input x for which f(x) reaches [target]. Secant
 * steps from the current value (a numeric derivative for the first one); once a sign change
 * is bracketed, a secant step that leaves the bracket or barely shrinks the error is replaced
 * by bisection, so convergence is guaranteed there. f returning null (a non-numeric result)
 * fails the search. Pure, so it unit-tests without a sheet.
 */
object GoalSeek {
    const val MAX_ITERATIONS = 200
    private const val TOLERANCE = 1e-9

    /** The x that makes f(x) ≈ [target], or null when no solution is found within [MAX_ITERATIONS]. */
    fun solve(f: (Double) -> Double?, target: Double, start: Double): Double? {
        fun g(x: Double): Double? = f(x)?.let { it - target }?.takeIf { it.isFinite() }
        val tol = TOLERANCE * maxOf(1.0, abs(target))
        // Prefer the roundest nearby value that still hits the target (3 rather than 2.999999992).
        fun polished(x: Double): Double {
            for (digits in 0..9) {
                val p = Math.pow(10.0, digits.toDouble())
                val r = Math.rint(x * p) / p
                val y = g(r) ?: continue
                if (abs(y) <= tol) return r
            }
            return x
        }
        var x0 = start
        var y0 = g(x0) ?: return null
        if (abs(y0) <= tol) return polished(x0)
        var x1 = x0 + (if (abs(x0) > 1e-9) abs(x0) * 1e-3 else 1e-3)
        var y1 = g(x1) ?: return null
        var lo: Double? = null   // where g < 0
        var hi: Double? = null   // where g > 0
        fun bracket(x: Double, y: Double) { if (y < 0) lo = x else hi = x }
        bracket(x0, y0); bracket(x1, y1)
        var bisectNext = false
        repeat(MAX_ITERATIONS) {
            if (abs(y1) <= tol) return polished(x1)
            val l = lo; val u = hi
            val dy = y1 - y0
            var next = if (abs(dy) > 1e-300) x1 - y1 * (x1 - x0) / dy else Double.NaN
            if (l != null && u != null) {
                val a = minOf(l, u); val b = maxOf(l, u)
                if (bisectNext || next.isNaN() || next <= a || next >= b) next = (a + b) / 2
            } else if (next.isNaN() || !next.isFinite()) {
                return null
            }
            if (next == x1) next = x1 + (if (x1 == 0.0) 1e-6 else abs(x1) * 1e-6)
            val yNext = g(next) ?: return null
            bracket(next, yNext)
            bisectNext = l != null && u != null && abs(yNext) > 0.5 * abs(y1)
            x0 = x1; y0 = y1; x1 = next; y1 = yNext
        }
        return if (abs(y1) <= tol * 1e3) polished(x1) else null
    }
}
