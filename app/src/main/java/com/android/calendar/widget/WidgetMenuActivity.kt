package com.android.calendar.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import com.android.calendar.theme.getColor as getWidgetColor
import com.android.calendar.theme.getWidgetBackgroundStyle
import ws.xsoh.etar.R
import kotlin.math.roundToInt

/** A transient, anchored menu for all three home-screen widgets. */
class WidgetMenuActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider?.packageName != packageName
        ) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(this@WidgetMenuActivity,
                    getWidgetBackgroundStyle(this@WidgetMenuActivity)))
            }
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.INVISIBLE
        }

        fun addItem(label: Int, action: () -> Intent) {
            val row = TextView(this).apply {
                setText(label)
                textSize = 16f
                setTextColor(getWidgetColor(this@WidgetMenuActivity, "widget_text"))
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setPadding(dp(20), 0, dp(20), 0)
                minHeight = dp(48)
                val selectable = TypedValue()
                if (this@WidgetMenuActivity.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                    setBackgroundResource(selectable.resourceId)
                }
                setOnClickListener {
                    startActivity(action().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
            menu.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        addItem(R.string.widget_menu_settings) { WidgetUtils.getConfigureIntent(this, widgetId) }
        addItem(R.string.widget_menu_add_event) { WidgetUtils.getAddEventIntent(this) }
        addItem(R.string.reminders_title) { WidgetUtils.getAddReminderIntent(this) }
        addItem(R.string.tasks_title) { WidgetUtils.getAddTaskIntent(this) }

        root.addView(menu, FrameLayout.LayoutParams(dp(200),
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        setContentView(root)

        root.doOnLayout {
            if (isFinishing || root.width == 0 || root.height == 0) return@doOnLayout
            val origin = IntArray(2)
            root.getLocationOnScreen(origin)
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val margin = dp(8)
            val left = maxOf(margin, visible.left - origin[0] + margin)
            val top = maxOf(margin, visible.top - origin[1] + margin)
            val right = minOf(root.width - margin, visible.right - origin[0] - margin)
            val bottom = minOf(root.height - margin, visible.bottom - origin[1] - margin)
            val width = minOf(dp(200), (right - left).coerceAtLeast(1))
            menu.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val height = menu.measuredHeight
            val anchor = intent.sourceBounds
            val x = if (anchor != null && !anchor.isEmpty) anchor.right - origin[0] - width
                else (left + right - width) / 2
            val below = if (anchor != null && !anchor.isEmpty) anchor.bottom - origin[1]
                else (top + bottom - height) / 2
            val y = if (anchor != null && !anchor.isEmpty && below + height > bottom)
                anchor.top - origin[1] - height else below
            menu.layoutParams = FrameLayout.LayoutParams(width,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT).apply {
                leftMargin = x.coerceIn(left, maxOf(left, right - width))
                topMargin = y.coerceIn(top, maxOf(top, bottom - height))
            }
            menu.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
