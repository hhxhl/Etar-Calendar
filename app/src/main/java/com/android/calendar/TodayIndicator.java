package com.android.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import java.util.ArrayList;

import ws.xsoh.etar.R;

/** Shared current-day and current-time marker for the calendar grid views. */
public final class TodayIndicator {
    public static final long REFRESH_INTERVAL_MS = 300_000L;
    private final Paint paint = new Paint();
    private final Path textClip = new Path();
    private final int color;
    private final float height, textGap;
    private final ArrayList<RectF> protectedText = new ArrayList<>();
    private boolean collecting;

    public TodayIndicator(Context context) {
        color = context.getColor(R.color.today_indicator);
        height = context.getResources().getDimension(R.dimen.today_indicator_height);
        textGap = context.getResources().getDisplayMetrics().density;
    }

    /** Collect only the current frame's text, in the same canvas coordinates. */
    public void beginFrame(boolean active) {
        protectedText.clear();
        collecting = active;
    }

    private void protect(float left, float top, float right, float bottom) {
        if (!collecting || right <= left || bottom <= top) return;
        protectedText.add(new RectF(left - textGap, top - textGap,
                right + textGap, bottom + textGap));
    }

    /** Protect displayed line extents, including replacement-span state icons. */
    public void protectLayout(Layout layout, float x, float y, float clipWidth, int clipHeight) {
        if (!collecting) return;
        for (int i = 0; i < layout.getLineCount(); i++) {
            if (layout.getLineTop(i) >= clipHeight) break;
            protect(x + Math.max(0, layout.getLineLeft(i)), y + layout.getLineTop(i),
                    x + Math.min(clipWidth, layout.getLineRight(i)),
                    y + Math.min(clipHeight, layout.getLineBottom(i)));
        }
    }

    public void protectText(String text, Paint paint, float x, float baseline,
            float clipLeft, float clipRight) {
        if (!collecting || text.isEmpty()) return;
        float width = paint.measureText(text);
        float left = x;
        if (paint.getTextAlign() == Paint.Align.CENTER) left -= width / 2f;
        else if (paint.getTextAlign() == Paint.Align.RIGHT) left -= width;
        Paint.FontMetrics fm = paint.getFontMetrics();
        protect(Math.max(clipLeft, left), baseline + fm.ascent,
                Math.min(clipRight, left + width), baseline + fm.descent);
    }

    public static float dayFraction(int hour, int minute) {
        return (hour * 60 + minute) / 1440f;
    }

    /** Map local wall-clock time to the cell, keeping the stroke inside it. */
    public float position(float top, float bottom, int hour, int minute) {
        return top + Math.max(0, bottom - top - height) * dayFraction(hour, minute);
    }

    public void draw(Canvas canvas, float left, float right, float top, int animationAlpha) {
        if (right <= left) return;
        // Partition the stroke into text and non-text regions. Union overlapping
        // text rectangles so the faint pass is never drawn more than once.
        textClip.rewind();
        textClip.setFillType(Path.FillType.WINDING);
        for (RectF bounds : protectedText) {
            if (bounds.bottom > top && bounds.top < top + height * 2
                    && bounds.right > left && bounds.left < right)
                textClip.addRect(bounds, Path.Direction.CW);
        }
        if (textClip.isEmpty()) {
            drawStroke(canvas, left, right, top, animationAlpha, 1f);
            return;
        }
        canvas.save();
        canvas.clipPath(textClip);
        drawStroke(canvas, left, right, top, animationAlpha, 0.25f);
        canvas.restore();
        canvas.save();
        canvas.clipOutPath(textClip);
        drawStroke(canvas, left, right, top, animationAlpha, 1f);
        canvas.restore();
    }

    private void drawStroke(Canvas canvas, float left, float right, float top,
            int animationAlpha, float opacity) {
        paint.setColor(color);
        if (animationAlpha > 0) {
            // Apply the same opacity rule to the pulse, without obscuring text.
            // Draw the halo only below the main stroke to avoid stacking alpha.
            paint.setAlpha(Math.round(animationAlpha * 0.25f * opacity));
            canvas.drawRect(left, top + height, right, top + height * 2, paint);
        }
        paint.setAlpha(Math.round(255 * opacity));
        canvas.drawRect(left, top, right, top + height, paint);
    }
}
