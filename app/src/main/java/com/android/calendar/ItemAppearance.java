package com.android.calendar;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import ws.xsoh.etar.R;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
import com.android.calendar.theme.DynamicThemeKt;

/** Shared source-color presentation for calendar rows, grid items and widgets. */
public final class ItemAppearance {
    private static final int BACKGROUND_ALPHA = 36;

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
        return context.getResources().getDimension(R.dimen.item_corner_radius);
    }

    public static float barWidth(Context context) {
        return context.getResources().getDimension(R.dimen.item_color_bar_width);
    }

    public static void drawSurface(Context context, Canvas canvas, Paint paint,
            float left, float top, float right, float bottom, int foreground, int animationAlpha) {
        int oldColor = paint.getColor();
        Paint.Style oldStyle = paint.getStyle();
        boolean oldAntiAlias = paint.isAntiAlias();
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        paint.setColor(background(foreground));
        paint.setAlpha(Math.round(BACKGROUND_ALPHA * animationAlpha / 255f));
        float radius = radius(context);
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
        paint.setColor(oldColor);
        paint.setStyle(oldStyle);
        paint.setAntiAlias(oldAntiAlias);
    }

    public static void drawBar(Context context, Canvas canvas, Paint paint,
            float left, float top, float right, float bottom, int foreground) {
        int oldColor = paint.getColor();
        Paint.Style oldStyle = paint.getStyle();
        boolean oldAntiAlias = paint.isAntiAlias();
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        paint.setColor(foreground);
        float width = barWidth(context);
        canvas.drawRoundRect(left, top + 1, Math.min(right, left + width),
                bottom - 1, width / 2, width / 2, paint);
        paint.setColor(oldColor);
        paint.setStyle(oldStyle);
        paint.setAntiAlias(oldAntiAlias);
    }

    public static int bindRow(View root, int displayColor, TextView... labels) {
        int color = foreground(root.getContext(), displayColor);
        apply(root, color);
        setTextColor(color, labels);
        return color;
    }

    public static void setTextColor(int foreground, TextView... labels) {
        for (TextView label : labels) label.setTextColor(foreground);
    }

    private static void apply(View view, int foreground) {
        GradientDrawable drawable = (GradientDrawable) view.getContext()
                .getDrawable(R.drawable.item_rounded_surface).mutate();
        drawable.setColor(background(foreground));
        view.setBackground(drawable);
    }

    public static void bindWidget(Context context, RemoteViews views, int displayColor,
            int backgroundId, int barId, int... textIds) {
        // Resolve the palette once per item. The host can switch both colors
        // together when using the live system theme.
        if (com.android.calendar.widget.WidgetUtils.usesLiveTheme(context)) {
            int light = foreground(displayColor, false);
            int dark = foreground(displayColor, true);
            views.setColorStateList(backgroundId, "setImageTintList",
                    ColorStateList.valueOf(light), ColorStateList.valueOf(dark));
            views.setColorInt(barId, "setBackgroundColor", light, dark);
            for (int textId : textIds) views.setColorInt(textId, "setTextColor", light, dark);
        } else {
            int color = foreground(context, displayColor);
            views.setColorStateList(backgroundId, "setImageTintList", ColorStateList.valueOf(color));
            views.setInt(barId, "setBackgroundColor", color);
            for (int textId : textIds) views.setTextColor(textId, color);
        }
        // Tint first, then apply the same alpha as Canvas and ordinary Views.
        views.setInt(backgroundId, "setImageAlpha", BACKGROUND_ALPHA);
    }
}
