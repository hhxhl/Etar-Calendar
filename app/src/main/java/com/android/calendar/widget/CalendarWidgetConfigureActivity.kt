/*
 * Copyright (C) 2026 The Etar Project
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

package com.android.calendar.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.database.Cursor
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract.Calendars
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.android.calendar.Utils
import ws.xsoh.etar.R

class CalendarWidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private val calendarItems = mutableListOf<CalendarItem>()
    private lateinit var adapter: CalendarAdapter

    data class CalendarItem(
        val id: Long,
        val name: String,
        val account: String,
        val color: Int,
        var isChecked: Boolean,
        val isTaskList: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_widget_configure)
        setTitle(R.string.widget_configure_title)

        val rootView = findViewById<View>(R.id.root_view)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            windowInsets
        }

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val opacitySlider = findViewById<SeekBar>(R.id.widget_opacity_slider)
        val opacityValue = findViewById<TextView>(R.id.widget_opacity_value)
        opacitySlider.progress = savedInstanceState?.getInt("widget_opacity")
            ?: WidgetUtils.getBackgroundOpacity(this, appWidgetId)
        fun updateOpacityValue(value: Int) {
            opacityValue.text = java.text.NumberFormat.getPercentInstance().format(value / 100.0)
        }
        updateOpacityValue(opacitySlider.progress)
        opacitySlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateOpacityValue(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        loadCalendars()

        val listView = findViewById<ListView>(R.id.calendar_list)
        adapter = CalendarAdapter()
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            calendarItems[position].isChecked = !calendarItems[position].isChecked
            adapter.notifyDataSetChanged()
        }

        findViewById<Button>(R.id.save_button).setOnClickListener {
            saveAndFinish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("widget_opacity", findViewById<SeekBar>(R.id.widget_opacity_slider).progress)
        super.onSaveInstanceState(outState)
    }

    private fun loadCalendars() {
        val savedIds = WidgetUtils.getWidgetCalendarIds(this, appWidgetId)

        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.CALENDAR_COLOR,
            Calendars.VISIBLE
        )

        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                Calendars.CONTENT_URI,
                projection,
                "${Calendars.SYNC_EVENTS}=1",
                null,
                "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}"
            )

            if (cursor != null) {
                val idCol = cursor.getColumnIndexOrThrow(Calendars._ID)
                val nameCol = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_DISPLAY_NAME)
                val accountCol = cursor.getColumnIndexOrThrow(Calendars.ACCOUNT_NAME)
                val colorCol = cursor.getColumnIndexOrThrow(Calendars.CALENDAR_COLOR)
                val visibleCol = cursor.getColumnIndexOrThrow(Calendars.VISIBLE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val account = cursor.getString(accountCol) ?: ""
                    val rawColor = cursor.getInt(colorCol)
                    val displayColor = rawColor
                    val visible = cursor.getInt(visibleCol) == 1

                    val isChecked = if (savedIds != null) {
                        savedIds.contains(id.toString())
                    } else {
                        visible
                    }

                    calendarItems.add(CalendarItem(id, name, account, displayColor, isChecked))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            cursor?.close()
        }

        val selectedTasks = WidgetUtils.getWidgetTaskListIds(this, appWidgetId)
        for (list in com.android.calendar.tasks.TaskStore.get(this).lists()) {
            val name = list.name.ifEmpty { getString(R.string.tasks_default_list) }
            val color = if (list.color == 0) getColor(R.color.event_center)
                else list.color
            calendarItems.add(CalendarItem(list.id, name, getString(R.string.tasks_title),
                color, selectedTasks?.contains(list.id.toString()) ?: true, true))
        }
    }

    private fun saveAndFinish() {
        val selectedIds = calendarItems.filter { it.isChecked && !it.isTaskList }.map { it.id.toString() }.toSet()
        val selectedTasks = calendarItems.filter { it.isChecked && it.isTaskList }.map { it.id.toString() }.toSet()
        val previousIds = WidgetUtils.getWidgetCalendarIds(this, appWidgetId)
        val previousTasks = WidgetUtils.getWidgetTaskListIds(this, appWidgetId)
        WidgetUtils.saveWidgetCalendarIds(this, appWidgetId, selectedIds)
        WidgetUtils.saveWidgetTaskListIds(this, appWidgetId, selectedTasks)
        WidgetUtils.saveBackgroundOpacity(this, appWidgetId,
            findViewById<SeekBar>(R.id.widget_opacity_slider).progress)

        // Update the selected widget before returning to the launcher.
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val providerName = appWidgetManager.getAppWidgetInfo(appWidgetId)?.provider?.className
        val provider = when (providerName) {
            CalendarAppWidgetProvider::class.java.name -> CalendarAppWidgetProvider()
            MonthAppWidgetProvider::class.java.name -> MonthAppWidgetProvider()
            WeekAppWidgetProvider::class.java.name -> WeekAppWidgetProvider()
            else -> null
        }
        val layoutId = when (providerName) {
            CalendarAppWidgetProvider::class.java.name -> R.layout.appwidget
            MonthAppWidgetProvider::class.java.name -> R.layout.month_appwidget
            WeekAppWidgetProvider::class.java.name -> R.layout.week_appwidget
            else -> 0
        }
        if (provider != null && previousIds == selectedIds && previousTasks == selectedTasks && layoutId != 0) {
            // Only opacity changed: leave the collection adapter and current layout intact.
            WidgetUtils.updateBackgroundOpacity(this, appWidgetId, layoutId)
        } else if (provider != null) {
            provider.onUpdate(this, appWidgetManager, intArrayOf(appWidgetId))
        } else {
            Utils.sendUpdateWidgetIntent(this)
        }

        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }

    private inner class CalendarAdapter : BaseAdapter() {
        override fun getCount(): Int = calendarItems.size
        override fun getItem(position: Int): CalendarItem = calendarItems[position]
        override fun getItemId(position: Int): Long = getItem(position).let { if (it.isTaskList) -it.id - 1 else it.id }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@CalendarWidgetConfigureActivity)
                .inflate(R.layout.widget_configure_item, parent, false)

            val item = getItem(position)

            val colorView = view.findViewById<View>(R.id.calendar_color)
            val nameView = view.findViewById<TextView>(R.id.calendar_name)
            val accountView = view.findViewById<TextView>(R.id.account_name)
            val checkBox = view.findViewById<CheckBox>(R.id.calendar_checkbox)

            nameView.text = item.name
            accountView.text = item.account
            checkBox.isChecked = item.isChecked

            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(item.color)
            }
            colorView.background = drawable

            return view
        }
    }
}
