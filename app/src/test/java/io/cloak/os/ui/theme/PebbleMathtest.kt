package io.cloak.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PebbleMathTest {
    private val size = 200f
    private val pts = PebbleMath.points(size, size)
    private val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
    private val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }

    @Test fun pointCountMatchesSteps() {
        assertEquals(PebbleMath.STEPS * 2, pts.size)
    }

    @Test fun shapeIsAMixNotACircleOrASquircle() {
        assertTrue(PebbleMath.SQUARENESS > 0.0 && PebbleMath.SQUARENESS < 1.0)
        assertTrue(PebbleMath.SUPER_EXPONENT > 2.0)
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
        val i = PebbleMath.STEPS / 8
        val dx = pts[2 * i] - size / 2
        val dy = pts[2 * i + 1] - size / 2
        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
        assertTrue(distance > size / 2)
        assertTrue(distance < size / 2 * 1.4143f)
    }

    @Test fun edgeMiddleCurvesButIsNotACircle() {
        // Halfway up the right edge a circle is at 0.87 of the radius and a squircle at 0.98.
        // A pebble sits between, which means the edge is curved and not flat.
        val half = size / 2
        var checked = 0
        for (i in 0 until PebbleMath.STEPS) {
            val xn = pts[2 * i] / half - 1f
            val yn = pts[2 * i + 1] / half - 1f
            if (xn > 0f && abs(yn) in 0.45f..0.55f) {
                assertTrue("x=$xn at y=$yn", xn > 0.90f && xn < 0.975f)
                checked++
            }
        }
        assertTrue(checked >= 2)
    }

    @Test fun nonSquareBoxesAreScaledPerAxis() {
        val p = PebbleMath.points(300f, 100f)
        assertEquals(300f, p.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        assertEquals(100f, p.filterIndexed { i, _ -> i % 2 == 1 }.max(), 0.01f)
    }
}