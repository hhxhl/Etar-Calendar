package com.android.calendar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.ImageView;
import android.widget.CheckBox;
import android.graphics.PorterDuff;
import ws.xsoh.etar.R;

/** The same vector artwork is used by Canvas text, list markers and RemoteViews. */
public final class ItemStateIcons {
    private ItemStateIcons() {}

    public static int resource(String state) {
        if (state == null) return 0;
        switch (state) {
            case ItemStateSymbols.EVENT_FUTURE: return R.drawable.ic_item_event_future;
            case ItemStateSymbols.EVENT_ACTIVE: return R.drawable.ic_item_event_active;
            case ItemStateSymbols.EVENT_PAST: return R.drawable.ic_item_event_past;
            case ItemStateSymbols.TASK_PENDING: return R.drawable.ic_item_task_pending;
            case ItemStateSymbols.TASK_ACTIVE: return R.drawable.ic_item_task_active;
            case ItemStateSymbols.TASK_OVERDUE: return R.drawable.ic_item_task_overdue;
            case ItemStateSymbols.TASK_DONE: return R.drawable.ic_item_task_done;
            default: return 0;
        }
    }

    /** Lists use dedicated image content, independent of text and foreground drawing. */
    public static void bindMarker(View view, String state, int color) {
        int id = resource(state);
        Drawable icon = id == 0 ? null : view.getContext().getDrawable(id).mutate();
        if (icon != null) icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        if (view instanceof ImageView) {
            ((ImageView) view).setImageDrawable(icon);
        } else if (view instanceof CompletionMarker) {
            ((CompletionMarker) view).setMarker(icon);
        } else {
            throw new IllegalArgumentException("Unsupported item marker");
        }
    }

    /** Retains native checkbox accessibility, click and checked state; draws only our icon. */
    public static final class CompletionMarker extends CheckBox {
        private Drawable marker;

        public CompletionMarker(Context context) {
            super(context);
            setButtonDrawable((Drawable) null);
            setPadding(0, 0, 0, 0);
            setWillNotDraw(false);
        }

        private void setMarker(Drawable icon) {
            marker = icon;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (marker == null) return;
            int size = Math.min(Math.min(getWidth(), getHeight()), marker.getIntrinsicWidth());
            if (size <= 0) return;
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            marker.setBounds(left, top, left + size, top + size);
            marker.draw(canvas);
        }
    }

    public static CharSequence prefix(Context context, String state, CharSequence title) {
        return withIcon(context, state, title);
    }

    private static CharSequence withIcon(Context context, String state, CharSequence text) {
        int id = resource(state);
        if (id == 0) return text;
        SpannableStringBuilder result = new SpannableStringBuilder("\uFFFC");
        result.setSpan(new MarkerSpan(context, state, id), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (text != null) result.append(text);
        return result;
    }

    public static boolean hasState(CharSequence text, String state) {
        if (!(text instanceof Spanned)) return false;
        Spanned spanned = (Spanned) text;
        MarkerSpan[] spans = spanned.getSpans(0, spanned.length(), MarkerSpan.class);
        return spans.length > 0 && state.equals(spans[0].state);
    }

    public static void bindWidget(RemoteViews views, int viewId, String state, boolean visible) {
        int id = resource(state);
        views.setViewVisibility(viewId, visible && id != 0 ? View.VISIBLE : View.GONE);
        if (id != 0) views.setImageViewResource(viewId, id);
    }

    /** The placeholder is never drawn as text. Size and tint follow the surrounding text. */
    private static final class MarkerSpan extends ReplacementSpan {
        final String state;
        private final Drawable icon;

        MarkerSpan(Context context, String state, int id) {
            this.state = state;
            this.icon = context.getDrawable(id).mutate();
        }

        private int size(Paint paint) { return Math.max(1, Math.round(paint.getTextSize())); }

        @Override public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return size(paint) + Math.max(1, Math.round(paint.getTextSize() * .22f));
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, Paint paint) {
            int size = size(paint);
            Paint.FontMetrics metrics = paint.getFontMetrics();
            float center = y + (metrics.ascent + metrics.descent) / 2f;
            icon.setTint(paint.getColor() | 0xff000000);
            icon.setAlpha(paint.getAlpha());
            icon.setBounds(0, 0, size, size);
            canvas.save();
            canvas.translate(x, center - size / 2f);
            icon.draw(canvas);
            canvas.restore();
        }
    }
}
