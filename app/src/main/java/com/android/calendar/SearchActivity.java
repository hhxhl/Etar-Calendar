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

import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import androidx.core.content.ContextCompat;
import com.android.calendar.tasks.TaskStore;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.CalendarContract.Events;
import android.provider.SearchRecentSuggestions;
import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;

import com.android.calendar.agenda.DatedAgendaFragment;
import com.android.calendar.settings.SettingsActivity;
import com.android.calendar.theme.DynamicThemeKt;

import ws.xsoh.etar.R;
import ws.xsoh.etar.databinding.SimpleFrameLayoutMaterialBinding;

/** Global item search; results reuse the agenda list and open the corresponding editor. */
public class SearchActivity extends AppCompatActivity implements SearchView.OnQueryTextListener {
    private static final String BUNDLE_KEY_RESTORE_SEARCH_QUERY = "key_restore_search_query";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String query = "";
    private SearchView searchView;
    private boolean observing;
    private BroadcastReceiver timeChangesReceiver;
    private boolean observingTasks;
    private final BroadcastReceiver taskChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshResults(); }
    };

    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { refreshResults(); }
    };

    private final Runnable timeChangesUpdater = new Runnable() {
        @Override public void run() {
            Utils.setMidnightUpdater(handler, this, Utils.getTimeZone(SearchActivity.this, this));
            invalidateOptionsMenu();
            refreshResults();
        }
    };

    @Override protected void onCreate(Bundle state) {
        DynamicThemeKt.applyTheme(this);
        super.onCreate(state);
        SimpleFrameLayoutMaterialBinding binding =
                SimpleFrameLayoutMaterialBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.include.toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        String initialQuery = state == null
                ? getIntent().getStringExtra(SearchManager.QUERY)
                : state.getString(BUNDLE_KEY_RESTORE_SEARCH_QUERY, "");
        query = initialQuery == null ? "" : initialQuery.trim();
        if (results() == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.body_frame, DatedAgendaFragment.forSearch(
                            Utils.timeFromIntentInMillis(getIntent()), query))
                    .commitNow();
        }
        if (state == null) rememberQuery();
    }

    private DatedAgendaFragment results() {
        Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.body_frame);
        return fragment instanceof DatedAgendaFragment ? (DatedAgendaFragment) fragment : null;
    }

    private void refreshResults() {
        DatedAgendaFragment fragment = results();
        if (fragment != null) fragment.eventsChanged();
    }

    private void rememberQuery() {
        if (query.isEmpty()) return;
        new SearchRecentSuggestions(this, Utils.getSearchAuthority(this),
                CalendarRecentSuggestionsProvider.MODE).saveRecentQuery(query, null);
    }

    private void search(String text) {
        query = text == null ? "" : text.trim();
        rememberQuery();
        DatedAgendaFragment fragment = results();
        if (fragment != null) fragment.setSearchQuery(query);
        if (searchView != null) {
            searchView.setQuery(query, false);
            searchView.clearFocus();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Intent.ACTION_SEARCH.equals(intent.getAction()))
            search(intent.getStringExtra(SearchManager.QUERY));
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.search_title_bar, menu);
        MenuItem item = menu.findItem(R.id.action_search);
        item.expandActionView();
        item.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
            @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                finish();
                return false;
            }
        });
        searchView = (SearchView) item.getActionView();
        Utils.setUpSearchView(searchView, this);
        searchView.setOnQueryTextListener(this);
        searchView.setQuery(query, false);
        searchView.clearFocus();
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem today = menu.findItem(R.id.action_today);
        if (today != null && today.getIcon() instanceof LayerDrawable)
            Utils.setTodayIcon((LayerDrawable) today.getIcon(), this,
                    Utils.getTimeZone(this, timeChangesUpdater));
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_today) {
            DatedAgendaFragment fragment = results();
            if (fragment != null) fragment.showToday();
            return true;
        }
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override public boolean onQueryTextChange(String text) { return false; }

    @Override public boolean onQueryTextSubmit(String text) {
        search(text);
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        Utils.setMidnightUpdater(handler, timeChangesUpdater, Utils.getTimeZone(this, timeChangesUpdater));
        timeChangesReceiver = Utils.setTimeChangesReceiver(this, timeChangesUpdater);
        invalidateOptionsMenu();
        ContextCompat.registerReceiver(this, taskChanges,
                new IntentFilter(TaskStore.ACTION_TASKS_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        observingTasks = true;
        if (Utils.isCalendarPermissionGranted(this, false)) {
            getContentResolver().registerContentObserver(Events.CONTENT_URI, true, observer);
            observing = true;
        }
    }

    @Override protected void onPause() {
        if (observingTasks) {
            unregisterReceiver(taskChanges);
            observingTasks = false;
        }
        if (observing) {
            getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
        Utils.resetMidnightUpdater(handler, timeChangesUpdater);
        if (timeChangesReceiver != null) {
            Utils.clearTimeChangesReceiver(this, timeChangesReceiver);
            timeChangesReceiver = null;
        }
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString(BUNDLE_KEY_RESTORE_SEARCH_QUERY, query);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        CalendarController.removeInstance(this);
    }
}
