package com.therealityteam.aeropages.editor

import com.therealityteam.aeropages.editor.FormulaEngine.FormulaError
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The worksheet functions that work on plain numbers, as Excel defines them: rounding, combinatorics,
 * statistics, the normal distribution, loans and investments, depreciation, and date arithmetic. A value
 * out of a function's reach is the error Excel gives (#NUM!, #DIV/0!, #N/A, #VALUE!).
 */
internal object FormulaMath {

    private fun num(): Nothing = throw FormulaError("#NUM!")
    private fun div0(): Nothing = throw FormulaError("#DIV/0!")

    // --- rounding ------------------------------------------------------------------------------

    /** [x] as Excel holds it: to fifteen significant digits (1.005 is 1.005, not 1.00499999999999989…). */
    fun decimal(x: Double): BigDecimal = if (x == 0.0) BigDecimal.ZERO else BigDecimal(x).round(MathContext(15))

    /** [x] to [digits] decimal places (negative: tens, hundreds …) by [mode], on its fifteen-digit value. */
    fun round(x: Double, digits: Int, mode: RoundingMode): Double = decimal(x).setScale(digits.coerceIn(-308, 340), mode).toDouble()

    /** [x] divided by [step] to a whole number by [mode], on the fifteen-digit quotient (0.15 / 0.1 is 1.5). */
    private fun steps(x: Double, step: Double, mode: RoundingMode): Double = decimal(x / step).setScale(0, mode).toDouble()

    /** MROUND: the multiple of [m] nearest [n], halves away from 0; the two must share a sign. */
    fun mround(n: Double, m: Double): Double {
        if (m == 0.0 || n == 0.0) return 0.0
        if ((n > 0) != (m > 0)) num()
        return steps(n, m, RoundingMode.HALF_UP) * m
    }

    /** CEILING: up to a multiple of [sig] — away from 0 when both are negative, as Excel 2010 on. */
    fun ceiling(x: Double, sig: Double): Double = when {
        sig == 0.0 || x == 0.0 -> 0.0
        x > 0 && sig < 0 -> num()
        else -> steps(x, sig, RoundingMode.CEILING.takeIf { x >= 0 || sig > 0 } ?: RoundingMode.UP) * sig
    }

    /** FLOOR: down to a multiple of [sig] — toward 0 when both are negative. */
    fun floor(x: Double, sig: Double): Double = when {
        x == 0.0 -> 0.0
        sig == 0.0 -> div0()
        x > 0 && sig < 0 -> num()
        else -> steps(x, sig, if (x >= 0 || sig > 0) RoundingMode.FLOOR else RoundingMode.DOWN) * sig
    }

    /** CEILING.MATH (and CEILING.PRECISE, ISO.CEILING without [mode]): up to a multiple of |[sig]|; a negative number with [mode] goes away from 0. */
    fun ceilingMath(x: Double, sig: Double, mode: Double): Double {
        val s = abs(sig)
        if (s == 0.0 || x == 0.0) return 0.0
        return steps(x, s, if (x < 0 && mode != 0.0) RoundingMode.FLOOR else RoundingMode.CEILING) * s
    }

    /** FLOOR.MATH (and FLOOR.PRECISE): down to a multiple of |[sig]|; a negative number with [mode] goes toward 0. */
    fun floorMath(x: Double, sig: Double, mode: Double): Double {
        val s = abs(sig)
        if (s == 0.0 || x == 0.0) return 0.0
        return steps(x, s, if (x < 0 && mode != 0.0) RoundingMode.CEILING else RoundingMode.FLOOR) * s
    }

    /** EVEN: away from 0 to an even whole number. */
    fun even(x: Double): Double { val a = kotlin.math.ceil(decimal(abs(x)).toDouble() / 2) * 2; return if (x < 0) -a else a }

    /** ODD: away from 0 to an odd whole number (ODD(0) is 1). */
    fun odd(x: Double): Double {
        val a = decimal(abs(x)).toDouble()
        var o = kotlin.math.ceil(a)
        if (o % 2 == 0.0) o += 1
        return if (x < 0) -o else o
    }

    // --- whole numbers -----------------------------------------------------------------------------

    fun fact(n: Double): Double {
        if (n < 0) num()
        val k = n.toLong()
        if (k > 170) num()
        var f = 1.0
        for (i in 2..k) f *= i
        return f
    }

