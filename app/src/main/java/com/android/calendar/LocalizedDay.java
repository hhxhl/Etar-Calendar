package com.android.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.text.TextUtils;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.color.MaterialColors;
import java.time.LocalDate;

public final class LocalizedDay {
    private LocalizedDay() {}
    public static boolean enabled(Context c) {
        return LunarUtils.showLunar(c) || LocalizationSettings.holidays(c);
    }
    public static String label(Context c, LocalDate date) {
        if (!LunarUtils.showLunar(c)) return "";
        String text = LunarUtils.get(c, date.getYear(), date.getMonthValue() - 1,
                date.getDayOfMonth(), LunarUtils.FORMAT_LUNAR_SHORT | LunarUtils.FORMAT_ONE_FESTIVAL,
                false, null);
        return text == null ? "" : text;
    }
    public static String marker(Context c, LocalDate date) {
        if (!LocalizationSettings.holidays(c)) return "";
        switch (HolidayManager.getInstance(c).getStatus(date)) {
            case HOLIDAY: return "休";
            case WORKDAY: return "班";
            default: return "";
        }
    }
    public static String combined(Context c, LocalDate date) {
        String label = label(c, date), marker = marker(c, date);
        return marker.isEmpty() ? label : label.isEmpty() ? marker : marker + " " + label;
    }
    public static int markerColor(String marker) {
        return "休".equals(marker) ? 0xffd84343 : 0xff39865a;
    }

    public static int festivalColor(int surfaceColor) {
        return ColorUtils.calculateLuminance(surfaceColor) < 0.5 ? 0xffffb78b : 0xffa04419;
    }

    /** Keep the date centered and shrink the holiday marker only when required. */
    public static void drawMarker(Canvas canvas, Paint source, String marker,
            float right, float center, float dateWidth, float padding, float baseline, float size) {
        if (marker.isEmpty()) return;
        TextPaint paint = new TextPaint(source);
        paint.setTextSize(size);
        float available = right - padding - center - dateWidth / 2f - 1f;
        float width = paint.measureText(marker);
        if (available <= 0 || width <= 0) return;
        paint.setTextSize(size * Math.min(1f, available / width));
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setColor(markerColor(marker));
        canvas.drawText(marker, right - padding, baseline, paint);
    }

    public static int labelColor(Context context, LocalDate date, String label,
            int dateColor, int surfaceColor) {
        String lunar = LunarUtils.get(context, date.getYear(), date.getMonthValue() - 1,
                date.getDayOfMonth(), LunarUtils.FORMAT_LUNAR_SHORT, false, null);
        if (label.equals(lunar)) return ColorUtils.blendARGB(dateColor, surfaceColor, 0.28f);
        if (LunarUtils.isFestivalLabel(context, date))
            return festivalColor(surfaceColor);
        return MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, dateColor);
    }

    public static void drawLabel(Context context, Canvas canvas, Paint source, LocalDate date,
            String label, float left, float right, float padding, float baseline, float size,
            int dateColor, int surfaceColor) {
        if (label.isEmpty()) return;
        TextPaint paint = new TextPaint(source);
        paint.setTextSize(size);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(labelColor(context, date, label, dateColor, surfaceColor));
        CharSequence fitted = TextUtils.ellipsize(label, paint,
                Math.max(0, right - left - 2 * padding), TextUtils.TruncateAt.END);
        canvas.drawText(fitted.toString(), (left + right) / 2f, baseline, paint);
    }

    public static LocalDate fromJulian(int day) {
        return LocalDate.ofEpochDay(day - 2440588L);
    }
}
