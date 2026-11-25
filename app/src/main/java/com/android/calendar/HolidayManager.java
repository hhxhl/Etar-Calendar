package com.android.calendar;

import android.content.Context;
import android.util.AtomicFile;
import android.util.LruCache;
import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Annual official holiday arrangements, independent of calendar events. */
public final class HolidayManager {
    public enum Status { UNKNOWN, NORMAL, HOLIDAY, WORKDAY }
    private static final int MAX_BYTES = 65536;
    private static HolidayManager instance;
    private final Context context;
    private final LruCache<Integer, YearData> cache = new LruCache<>(8);

    private HolidayManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized HolidayManager getInstance(Context context) {
        if (instance == null) instance = new HolidayManager(context);
        return instance;
    }

    public synchronized Status getStatus(LocalDate date) {
        YearData data = load(date.getYear());
        if (data == null) return Status.UNKNOWN;
        if (data.workdays.contains(date)) return Status.WORKDAY;
        if (data.holidays.containsKey(date)) return Status.HOLIDAY;
        return Status.NORMAL;
    }

    public synchronized String getHolidayName(LocalDate date) {
        YearData data = load(date.getYear());
        return data == null ? null : data.holidays.get(date);
    }

    public synchronized int getVersion(int year) {
        YearData data = load(year);
        return data == null ? 0 : data.version;
    }

    public synchronized int importDataForYear(InputStream input, int year, int version)
            throws IOException, JSONException {
        byte[] bytes = read(input);
        YearData data = parse(bytes);
        if (data.year != year || data.version != version) throw new JSONException("Unexpected data version");
        if (version <= getVersion(year)) return 0;
        importData(new ByteArrayInputStream(bytes));
        return 1;
    }

    /** Validates before writing; an older version cannot replace a newer one. */
    public synchronized void importData(InputStream input) throws IOException, JSONException {
        byte[] bytes = read(input);
        YearData data = parse(bytes);
        if (data.version < getVersion(data.year)) throw new IOException("Older holiday data");
        AtomicFile file = file(data.year);
        File parent = new File(context.getFilesDir(), "holidays");
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create holiday directory");
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            file.failWrite(output);
            throw e;
        }
        cache.put(data.year, data);
    }

    private AtomicFile file(int year) {
        return new AtomicFile(new File(context.getFilesDir(), "holidays/" + year + ".json"));
    }

    private YearData load(int year) {
        YearData cached = cache.get(year);
        if (cached != null) return cached;
        YearData bundled = null, local = null;
        try (InputStream in = context.getAssets().open("holidays/" + year + ".json")) {
            bundled = parse(read(in));
            if (bundled.year != year) bundled = null;
        } catch (Exception ignored) {}
        try (InputStream in = file(year).openRead()) {
            local = parse(read(in));
            if (local.year != year) local = null;
        } catch (Exception ignored) {}
        YearData data = local != null && (bundled == null || local.version >= bundled.version)
                ? local : bundled;
        if (data != null) cache.put(year, data);
        return data;
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (out.size() + count > MAX_BYTES) throw new IOException("Holiday data too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static YearData parse(byte[] bytes) throws JSONException {
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        int year = json.getInt("year"), version = json.getInt("version");
        if (year < 1900 || year > 2100 || version < 1) throw new JSONException("Invalid metadata");
        YearData data = new YearData(year, version);
        JSONObject holidays = json.getJSONObject("holidays");
        Iterator<String> keys = holidays.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String name = holidays.getString(key).trim();
            if (name.isEmpty() || name.length() > 64) throw new JSONException("Invalid holiday name");
            data.holidays.put(date(key, year), name);
        }
        JSONArray workdays = json.getJSONArray("workdays");
        for (int i = 0; i < workdays.length(); i++) {
            LocalDate date = date(workdays.getString(i), year);
            if (data.holidays.containsKey(date) || !data.workdays.add(date))
                throw new JSONException("Conflicting workday");
        }
        if (data.holidays.isEmpty()) throw new JSONException("Empty holidays");
        return data;
    }

    private static LocalDate date(String value, int year) throws JSONException {
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() == year && date.toString().equals(value)) return date;
        } catch (RuntimeException ignored) {}
        throw new JSONException("Invalid date: " + value);
    }

    private static final class YearData {
        final int year, version;
        final Map<LocalDate, String> holidays = new HashMap<>();
        final Set<LocalDate> workdays = new HashSet<>();
        YearData(int year, int version) { this.year = year; this.version = version; }
    }
}
