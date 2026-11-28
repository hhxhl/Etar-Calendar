package com.android.calendar.theme

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.TypedValue
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.android.calendar.Utils
import com.android.calendar.theme.ThemeUtils.getSuffix
import com.android.calendar.theme.ThemeUtils.isPureBlackModeEnabled
import com.android.calendar.theme.model.Theme
import ws.xsoh.etar.R

private const val TEAL: String = "teal"
private const val MONET: String = "monet"

fun AppCompatActivity.applyTheme() {
    val selectedTheme = ThemeUtils.getTheme(this)

    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager

    // Apply M3 system roles before the explicit pure-black override.
    theme.applyStyle(R.style.EtarDynamicColors, true)

    // Handle black theme/mode
    if (selectedTheme == Theme.SYSTEM && isPureBlackModeEnabled && isSystemInDarkTheme(this) || selectedTheme == Theme.DARK && isPureBlackModeEnabled) {
        theme.applyStyle(R.style.colorBackgroundBlack, true)
        theme.applyStyle(R.style.EtarPureBlackSurface, true)
    }

    // Setup edge to edge
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        setupEdgeToEdge(this)
    }

    // Set selected theme mode to the app
    when (selectedTheme) {
        Theme.SYSTEM -> {
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_CUSTOM)
        }

        Theme.LIGHT -> {
            setSystemBarConfiguration(light = true)
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_NO)
        }

        else -> {
            setSystemBarConfiguration(light = false)
            uiModeManager.setApplicationNightMode(UiModeManager.MODE_NIGHT_YES)
        }
    }
}

private fun AppCompatActivity.setSystemBarConfiguration(light: Boolean) {
    WindowInsetsControllerCompat(this.window, this.window.decorView.rootView).apply {
        // Status bar color
        isAppearanceLightStatusBars = light

        // Navigation bar color
        isAppearanceLightNavigationBars = light
        window.navigationBarColor = getStyledAttributeColor(android.R.attr.colorBackground)
    }
}

private fun Context.getStyledAttributeColor(id: Int): Int {
    val arr = obtainStyledAttributes(TypedValue().data, intArrayOf(id))
    val styledAttr = arr.getColor(0, Color.WHITE)
    arr.recycle()
    return styledAttr
}

fun getDialogStyle(context: Context): Int {
    val theme = ThemeUtils.getTheme(context)
    when (theme) {
        Theme.SYSTEM -> if (isSystemInDarkTheme(context)) {
            return android.R.style.Theme_DeviceDefault_Dialog
        } else {
            return android.R.style.Theme_DeviceDefault_Light_Dialog
        }

        Theme.LIGHT -> return android.R.style.Theme_DeviceDefault_Light_Dialog
        Theme.DARK, Theme.BLACK -> return android.R.style.Theme_DeviceDefault_Dialog
        else -> throw UnsupportedOperationException("Unknown theme: " + theme)
    }
}

fun getWidgetBackgroundStyle(context: Context): Int {
    val theme = ThemeUtils.getTheme(context)
    val pureBlack = context.isPureBlackModeEnabled
    when (theme) {
        Theme.SYSTEM -> if ((isSystemInDarkTheme(context))
                ) {
            if (pureBlack) {
                return R.color.bg_black
            } else {
                return R.color.bg_dark
            }
        } else {
            return R.color.background_color
        }

        Theme.LIGHT -> return R.color.background_color
        Theme.DARK -> return R.color.bg_dark
        Theme.BLACK -> return R.color.bg_black
        else -> throw java.lang.UnsupportedOperationException("Unknown theme: " + theme)
    }
}

fun getPrimaryColor(context: Context?): String {
    if (Utils.isMonetAvailable(context)) {
        return MONET
    } else {
        return TEAL
    }
}

fun getColorId(name: String): Int {
    return when (name) {
        TEAL -> R.color.colorPrimary
        MONET -> android.R.color.system_accent1_500
        else -> throw java.lang.UnsupportedOperationException("Unknown color name : " + name)
    }
}

// Explicit resource references keep the selected light/dark/black colors visible to
// the Android resource shrinker, including colors selected independently of uiMode.
private data class ThemeColor(val light: Int, val dark: Int, val black: Int)

