package io.cloak.os.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import io.cloak.os.ui.theme.Pebble

object IconRenderer {
    private const val LEGACY_BACKGROUND = 0xFFEDEFF3

    /** Draws any app icon into a squircle tile. */
    fun render(icon: Drawable, sizePx: Int): Bitmap {
        val content = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawContent(Canvas(content), icon, sizePx)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawPath(Pebble.androidPath(sizePx.toFloat()), paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(content, 0f, 0f, paint)
        content.recycle()
        return out
    }

    private fun drawContent(canvas: Canvas, icon: Drawable, size: Int) {
        if (icon is AdaptiveIconDrawable) {
            // The layers are 108dp with only the middle 72dp visible, so draw them 1.5x
            // and let the tile show the visible part.
            val over = (size * 0.25f).toInt()
            val bg = icon.background
            if (bg != null) {
                bg.setBounds(-over, -over, size + over, size + over)
                bg.draw(canvas)
            } else {
                canvas.drawColor(LEGACY_BACKGROUND.toInt())
            }
            icon.foreground?.let {
                it.setBounds(-over, -over, size + over, size + over)
                it.draw(canvas)
            }
        } else {
            // Icons without layers get a light tile and some breathing room.
            canvas.drawColor(LEGACY_BACKGROUND.toInt())
            val inset = (size * 0.14f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(canvas)
        }
    }
}