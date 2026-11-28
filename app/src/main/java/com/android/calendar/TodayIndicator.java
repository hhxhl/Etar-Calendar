package com.android.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import ws.xsoh.etar.R;

/** Shared current-day and current-time marker for the calendar grid views. */
public final class TodayIndicator {
    private final Paint paint = new Paint();
    private final int color;
    private final float height;

    public TodayIndicator(Context context) {
        color = context.getColor(R.color.today_indicator);
        height = context.getResources().getDimension(R.dimen.today_indicator_height);
    }

    public void draw(Canvas canvas, float left, float right, float top, int animationAlpha) {
        if (right <= left) return;
        paint.setColor(color);
        if (animationAlpha > 0) {
            // Keep the red marker visible while the Go to Today feedback pulses.
            paint.setAlpha(Math.round(animationAlpha * 0.25f));
            canvas.drawRect(left, top, right, top + height * 2, paint);
            paint.setAlpha(255);
        }
        canvas.drawRect(left, top, right, top + height, paint);
    }
}
