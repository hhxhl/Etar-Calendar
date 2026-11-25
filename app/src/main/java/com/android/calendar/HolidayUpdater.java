package com.android.calendar;

import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;
import org.json.JSONArray;
import java.nio.charset.StandardCharsets;

public final class HolidayUpdater {
    private static final String BASE = "https://raw.githubusercontent.com/hhxhl/Etar-Calendar/master-hhxhl/app/src/main/assets/holidays/";
    private HolidayUpdater() {}

    public static int update(HolidayManager manager) throws Exception {
        JSONArray years = new JSONObject(new String(download("index.json"), StandardCharsets.UTF_8))
                .getJSONArray("years");
        if (years.length() > 201) throw new IOException("Invalid index");
        int count = 0;
        for (int i = 0; i < years.length(); i++) {
            JSONObject item = years.getJSONObject(i);
            int year = item.getInt("year"), version = item.getInt("version");
            if (year < 1900 || year > 2100 || version < 1) throw new IOException("Invalid index");
            if (version > manager.getVersion(year)) {
                count += manager.importDataForYear(new ByteArrayInputStream(download(year + ".json")), year, version);
            }
        }
        return count;
    }

    private static byte[] download(String path) throws IOException {
        HttpsURLConnection c = (HttpsURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > 65536) throw new IOException("Data too large");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
}
