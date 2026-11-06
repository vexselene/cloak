package io.cloak.os.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapseModelTest {
    @Test fun collapseIsLimitedByTheRemainingRoom() {
        val m = CollapseModel(100f)
        assertEquals(60f, m.collapse(60f), 0.001f)
        assertEquals(40f, m.collapse(60f), 0.001f)
        assertEquals(100f, m.collapsed, 0.001f)
        assertEquals(0f, m.collapse(10f), 0.001f)
    }

    @Test fun expandIsLimitedByWhatWasCollapsed() {
        val m = CollapseModel(100f)
        m.collapse(30f)
        assertEquals(30f, m.expand(80f), 0.001f)
        assertEquals(0f, m.collapsed, 0.001f)
        assertEquals(0f, m.expand(10f), 0.001f)
    }

    @Test fun negativeAmountsDoNothing() {
        val m = CollapseModel(100f)
        assertEquals(0f, m.collapse(-5f), 0.001f)
        m.collapse(20f)
        assertEquals(0f, m.expand(-5f), 0.001f)
        assertEquals(20f, m.collapsed, 0.001f)
    }

    @Test fun collapseThenExpandReturnsToStart() {
        val m = CollapseModel(250f)
        m.collapse(123f)
        m.expand(123f)
        assertEquals(0f, m.collapsed, 0.001f)
    }

    @Test fun progressRunsFromZeroToOneAndHandlesZeroMax() {
        assertEquals(0f, CollapseModel.progressOf(0f, 100f), 0.001f)
        assertEquals(0.5f, CollapseModel.progressOf(50f, 100f), 0.001f)
        assertEquals(1f, CollapseModel.progressOf(100f, 100f), 0.001f)
        assertEquals(1f, CollapseModel.progressOf(0f, 0f), 0.001f)
        assertEquals(1f, CollapseModel.progressOf(500f, 100f), 0.001f)
    }

    @Test fun brandingIsVisibleWhenOpenAndGoneWhenClosed() {
        assertEquals(1f, CollapseModel.brandAlpha(0f), 0.001f)
        assertEquals(0f, CollapseModel.brandAlpha(1f), 0.001f)
        assertEquals(0f, CollapseModel.brandAlpha(0.6f), 0.001f)
    }

    @Test fun titleIsHiddenWhenOpenAndVisibleWhenClosed() {
        assertEquals(0f, CollapseModel.titleAlpha(0f), 0.001f)
        assertEquals(0f, CollapseModel.titleAlpha(0.55f), 0.001f)
        assertEquals(1f, CollapseModel.titleAlpha(1f), 0.001f)
    }

    @Test fun fadesNeverMoveTheWrongWay() {
        var lastBrand = 1f
        var lastTitle = 0f
        for (i in 0..100) {
            val p = i / 100f
            val b = CollapseModel.brandAlpha(p)
            val t = CollapseModel.titleAlpha(p)
            assertTrue("brand at $p", b <= lastBrand + 0.0001f)
            assertTrue("title at $p", t >= lastTitle - 0.0001f)
            lastBrand = b
            lastTitle = t
        }
    }
}