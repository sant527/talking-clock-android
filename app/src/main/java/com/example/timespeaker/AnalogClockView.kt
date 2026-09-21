package com.example.timespeaker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A dial clock drawn from the current theme's colours.
 *
 * Written rather than using `android.widget.AnalogClock`, which is deprecated and draws a fixed
 * bitmap face that ignores the theme — it would look wrong in four of the five palettes.
 */
class AnalogClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var time: LocalTime = LocalTime.now()

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val secondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val hubPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
    }
    private val numeralBounds = android.graphics.Rect()

    init {
        val themed = context.theme
        val secondary = themed.resolveColor(android.R.attr.textColorSecondary)
        val accent = themed.resolveColor(androidx.appcompat.R.attr.colorPrimary)

        // The clock reads in the theme's own colour rather than a near-white: the dial ring and
        // ticks keep the dimmer tone so the hands still stand out against them.
        facePaint.color = secondary
        tickPaint.color = secondary
        handPaint.color = accent
        secondPaint.color = accent
        hubPaint.color = accent
        numeralPaint.color = accent
    }

    fun setTime(value: LocalTime) {
        time = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centreX = width / 2f
        val centreY = height / 2f
        val radius = min(width, height) / 2f - paddingLeft

        if (radius <= 0f) return

        facePaint.strokeWidth = radius * 0.025f
        canvas.drawCircle(centreX, centreY, radius, facePaint)

        drawTicks(canvas, centreX, centreY, radius)
        drawNumerals(canvas, centreX, centreY, radius)

        // Hours and minutes advance smoothly rather than stepping, which is how a real dial reads.
        val seconds = time.second + time.nano / 1_000_000_000f
        val minutes = time.minute + seconds / 60f
        val hours = time.hour % 12 + minutes / 60f

        handPaint.strokeWidth = radius * 0.075f
        drawHand(canvas, centreX, centreY, radius * 0.44f, hours / 12f, handPaint)

        handPaint.strokeWidth = radius * 0.055f
        drawHand(canvas, centreX, centreY, radius * 0.62f, minutes / 60f, handPaint)

        secondPaint.strokeWidth = radius * 0.025f
        drawHand(canvas, centreX, centreY, radius * 0.78f, seconds / 60f, secondPaint)

        canvas.drawCircle(centreX, centreY, radius * 0.045f, hubPaint)
    }

    private fun drawTicks(canvas: Canvas, centreX: Float, centreY: Float, radius: Float) {
        for (tick in 0 until 60) {
            val isHour = tick % 5 == 0
            tickPaint.strokeWidth = if (isHour) radius * 0.028f else radius * 0.012f

            val inner = radius * if (isHour) 0.89f else 0.93f
            val angle = (tick / 60f) * TWO_PI - HALF_PI

            canvas.drawLine(
                centreX + cos(angle) * inner,
                centreY + sin(angle) * inner,
                centreX + cos(angle) * radius * 0.95f,
                centreY + sin(angle) * radius * 0.95f,
                tickPaint
            )
        }
    }

    private fun drawNumerals(canvas: Canvas, centreX: Float, centreY: Float, radius: Float) {
        numeralPaint.textSize = radius * NUMERAL_TEXT_SCALE

        for (hour in 1..12) {
            val label = hour.toString()
            val angle = (hour / 12f) * TWO_PI - HALF_PI
            val x = centreX + cos(angle) * radius * NUMERAL_RING
            val y = centreY + sin(angle) * radius * NUMERAL_RING

            // Centre each numeral on its own glyph box: Paint draws from the baseline, so
            // without this the numbers sit noticeably high on the dial.
            numeralPaint.getTextBounds(label, 0, label.length, numeralBounds)
            canvas.drawText(label, x, y + numeralBounds.height() / 2f, numeralPaint)
        }
    }

    /** [fraction] is the hand's position around the dial, 0 at twelve o'clock. */
    private fun drawHand(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        length: Float,
        fraction: Float,
        paint: Paint
    ) {
        val angle = fraction * TWO_PI - HALF_PI
        // A short tail past the hub, as on a real clock face.
        canvas.drawLine(
            centreX - cos(angle) * length * 0.14f,
            centreY - sin(angle) * length * 0.14f,
            centreX + cos(angle) * length,
            centreY + sin(angle) * length,
            paint
        )
    }

    private fun android.content.res.Resources.Theme.resolveColor(attr: Int): Int {
        val value = android.util.TypedValue()
        resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private companion object {
        const val TWO_PI = (2 * Math.PI).toFloat()
        const val HALF_PI = (Math.PI / 2).toFloat()

        /** Where the numerals sit, as a share of the dial radius, and how big they are. */
        const val NUMERAL_RING = 0.74f
        const val NUMERAL_TEXT_SCALE = 0.17f
    }
}