private val themeColors = mapOf(
    "agenda_item_declined_color" to ThemeColor(R.color.agenda_item_declined_color_light, R.color.agenda_item_declined_color_dark, R.color.agenda_item_declined_color_black),
    "agenda_item_standard_color" to ThemeColor(R.color.agenda_item_standard_color_light, R.color.agenda_item_standard_color_dark, R.color.agenda_item_standard_color_black),
    "agenda_item_where_declined_text_color" to ThemeColor(R.color.agenda_item_where_declined_text_color_light, R.color.agenda_item_where_declined_text_color_dark, R.color.agenda_item_where_declined_text_color_black),
    "agenda_item_where_text_color" to ThemeColor(R.color.agenda_item_where_text_color_light, R.color.agenda_item_where_text_color_dark, R.color.agenda_item_where_text_color_black),
    "calendar_date_banner_text_color" to ThemeColor(R.color.calendar_date_banner_text_color_light, R.color.calendar_date_banner_text_color_dark, R.color.calendar_date_banner_text_color_black),
    "calendar_event_text_color" to ThemeColor(R.color.calendar_event_text_color, R.color.calendar_event_text_color_dark, R.color.calendar_event_text_color_black),
    "calendar_future_bg_color" to ThemeColor(R.color.calendar_future_bg_color_light, R.color.calendar_future_bg_color_dark, R.color.calendar_future_bg_color_black),
    "calendar_grid_area_selected" to ThemeColor(R.color.calendar_grid_area_selected_light, R.color.calendar_grid_area_selected_dark, R.color.calendar_grid_area_selected_black),
    "calendar_grid_line_inner_horizontal_color" to ThemeColor(R.color.calendar_grid_line_inner_horizontal_color_light, R.color.calendar_grid_line_inner_horizontal_color_dark, R.color.calendar_grid_line_inner_horizontal_color_black),
    "calendar_grid_line_inner_vertical_color" to ThemeColor(R.color.calendar_grid_line_inner_vertical_color_light, R.color.calendar_grid_line_inner_vertical_color_dark, R.color.calendar_grid_line_inner_vertical_color_black),
    "calendar_hidden" to ThemeColor(R.color.calendar_hidden_light, R.color.calendar_hidden_dark, R.color.calendar_hidden_black),
    "calendar_hour_background" to ThemeColor(R.color.calendar_hour_background_light, R.color.calendar_hour_background_dark, R.color.calendar_hour_background_black),
    "calendar_hour_label" to ThemeColor(R.color.calendar_hour_label_light, R.color.calendar_hour_label_dark, R.color.calendar_hour_label_black),
    "calendar_secondary_hidden" to ThemeColor(R.color.calendar_secondary_hidden_light, R.color.calendar_secondary_hidden_dark, R.color.calendar_secondary_hidden_black),
    "calendar_secondary_visible" to ThemeColor(R.color.calendar_secondary_visible_light, R.color.calendar_secondary_visible_dark, R.color.calendar_secondary_visible_black),
    "calendar_visible" to ThemeColor(R.color.calendar_visible_light, R.color.calendar_visible_dark, R.color.calendar_visible_black),
    "day_clicked_background_color" to ThemeColor(R.color.day_clicked_background_color_light, R.color.day_clicked_background_color_dark, R.color.day_clicked_background_color_black),
    "day_event_clicked_background_color" to ThemeColor(R.color.day_event_clicked_background_color_light, R.color.day_event_clicked_background_color_dark, R.color.day_event_clicked_background_color_black),
    "mini_month_today_outline_color" to ThemeColor(R.color.mini_month_today_outline_color_light, R.color.mini_month_today_outline_color_dark, R.color.mini_month_today_outline_color_black),
    "month_bgcolor" to ThemeColor(R.color.month_bgcolor_light, R.color.month_bgcolor_dark, R.color.month_bgcolor_black),
    "month_day_names_color" to ThemeColor(R.color.month_day_names_color_light, R.color.month_day_names_color_dark, R.color.month_day_names_color_black),
    "month_day_number" to ThemeColor(R.color.month_day_number_light, R.color.month_day_number_dark, R.color.month_day_number_black),
    "month_day_number_other" to ThemeColor(R.color.month_day_number_other_light, R.color.month_day_number_other_dark, R.color.month_day_number_other_black),
    "month_event_color" to ThemeColor(R.color.month_event_color_light, R.color.month_event_color_dark, R.color.month_event_color_black),
    "month_event_extra_color" to ThemeColor(R.color.month_event_extra_color_light, R.color.month_event_extra_color_dark, R.color.month_event_extra_color_black),
    "month_event_extra_other_color" to ThemeColor(R.color.month_event_extra_other_color_light, R.color.month_event_extra_other_color_dark, R.color.month_event_extra_other_color_black),
    "month_event_other_color" to ThemeColor(R.color.month_event_other_color_light, R.color.month_event_other_color_dark, R.color.month_event_other_color_black),
    "month_focus_month_bgcolor" to ThemeColor(R.color.month_focus_month_bgcolor_light, R.color.month_focus_month_bgcolor_dark, R.color.month_focus_month_bgcolor_black),
    "month_grid_lines" to ThemeColor(R.color.month_grid_lines_light, R.color.month_grid_lines_dark, R.color.month_grid_lines_black),
    "month_mini_day_number" to ThemeColor(R.color.month_mini_day_number_light, R.color.month_mini_day_number_dark, R.color.month_mini_day_number_black),
    "month_other_bgcolor" to ThemeColor(R.color.month_other_bgcolor_light, R.color.month_other_bgcolor_dark, R.color.month_other_bgcolor_black),
    "month_other_month_day_number" to ThemeColor(R.color.month_other_month_day_number_light, R.color.month_other_month_day_number_dark, R.color.month_other_month_day_number_black),
    "month_saturday" to ThemeColor(R.color.month_saturday_light, R.color.month_saturday_dark, R.color.month_saturday_black),
    "month_selected_week_bgcolor" to ThemeColor(R.color.month_selected_week_bgcolor_light, R.color.month_selected_week_bgcolor_dark, R.color.month_selected_week_bgcolor_black),
    "month_sunday" to ThemeColor(R.color.month_sunday_light, R.color.month_sunday_dark, R.color.month_sunday_black),
    "month_today_bgcolor" to ThemeColor(R.color.month_today_bgcolor_light, R.color.month_today_bgcolor_dark, R.color.month_today_bgcolor_black),
    "month_today_number" to ThemeColor(R.color.month_today_number_light, R.color.month_today_number_dark, R.color.month_today_number_black),
    "month_week_num_color" to ThemeColor(R.color.month_week_num_color_light, R.color.month_week_num_color_dark, R.color.month_week_num_color_black),
    "month_widget_today_bg" to ThemeColor(R.color.month_widget_today_bg_light, R.color.month_widget_today_bg_dark, R.color.month_widget_today_bg_black),
    "month_widget_today_text" to ThemeColor(R.color.month_widget_today_text_light, R.color.month_widget_today_text_dark, R.color.month_widget_today_text_black),
    "pressed" to ThemeColor(R.color.pressed_light, R.color.pressed_dark, R.color.pressed_black),
    "staggered_event_shadow_color" to ThemeColor(R.color.staggered_event_shadow_color, R.color.staggered_event_shadow_color_dark, R.color.staggered_event_shadow_color_black),
    "today_highlight_color" to ThemeColor(R.color.today_highlight_color_light, R.color.today_highlight_color_dark, R.color.today_highlight_color_black),
    "week_saturday" to ThemeColor(R.color.week_saturday_light, R.color.week_saturday_dark, R.color.week_saturday_black),
    "week_sunday" to ThemeColor(R.color.week_sunday_light, R.color.week_sunday_dark, R.color.week_sunday_black),
    "week_today" to ThemeColor(R.color.week_today_light, R.color.week_today_dark, R.color.week_today_black),
    "widget_text" to ThemeColor(R.color.widget_text_light, R.color.widget_text_dark, R.color.widget_text_black),
)

