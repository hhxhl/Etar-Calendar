package com.android.calendar.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.SwitchPreferenceCompat;

import ws.xsoh.etar.R;

/** Preserve AndroidX preference persistence and accessibility while using MaterialSwitch. */
public final class MaterialSwitchPreference extends SwitchPreferenceCompat {
    public MaterialSwitchPreference(Context context) {
        this(context, null);
    }

    public MaterialSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_material_switch);
    }
}
