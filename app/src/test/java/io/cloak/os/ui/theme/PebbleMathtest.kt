package io.cloak.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class PebbleMathTest {
    private val size = 200f
    private val half = size / 2
    private val pts = PebbleMath.points(size, size)
    private val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
    private val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }

    @Test fun pointCountMatchesSteps() {
        assertEquals(PebbleMath.STEPS * 2, pts.size)
    }

    @Test fun exponentSitsBetweenACircleAndAClassicSquircle() {
        // 2 is a circle, 4 is a classic squircle. The pebble is in between.
        assertTrue(PebbleMath.EXPONENT > 2.0 && PebbleMath.EXPONENT < 4.0)
    }

    @Test fun everyPointLiesOnTheSuperellipse() {
        // In a -1..1 box the curve is |x|^n + |y|^n = 1.
        val n = PebbleMath.EXPONENT
        for (i in 0 until PebbleMath.STEPS) {
            val xn = (pts[2 * i] / half - 1f).toDouble()
            val yn = (pts[2 * i + 1] / half - 1f).toDouble()
            val v = abs(xn).pow(n) + abs(yn).pow(n)
            assertEquals("point $i", 1.0, v, 0.001)
        }
    }

    @Test fun allPointsStayInsideTheBox() {
        assertTrue(xs.all { it >= -0.001f && it <= size + 0.001f })
        assertTrue(ys.all { it >= -0.001f && it <= size + 0.001f })
    }

    @Test fun shapeTouchesAllFourEdges() {
        assertEquals(0f, xs.min(), 0.01f)
        assertEquals(size, xs.max(), 0.01f)
        assertEquals(0f, ys.min(), 0.01f)
        assertEquals(size, ys.max(), 0.01f)
    }

    @Test fun diagonalPointIsFartherOutThanACircleButInsideASquare() {
        // STEPS / 8 is about 45 degrees. With STEPS = 180 it lands on 44 degrees, which is close enough.
        val i = PebbleMath.STEPS / 8
        val dx = pts[2 * i] - half
        val dy = pts[2 * i + 1] - half
        val ratio = sqrt(dx * dx + dy * dy) / half
        assertTrue("ratio=$ratio", ratio > 1.0f)
        assertTrue("ratio=$ratio", ratio < 1.4143f)
        // A pebble corner is only slightly past a circle (about 1.07 for exponent 2.5).
        assertTrue("ratio=$ratio", ratio > 1.03f && ratio < 1.12f)
    }

    @Test fun edgeMiddleCurvesButIsNotACircle() {
        // Halfway up the right edge a circle is at 0.87 of the radius and a classic squircle at 0.98.
        // The pebble (exponent 2.5) is about 0.925, so the edge is curved and not flat.
        var checked = 0
        for (i in 0 until PebbleMath.STEPS) {
            val xn = pts[2 * i] / half - 1f
            val yn = pts[2 * i + 1] / half - 1f
            if (xn > 0f && abs(yn) in 0.45f..0.55f) {
                assertTrue("x=$xn at y=$yn", xn > 0.90f && xn < 0.95f)
                checked++
            }
        }
        assertTrue(checked >= 2)
    }

    @Test fun shapeIsSymmetric() {
        val maxX = xs.max()
        val maxY = ys.max()
        // For every point there is a mirror point across each axis.
        for (i in 0 until PebbleMath.STEPS) {
            val x = pts[2 * i]
            val y = pts[2 * i + 1]
            assertTrue(
                "no horizontal mirror for point $i",
                (0 until PebbleMath.STEPS).any {
                    abs(pts[2 * it] - (maxX - x)) < 0.01f && abs(pts[2 * it + 1] - y) < 0.01f
                }
            )
            assertTrue(
                "no vertical mirror for point $i",
                (0 until PebbleMath.STEPS).any {
                    abs(pts[2 * it] - x) < 0.01f && abs(pts[2 * it + 1] - (maxY - y)) < 0.01f
                }
            )
        }
    }

    @Test fun nonSquareBoxesAreScaledPerAxis() {
        val p = PebbleMath.points(300f, 100f)
        assertEquals(300f, p.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        assertEquals(100f, p.filterIndexed { i, _ -> i % 2 == 1 }.max(), 0.01f)
    }
}