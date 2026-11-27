package com.android.calendar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.RemoteViews;
import androidx.core.graphics.ColorUtils;
import com.android.calendar.theme.DynamicThemeKt;

/** Shared source-color presentation for calendar rows, grid items and widgets. */
public final class ItemAppearance {
    private static final int BACKGROUND_ALPHA = 36;
    private static final int CORNER_DP = 4;

    private ItemAppearance() {}

    public static int foreground(Context context, int displayColor) {
        return foreground(displayColor, ColorUtils.calculateLuminance(
                DynamicThemeKt.getColor(context, "agenda_item_standard_color") | 0xff000000) > 0.5);
    }

    private static int foreground(int displayColor, boolean dark) {
        int surface = dark ? 0xff202124 : 0xfffafafa;
        int target = dark ? Color.WHITE : Color.BLACK;
        int original = displayColor | 0xff000000;
        // Preserve the configured hue whenever possible; correct only insufficient contrast.
        for (int step = 0; step <= 20; step++) {
            int candidate = ColorUtils.blendARGB(original, target, step / 20f);
            int composite = ColorUtils.compositeColors(background(candidate), surface);
            if (ColorUtils.calculateContrast(candidate, composite) >= 4.5) return candidate;
        }
        return target;
    }

    public static int background(int foreground) {
        return (foreground & 0x00ffffff) | (BACKGROUND_ALPHA << 24);
    }

    public static float radius(Context context) {
        return CORNER_DP * context.getResources().getDisplayMetrics().density;
    }

    public static void apply(View view, int foreground) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(background(foreground));
        drawable.setCornerRadius(radius(view.getContext()));
        view.setBackground(drawable);
    }

    public static void widgetBackground(Context context, RemoteViews views, int backgroundId, int displayColor) {
        if (com.android.calendar.widget.WidgetUtils.usesLiveTheme(context)) {
            views.setColorStateList(backgroundId, "setImageTintList",
                    ColorStateList.valueOf(background(foreground(displayColor, false))),
                    ColorStateList.valueOf(background(foreground(displayColor, true))));
        } else {
            views.setColorStateList(backgroundId, "setImageTintList",
                    ColorStateList.valueOf(background(foreground(context, displayColor))));
        }
    }

    public static void widgetText(Context context, RemoteViews views, int id, int displayColor) {
        if (com.android.calendar.widget.WidgetUtils.usesLiveTheme(context)) {
            views.setColorInt(id, "setTextColor", foreground(displayColor, false), foreground(displayColor, true));
        } else {
            views.setTextColor(id, foreground(context, displayColor));
        }
    }

    public static void widgetBar(Context context, RemoteViews views, int id, int displayColor) {
        if (com.android.calendar.widget.WidgetUtils.usesLiveTheme(context)) {
            views.setColorInt(id, "setBackgroundColor", foreground(displayColor, false), foreground(displayColor, true));
        } else {
            views.setInt(id, "setBackgroundColor", foreground(context, displayColor));
        }
    }
}
