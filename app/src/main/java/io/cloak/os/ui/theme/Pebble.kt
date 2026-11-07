package io.cloak.os.ui.theme

import android.graphics.Path as AndroidPath
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

object PebbleMath {
    const val STEPS = 180

    /**
     * Superellipse exponent: |x|^n + |y|^n = 1.
     * Fitted from a real OxygenOS pebble icon (best fit about 2.5).
     * 2.0 is a circle, 4.0 is a classic squircle.
     */
    const val EXPONENT = 2.5

    /** x,y pairs, clockwise from the right-hand middle, inside a width by height box. */
    fun points(width: Float, height: Float, steps: Int = STEPS): FloatArray {
        val out = FloatArray(steps * 2)
        val p = 2.0 / EXPONENT
        for (i in 0 until steps) {
            val t = 2.0 * PI * i / steps
            val c = cos(t)
            val s = sin(t)
            val x = sign(c) * abs(c).pow(p)
            val y = sign(s) * abs(s).pow(p)
            out[2 * i] = ((x + 1.0) / 2.0 * width).toFloat()
            out[2 * i + 1] = ((y + 1.0) / 2.0 * height).toFloat()
        }
        return out
    }
}

object Pebble {
    fun androidPath(size: Float): AndroidPath = AndroidPath().apply {
        val p = PebbleMath.points(size, size)
        moveTo(p[0], p[1])
        for (i in 1 until p.size / 2) lineTo(p[2 * i], p[2 * i + 1])
        close()
    }
}

object PebbleShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = PebbleMath.points(size.width, size.height)
        val path = Path().apply {
            moveTo(p[0], p[1])
            for (i in 1 until p.size / 2) lineTo(p[2 * i], p[2 * i + 1])
            close()
        }
        return Outline.Generic(path)
    }
}