    fun combin(n: Double, k: Double): Double {
        val nn = n.toLong(); val kk = k.toLong()
        if (nn < 0 || kk < 0 || nn < kk) num()
        var r = 1.0
        for (i in 1..minOf(kk, nn - kk)) r = r * (nn - minOf(kk, nn - kk) + i) / i
        return kotlin.math.round(r)
    }

    fun permut(n: Double, k: Double): Double {
        val nn = n.toLong(); val kk = k.toLong()
        if (nn < 0 || kk < 0 || nn < kk) num()
        var r = 1.0
        for (i in 0 until kk) r *= (nn - i)
        return r
    }

    private fun gcd2(a: Long, b: Long): Long = if (b == 0L) a else gcd2(b, a % b)

    fun gcd(xs: List<Double>): Double {
        if (xs.any { it < 0 }) num()
        return xs.fold(0L) { g, x -> gcd2(g, x.toLong()) }.toDouble()
    }

    fun lcm(xs: List<Double>): Double {
        if (xs.any { it < 0 }) num()
        if (xs.any { it.toLong() == 0L }) return 0.0
        return xs.fold(1L) { l, x -> val v = x.toLong(); l / gcd2(l, v) * v }.toDouble()
    }

    // --- statistics ---------------------------------------------------------------------------

    fun average(v: List<Double>): Double = if (v.isEmpty()) div0() else v.sum() / v.size

