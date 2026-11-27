package com.android.calendar;

import android.content.Context;
import android.text.format.DateUtils;
import java.util.Formatter;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import ws.xsoh.etar.R;

/** Common editor confirmation; each editor owns its draft and persistence. */
public final class ItemEditorUi {
    private ItemEditorUi() {}

    /** Format in the item's own zone without changing the process-wide default timezone. */
    public static String formatDateTime(Context context, long millis, int flags, String zone) {
        try (Formatter formatter = new Formatter()) {
            return DateUtils.formatDateRange(context, formatter, millis, millis, flags, zone).toString();
        }
    }

    public static void confirmDiscard(Context context, Runnable discard) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.discard_event_changes)
                .setPositiveButton(R.string.discard, (dialog, which) -> discard.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