fun getColor(context: Context, id: String?): Int {
    val color = requireNotNull(themeColors[id]) { "Unknown theme color: $id" }
    val colorId = when (getSuffix(context)) {
        "_black" -> color.black
        "_dark" -> color.dark
        else -> color.light
    }
    return context.getColor(colorId)
}

fun getDrawableId(context: Context, id: String?): Int = when (id) {
    // Both backgrounds use theme attributes; no separate dark/black drawable is needed.
    "agenda_item_bg_primary" -> R.drawable.agenda_item_bg_primary
    "agenda_item_bg_secondary" -> R.drawable.agenda_item_bg_secondary
    "month_widget_today_circle" -> when (getSuffix(context)) {
        "_black" -> R.drawable.month_widget_today_circle_black
        "_dark" -> R.drawable.month_widget_today_circle_dark
        else -> R.drawable.month_widget_today_circle
    }
    else -> 0
}

fun isSystemInDarkTheme(context: Context): Boolean {
    return (context.resources
        .configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun setupEdgeToEdge(activity: Activity) {
    val window = activity.window
    val rootView = activity.window.decorView.rootView

    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.setOnApplyWindowInsetsListener(
        rootView
    ) { v: View, windowInsets: WindowInsetsCompat ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
        WindowInsetsCompat.CONSUMED
    }

    // Special Handling
    setSystemBarsColors(activity)
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun setSystemBarsColors(activity: Activity) {
    val lightAppearance = !isSystemInDarkTheme(activity)
    val rootView = activity.window.decorView.rootView
    val windowInsetsController =
        WindowCompat.getInsetsController(activity.window, rootView)

    windowInsetsController.isAppearanceLightStatusBars = lightAppearance
    windowInsetsController.isAppearanceLightNavigationBars = lightAppearance

}
