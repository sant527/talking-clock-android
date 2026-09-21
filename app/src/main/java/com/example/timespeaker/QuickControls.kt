package com.example.timespeaker

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * The row of theme swatches shown on the main screen and over the full-screen clock.
 *
 * Swatches rather than a list of names: this is the shortcut for changing the look without
 * leaving the clock, so it has to read at a glance and fit on one line.
 */
object QuickControls {

    fun populateThemeSwatches(
        container: LinearLayout,
        current: AppTheme,
        onPick: (AppTheme) -> Unit
    ) {
        val context = container.context
        container.removeAllViews()

        AppTheme.entries.forEach { theme ->
            val swatch = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, SWATCH_DP), dp(context, SWATCH_DP))
                    .also { it.marginEnd = dp(context, SWATCH_GAP_DP) }
                background = swatchDrawable(context, theme, selected = theme == current)
                contentDescription = context.getString(theme.labelRes)
                isClickable = true
                isFocusable = true
                setOnClickListener { if (theme != current) onPick(theme) }
            }
            container.addView(swatch)
        }
    }

    /**
     * A disc of the theme's accent over its own background, ringed when it is the current one.
     * Showing both colours is what distinguishes, say, Paper from Midnight — their accents are
     * both blue and only the background tells them apart.
     */
    private fun swatchDrawable(context: Context, theme: AppTheme, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(context, theme.backgroundRes))
            setStroke(
                dp(context, if (selected) SELECTED_RING_DP else RING_DP),
                ContextCompat.getColor(context, theme.accentRes)
            )
        }

    private fun dp(context: Context, value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        context.resources.displayMetrics
    ).toInt()

    private const val SWATCH_DP = 34
    private const val SWATCH_GAP_DP = 12
    private const val RING_DP = 3
    private const val SELECTED_RING_DP = 9
}
