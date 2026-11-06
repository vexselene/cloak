package io.cloak.os.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PebbleMathTest {
    private val size = 200f
    private val pts = PebbleMath.points(size, size)
    private val xs = pts.filterIndexed { i, _ -> i % 2 == 0 }
    private val ys = pts.filterIndexed { i, _ -> i % 2 == 1 }

    @Test fun pointCountMatchesSteps() {
        assertEquals(PebbleMath.STEPS * 2, pts.size)
    }

    @Test fun exponentIsBetweenACircleAndTheOldSquircle() {
        assertTrue(PebbleMath.EXPONENT > 2.0 && PebbleMath.EXPONENT < 4.5)
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

    @Test fun nonSquareBoxesAreScaledPerAxis() {
        val p = PebbleMath.points(300f, 100f)
        assertEquals(300f, p.filterIndexed { i, _ -> i % 2 == 0 }.max(), 0.01f)
        assertEquals(100f, p.filterIndexed { i, _ -> i % 2 == 1 }.max(), 0.01f)
    }
}