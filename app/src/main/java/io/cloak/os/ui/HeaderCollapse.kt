package io.cloak.os.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** How far the header has shrunk, between 0 and max. No Android or Compose types, so it is testable. */
class CollapseModel(val max: Float) {
    var collapsed = 0f
        private set

    /** Shrinks by up to [amount]. Returns how much was used. */
    fun collapse(amount: Float): Float {
        val used = amount.coerceIn(0f, max - collapsed)
        collapsed += used
        return used
    }

    /** Grows back by up to [amount]. Returns how much was used. */
    fun expand(amount: Float): Float {
        val used = amount.coerceIn(0f, collapsed)
        collapsed -= used
        return used
    }

    companion object {
        fun progressOf(collapsed: Float, max: Float): Float =
            if (max <= 0f) 1f else (collapsed / max).coerceIn(0f, 1f)

        /** The centered branding fades out quickly, gone before the header is two thirds shut. */
        fun brandAlpha(progress: Float): Float = (1f - progress * 1.8f).coerceIn(0f, 1f)

        /** The top-left title only appears once the branding is nearly gone. */
        fun titleAlpha(progress: Float): Float = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
    }
}

@Stable
class HeaderCollapse(private val max: Float) : NestedScrollConnection {
    private val model = CollapseModel(max)
    private var collapsed by mutableFloatStateOf(0f)

    val heightPx: Float get() = max - collapsed
    val collapsedPx: Float get() = collapsed
    val brandAlpha: Float get() = CollapseModel.brandAlpha(CollapseModel.progressOf(collapsed, max))
    val titleAlpha: Float get() = CollapseModel.titleAlpha(CollapseModel.progressOf(collapsed, max))

    /** Finger moving up: the header shrinks first, then the list scrolls. */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (available.y >= 0f) return Offset.Zero
        val used = model.collapse(-available.y)
        collapsed = model.collapsed
        return Offset(0f, -used)
    }

    /** Finger moving down: the list scrolls back to the top first, then the header grows. */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (available.y <= 0f) return Offset.Zero
        val used = model.expand(available.y)
        collapsed = model.collapsed
        return Offset(0f, used)
    }
}