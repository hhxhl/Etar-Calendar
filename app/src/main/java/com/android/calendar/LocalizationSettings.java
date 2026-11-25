package com.android.calendar;

import android.content.Context;
import java.util.Locale;

public final class LocalizationSettings {
    private LocalizationSettings() {}
    public static boolean enabled(Context context, String key) {
        Locale locale = context.getResources().getConfiguration().getLocales().get(0);
        return context.getSharedPreferences(Utils.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(key, "zh".equals(locale.getLanguage()) && "CN".equals(locale.getCountry()));
    }
    public static boolean lunar(Context c) { return enabled(c, "local_lunar"); }
    public static boolean festivals(Context c) { return enabled(c, "local_festivals"); }
    public static boolean terms(Context c) { return enabled(c, "local_terms"); }
    public static boolean holidays(Context c) { return enabled(c, "local_holidays"); }
}
