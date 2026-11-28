/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.calendar;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;
import com.android.calendar.event.EditEventActivity;
import ws.xsoh.etar.R;

/** Compatibility entry point for existing notifications, widgets and external event links.
 * It never displays a separate event detail page.
 */
public class EventInfoActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        forward(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        forward(intent);
    }

    private void forward(Intent incoming) {
        try {
            Uri uri = incoming == null ? null : incoming.getData();
            if (uri == null || !"content".equals(uri.getScheme())
                    || !android.provider.CalendarContract.AUTHORITY.equals(uri.getAuthority())
                    || uri.getPathSegments().size() < 2
                    || !"events".equals(uri.getPathSegments().get(0))
                    || Long.parseLong(uri.getPathSegments().get(1)) <= 0) {
                Toast.makeText(this, R.string.event_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent launch = new Intent(incoming).setClass(this, EditEventActivity.class);
            launch.setFlags(0);
            launch.putExtra(CalendarController.EVENT_EDIT_ON_LAUNCH, true);
            startActivity(launch);
        } catch (NumberFormatException e) {
            Toast.makeText(this, R.string.event_not_found, Toast.LENGTH_SHORT).show();
        } finally {
            finish();
        }
    }
}
