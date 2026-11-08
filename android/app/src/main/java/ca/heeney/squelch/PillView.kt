package ca.heeney.squelch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.ln
import kotlin.math.sin

/** Black pill with level bars: accent while listening, a white ripple while transcribing. */
class PillView(ctx: Context) : View(ctx) {
    var phase = Dictation.Phase.Listening
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2A2A2A.toInt(); style = Paint.Style.STROKE; strokeWidth = d
    }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val levels = FloatArray(BARS)
    private val rect = RectF()
    private var unlisten: (() -> Unit)? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        unlisten = Dictation.level.listen { r -> post { push(r) } }
    }

    override fun onDetachedFromWindow() {
        unlisten?.invoke()
        super.onDetachedFromWindow()
    }

    private fun push(r: Float) {
        // dB-ish: 0.001 (quiet room) to 0.3 (loud) maps to 0..1
        val v = ((ln(maxOf(r, 1e-4f)) - ln(0.001f)) / (ln(0.3f) - ln(0.001f))).coerceIn(0f, 1f)
        System.arraycopy(levels, 1, levels, 0, BARS - 1)
        levels[BARS - 1] = v
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(d / 2, d / 2, w - d / 2, h - d / 2)
        c.drawRoundRect(rect, h / 2, h / 2, bg)
        c.drawRoundRect(rect, h / 2, h / 2, border)

        val bw = 3 * d
        val gap = 4 * d
        val total = BARS * bw + (BARS - 1) * gap
        var x = (w - total) / 2
        val maxH = h - 16 * d
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until BARS) {
            val v = if (phase == Dictation.Phase.Listening) {
                bar.color = ACCENT
                levels[i]
            } else {
                bar.color = Color.WHITE
                0.25f + 0.25f * (1 + sin(t * 8f - i * 0.6f))
            }
            val bh = maxOf(bw, v * maxH)
            rect.set(x, (h - bh) / 2, x + bw, (h + bh) / 2)
            c.drawRoundRect(rect, bw / 2, bw / 2, bar)
            x += bw + gap
        }
        if (phase == Dictation.Phase.Working) postInvalidateOnAnimation()
    }

    companion object {
        const val BARS = 9
        val ACCENT = 0xFF6F93B8.toInt()
    }
}