    fun median(v: List<Double>): Double {
        if (v.isEmpty()) num()
        val s = v.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /** MODE (MODE.SNGL): the value repeated most, the first of them to repeat; #N/A when none repeats. */
    fun mode(v: List<Double>): Double {
        val counts = LinkedHashMap<Double, Int>()
        v.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val best = counts.maxByOrNull { it.value } ?: throw FormulaError("#N/A")
        if (best.value < 2) throw FormulaError("#N/A")
        return best.key
    }

    fun variance(v: List<Double>, sample: Boolean): Double {
        val n = v.size
        if (n == 0 || (sample && n < 2)) div0()
        val mean = v.average()
        return v.sumOf { (it - mean) * (it - mean) } / (if (sample) n - 1 else n)
    }

    fun devsq(v: List<Double>): Double { if (v.isEmpty()) num(); val m = v.average(); return v.sumOf { (it - m) * (it - m) } }

    fun avedev(v: List<Double>): Double { if (v.isEmpty()) num(); val m = v.average(); return v.sumOf { abs(it - m) } / v.size }

    fun geomean(v: List<Double>): Double { if (v.isEmpty() || v.any { it <= 0 }) num(); return exp(v.sumOf { ln(it) } / v.size) }

    fun harmean(v: List<Double>): Double { if (v.isEmpty() || v.any { it <= 0 }) num(); return v.size / v.sumOf { 1 / it } }

    /** PERCENTILE.INC: the [k]-th percentile (0…1), interpolating between the sorted values. */
    fun percentileInc(v: List<Double>, k: Double): Double {
        if (v.isEmpty() || k < 0 || k > 1) num()
        val s = v.sorted()
        val rank = k * (s.size - 1)
        val lo = floor(rank).toInt()
        return if (lo + 1 >= s.size) s[lo] else s[lo] + (rank - lo) * (s[lo + 1] - s[lo])
    }

    /** PERCENTILE.EXC: [k] strictly inside (0, 1) and within the values' reach. */
    fun percentileExc(v: List<Double>, k: Double): Double {
        val s = v.sorted(); val n = s.size
        if (n == 0 || k <= 0 || k >= 1) num()
        val rank = k * (n + 1)
        if (rank < 1 || rank > n) num()
        val lo = floor(rank).toInt()
        return if (lo >= n) s[n - 1] else s[lo - 1] + (rank - lo) * (s[lo] - s[lo - 1])
    }

    /** RANK.AVG: [x]'s rank in [v], ties sharing their average rank; #N/A when it is not among them. */
    fun rankAvg(x: Double, v: List<Double>, ascending: Boolean): Double {
        val s = if (ascending) v.sorted() else v.sortedDescending()
        val first = s.indexOfFirst { it == x }
        if (first < 0) throw FormulaError("#N/A")
        val last = s.indexOfLast { it == x }
        return (first + last) / 2.0 + 1
    }

    /** The pairs of [ys] and [xs] where both are numbers; #N/A when the two differ in size. */
    fun pairs(ys: List<Any>, xs: List<Any>): List<Pair<Double, Double>> {
        if (ys.size != xs.size) throw FormulaError("#N/A")
        return ys.indices.mapNotNull { i -> val y = ys[i]; val x = xs[i]; if (y is Double && x is Double) y to x else null }
    }

    private class Fit(val slope: Double, val intercept: Double, val r: Double)

    private fun fit(p: List<Pair<Double, Double>>): Fit {
        if (p.size < 2) div0()
        val my = p.sumOf { it.first } / p.size; val mx = p.sumOf { it.second } / p.size
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((y, x) in p) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
        if (sxx == 0.0) div0()
        return Fit(sxy / sxx, my - sxy / sxx * mx, if (syy == 0.0) Double.NaN else sxy / sqrt(sxx * syy))
    }

    fun slope(p: List<Pair<Double, Double>>) = fit(p).slope
    fun intercept(p: List<Pair<Double, Double>>) = fit(p).intercept
    fun correl(p: List<Pair<Double, Double>>): Double = fit(p).r.takeIf { !it.isNaN() } ?: div0()
    fun forecast(x: Double, p: List<Pair<Double, Double>>): Double = fit(p).let { it.intercept + it.slope * x }

    fun covariance(p: List<Pair<Double, Double>>, sample: Boolean): Double {
        if (p.isEmpty() || (sample && p.size < 2)) div0()
        val my = p.sumOf { it.first } / p.size; val mx = p.sumOf { it.second } / p.size
        return p.sumOf { (y, x) -> (x - mx) * (y - my) } / (if (sample) p.size - 1 else p.size)
    }

    // --- the normal distribution ------------------------------------------------------------------

    /** The error function, to about 1e-15 (a series near 0, a continued fraction beyond). */
    private fun erfc(x: Double): Double {
        val z = abs(x)
        val r = if (z < 2.5) {
            // erf by its Taylor series, then 1 − erf.
            var sum = z; var term = z; var n = 0
            while (abs(term) > 1e-17 * abs(sum)) { n++; term *= -z * z / n; sum += term / (2 * n + 1) }
            1 - 2 / sqrt(Math.PI) * sum
        } else {
            // Lentz's continued fraction for erfc.
            var f = z; var c = z; var d = 0.0
            for (k in 1..300) {
                val a = k / 2.0
                d = z + a * d; d = if (d == 0.0) 1e-300 else 1 / d
                c = z + a / c; if (c == 0.0) c = 1e-300
                val delta = c * d; f *= delta
                if (abs(delta - 1) < 1e-16) break
            }
            exp(-z * z) / sqrt(Math.PI) / f
        }
        return if (x >= 0) r else 2 - r
    }

    /** NORM.S.DIST: the standard normal's cumulative distribution, or its density. */
    fun normS(z: Double, cumulative: Boolean): Double =
        if (cumulative) 0.5 * erfc(-z / sqrt(2.0)) else exp(-z * z / 2) / sqrt(2 * Math.PI)

    /** NORM.S.INV: the z whose cumulative probability is [p] (Acklam's approximation, then a Newton step). */
    fun normSInv(p: Double): Double {
        if (p <= 0 || p >= 1) num()
        val a = doubleArrayOf(-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00)
        val b = doubleArrayOf(-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01)
        val c = doubleArrayOf(-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00)
        val d = doubleArrayOf(7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00)
        val lo = 0.02425
        var x = when {
            p < lo -> { val q = sqrt(-2 * ln(p)); (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            p > 1 - lo -> { val q = sqrt(-2 * ln(1 - p)); -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1) }
            else -> { val q = p - 0.5; val r = q * q; (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1) }
        }
        // One Halley step against the exact distribution.
        val e = normS(x, true) - p
        val u = e * sqrt(2 * Math.PI) * exp(x * x / 2)
        x -= u / (1 + x * u / 2)
        return x
    }

    // --- loans and investments ----------------------------------------------------------------------

    private fun growth(rate: Double, nper: Double) = (1 + rate).pow(nper)

    fun fv(rate: Double, nper: Double, pmt: Double, pv: Double, type: Double): Double =
        if (rate == 0.0) -(pv + pmt * nper)
        else -(pv * growth(rate, nper) + pmt * (1 + rate * type) * (growth(rate, nper) - 1) / rate)

    fun pv(rate: Double, nper: Double, pmt: Double, fv: Double, type: Double): Double =
        if (rate == 0.0) -(fv + pmt * nper)
        else -(fv + pmt * (1 + rate * type) * (growth(rate, nper) - 1) / rate) / growth(rate, nper)

    fun pmt(rate: Double, nper: Double, pv: Double, fv: Double, type: Double): Double {
        if (nper == 0.0) num()
        return if (rate == 0.0) -(pv + fv) / nper
        else -(rate * (pv * growth(rate, nper) + fv)) / ((1 + rate * type) * (growth(rate, nper) - 1))
    }

    fun ipmt(rate: Double, per: Double, nper: Double, pv: Double, fv: Double, type: Double): Double {
        if (per < 1 || per > nper) num()
        val p = pmt(rate, nper, pv, fv, type)
        return if (type != 0.0) {
            if (per == 1.0) 0.0 else (fv(rate, per - 2, p, pv, 1.0) - p) * rate
        } else fv(rate, per - 1, p, pv, 0.0) * rate
    }

    fun ppmt(rate: Double, per: Double, nper: Double, pv: Double, fv: Double, type: Double): Double =
        pmt(rate, nper, pv, fv, type) - ipmt(rate, per, nper, pv, fv, type)

    fun nper(rate: Double, pmt: Double, pv: Double, fv: Double, type: Double): Double {
        if (rate == 0.0) { if (pmt == 0.0) num(); return -(pv + fv) / pmt }
        val a = pmt * (1 + rate * type) - fv * rate
        val b = pmt * (1 + rate * type) + pv * rate
        if (a / b <= 0) num()
        return ln(a / b) / ln(1 + rate)
    }

    fun npv(rate: Double, values: List<Double>): Double {
        if (rate == -1.0) div0()
        return values.withIndex().sumOf { (i, v) -> v / (1 + rate).pow(i + 1) }
    }

    /** Newton's method on [f] (with derivative [df]) from [guess]; #NUM! when it does not settle. */
    private fun solve(guess: Double, f: (Double) -> Double, df: (Double) -> Double): Double {
        var x = guess
        repeat(100) {
            val y = f(x); val d = df(x)
            if (d == 0.0 || d.isNaN()) num()
            val next = x - y / d
            if (next.isNaN() || next.isInfinite()) num()
            if (abs(next - x) < 1e-10 * maxOf(1.0, abs(x))) return next
            x = next
        }
        num()
    }

    fun irr(values: List<Double>, guess: Double): Double {
        if (values.none { it > 0 } || values.none { it < 0 }) num()
        return solve(guess, { r -> values.withIndex().sumOf { (i, v) -> v / (1 + r).pow(i) } },
            { r -> values.withIndex().sumOf { (i, v) -> -i * v / (1 + r).pow(i + 1) } })
    }

    fun xnpv(rate: Double, values: List<Double>, dates: List<Double>): Double {
        if (values.size != dates.size || values.isEmpty()) num()
        return values.indices.sumOf { i -> values[i] / (1 + rate).pow((dates[i] - dates[0]) / 365.0) }
    }

    fun xirr(values: List<Double>, dates: List<Double>, guess: Double): Double {
        if (values.size != dates.size || values.none { it > 0 } || values.none { it < 0 }) num()
        return solve(guess, { r -> xnpv(r, values, dates) },
            { r -> values.indices.sumOf { i -> val t = (dates[i] - dates[0]) / 365.0; -t * values[i] / (1 + r).pow(t + 1) } })
    }

    fun rate(nper: Double, pmt: Double, pv: Double, fv: Double, type: Double, guess: Double): Double {
        fun f(r: Double) = if (abs(r) < 1e-12) pv + pmt * nper + fv
            else pv * growth(r, nper) + pmt * (1 + r * type) * (growth(r, nper) - 1) / r + fv
        val h = 1e-7
        return solve(guess, ::f) { r -> (f(r + h) - f(r - h)) / (2 * h) }
    }

    fun sln(cost: Double, salvage: Double, life: Double): Double { if (life == 0.0) div0(); return (cost - salvage) / life }

    fun syd(cost: Double, salvage: Double, life: Double, per: Double): Double {
        if (life <= 0 || per <= 0 || per > life) num()
        return (cost - salvage) * (life - per + 1) * 2 / (life * (life + 1))
    }

    /** DDB: double-declining balance (or [factor]-declining) for period [period], never below the salvage value. */
    fun ddb(cost: Double, salvage: Double, life: Double, period: Double, factor: Double): Double {
        if (cost < 0 || salvage < 0 || life <= 0 || period <= 0 || period > life || factor <= 0) num()
        var total = 0.0
        var dep = 0.0
        var p = 1.0
        while (p <= period) {
            dep = minOf((cost - total) * factor / life, maxOf(0.0, cost - salvage - total))
            total += dep
            p++
        }
        return dep
    }

    /** DB: fixed-declining balance, its rate rounded to three places as Excel's; [month] in the first year. */
    fun db(cost: Double, salvage: Double, life: Double, period: Double, month: Double): Double {
        if (cost < 0 || salvage < 0 || life <= 0 || period <= 0 || month < 1 || month > 12 || period > life + 1) num()
        if (cost == 0.0) return 0.0
        val rate = round(1 - (salvage / cost).pow(1 / life), 3, RoundingMode.HALF_UP)
        var total = cost * rate * month / 12
        if (period.toInt() == 1) return total
        for (p in 2 until period.toInt()) total += (cost - total) * rate
        return if (period.toInt().toDouble() == life + 1) (cost - total) * rate * (12 - month) / 12 else (cost - total) * rate
    }

    fun effect(nominal: Double, npery: Double): Double {
        val n = npery.toLong()
        if (nominal <= 0 || n < 1) num()
        return (1 + nominal / n).pow(n.toDouble()) - 1
    }

    fun nominal(effect: Double, npery: Double): Double {
        val n = npery.toLong()
        if (effect <= 0 || n < 1) num()
        return n * ((1 + effect).pow(1.0 / n) - 1)
    }

    // --- dates ----------------------------------------------------------------------------------

    private val EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    fun date(serial: Double): LocalDate { if (serial < 0) num(); return EPOCH.plusDays(floor(serial).toLong()) }
    fun serial(d: LocalDate): Double = ChronoUnit.DAYS.between(EPOCH, d).toDouble()

    /** WEEKDAY's number for [d] by [type]: 1 Sunday=1, 2 Monday=1, 3 Monday=0, 11–17 Monday…Sunday=1. */
    fun weekday(d: LocalDate, type: Int): Double {
        val iso = d.dayOfWeek.value   // Monday 1 … Sunday 7
        fun from(first: Int) = ((iso - first + 7) % 7 + 1).toDouble()
        return when (type) {
            1, 17 -> from(7)
            2, 11 -> from(1)
            3 -> from(1) - 1
            in 12..16 -> from(type - 10)
            else -> num()
        }
    }

    /** WEEKNUM: the week of the year [d] is in, weeks starting on the day [type] names (21: ISO weeks). */
    fun weeknum(d: LocalDate, type: Int): Double {
        if (type == 21) return d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toDouble()
        val first = when (type) { 1, 17 -> 7; 2, 11 -> 1; in 12..16 -> type - 10; else -> num() }
        val jan1 = d.withDayOfYear(1)
        val offset = (jan1.dayOfWeek.value - first + 7) % 7
        return ((d.dayOfYear - 1 + offset) / 7 + 1).toDouble()
    }

    private fun lastOfFeb(d: LocalDate) = d.monthValue == 2 && d.dayOfMonth == d.lengthOfMonth()

    private fun thirty(a: LocalDate, b: LocalDate, d1: Int, d2: Int): Double =
        ((b.year - a.year) * 360 + (b.monthValue - a.monthValue) * 30 + (d2 - d1)).toDouble()

    /** YEARFRAC's US 30/360 days (basis 0), with Excel's own rules for month ends and the end of February. */
    private fun days360Basis0(a: LocalDate, b: LocalDate): Double {
        var d1 = a.dayOfMonth; var d2 = b.dayOfMonth
        when {
            d1 == 31 && d2 == 31 -> { d1 = 30; d2 = 30 }
            d1 == 31 -> d1 = 30
            d1 == 30 && d2 == 31 -> d2 = 30
            lastOfFeb(a) -> { d1 = 30; if (lastOfFeb(b)) d2 = 30 }
        }
        return thirty(a, b, d1, d2)
    }

    /** DAYS360: days between [a] and [b] with every month 30 days — the US (NASD) method as Excel's, or the European one. */
    fun days360(a: LocalDate, b: LocalDate, european: Boolean): Double {
        var d1 = a.dayOfMonth; var d2 = b.dayOfMonth
        if (european) { if (d1 == 31) d1 = 30; if (d2 == 31) d2 = 30; return thirty(a, b, d1, d2) }
        // A start on its month's last day is the 30th; an end on the 31st from before the 30th is the next month's 1st.
        if (d1 == a.lengthOfMonth()) d1 = 30
        var end = b
        if (d2 == 31) { if (d1 < 30) { end = b.plusDays(1); d2 = 1 } else d2 = 30 }
        return thirty(a, end, d1, d2)
    }

    /** YEARFRAC: the fraction of a year from [s0] to [e0] by [basis] (0 US 30/360, 1 actual/actual, 2 actual/360, 3 actual/365, 4 European 30/360). */
    fun yearFrac(s0: LocalDate, e0: LocalDate, basis: Int): Double {
        val (s, e) = if (s0.isAfter(e0)) e0 to s0 else s0 to e0
        val days = ChronoUnit.DAYS.between(s, e).toDouble()
        return when (basis) {
            0 -> days360Basis0(s, e) / 360
            2 -> days / 360
            3 -> days / 365
            4 -> days360(s, e, european = true) / 360
            1 -> {
                val moreThanAYear = s.year != e.year && (s.year + 1 != e.year || s.monthValue < e.monthValue || (s.monthValue == e.monthValue && s.dayOfMonth < e.dayOfMonth))
                val den = when {
                    moreThanAYear -> (s.year..e.year).sumOf { y -> if (java.time.Year.isLeap(y.toLong())) 366.0 else 365.0 } / (e.year - s.year + 1)
                    countsFeb29(s, e) -> 366.0
                    else -> 365.0
                }
                days / den
            }
            else -> num()
        }
    }

    /** Whether a span of at most a year counts a 29 February, as Excel's actual/actual decides it. */
    private fun countsFeb29(s: LocalDate, e: LocalDate): Boolean {
        if (s.isLeapYear) return s.year == e.year || s.monthValue <= 2
        if (e.isLeapYear) return when (e.monthValue) { 1 -> false; 2 -> e.dayOfMonth == 29; else -> true }
        return false
    }

    /** Whether [d] is a weekend day by NETWORKDAYS.INTL's [weekend]: a number (1 Saturday+Sunday … 7, 11–17 one day) or seven 0/1 flags from Monday. */
    fun weekendOf(weekend: Any?): (LocalDate) -> Boolean {
        val days: Set<DayOfWeek> = when (weekend) {
            null -> setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            is String -> {
                if (weekend.length != 7 || weekend.any { it != '0' && it != '1' } || weekend == "1111111") throw FormulaError("#VALUE!")
                weekend.indices.filter { weekend[it] == '1' }.map { DayOfWeek.of(it + 1) }.toSet()
            }
            is Double -> when (val k = weekend.toInt()) {
                in 1..7 -> setOf(DayOfWeek.of((k + 4) % 7 + 1), DayOfWeek.of((k + 5) % 7 + 1))
                in 11..17 -> setOf(DayOfWeek.of((k - 11 + 6) % 7 + 1))
                else -> num()
            }
            else -> num()
        }
        return { d -> d.dayOfWeek in days }
    }

    fun networkDays(a: LocalDate, b: LocalDate, weekend: (LocalDate) -> Boolean, holidays: Set<LocalDate>): Double {
        val (s, e, sign) = if (b.isBefore(a)) Triple(b, a, -1.0) else Triple(a, b, 1.0)
        var n = 0
        var d = s
        while (!d.isAfter(e)) { if (!weekend(d) && d !in holidays) n++; d = d.plusDays(1) }
        return sign * n
    }

    fun workday(start: LocalDate, days: Int, weekend: (LocalDate) -> Boolean, holidays: Set<LocalDate>): LocalDate {
        var d = start; var left = days
        val step = if (days < 0) -1L else 1L
        while (left != 0) { d = d.plusDays(step); if (!weekend(d) && d !in holidays) left -= step.toInt() }
        return d
    }
}
