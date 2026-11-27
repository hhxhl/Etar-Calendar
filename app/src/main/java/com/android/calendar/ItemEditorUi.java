package com.android.calendar;

import android.content.Context;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import ws.xsoh.etar.R;

/** Common editor confirmation; each editor owns its draft and persistence. */
public final class ItemEditorUi {
    private ItemEditorUi() {}

    public static void confirmDiscard(Context context, Runnable discard) {
        new MaterialAlertDialogBuilder(context)
                .setMessage(R.string.discard_event_changes)
                .setPositiveButton(R.string.discard, (dialog, which) -> discard.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
