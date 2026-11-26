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

package com.android.calendar.icalendar

import android.content.Context
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

class IcalExporter(private val context: Context) {

    companion object {
        private const val TAG = "IcalExporter"

        private val PROJECTION = arrayOf(
            Events._ID,                 // 0
            Events.TITLE,               // 1
            Events.DESCRIPTION,         // 2
            Events.EVENT_LOCATION,      // 3
            Events.DTSTART,             // 4
            Events.DTEND,               // 5
            Events.EVENT_TIMEZONE,      // 6
            Events.ALL_DAY,             // 7
            Events.RRULE,               // 8
            Events.DURATION,            // 9
            Events._SYNC_ID,            // 10
            Events.ORGANIZER,           // 11
            Events.UID_2445,            // 12
            Events.RDATE,               // 13
            Events.EXDATE,              // 14
            Events.ORIGINAL_ID,         // 15
            Events.ORIGINAL_SYNC_ID     // 16
        )

        private const val INDEX_ID = 0
        private const val INDEX_TITLE = 1
        private const val INDEX_DESCRIPTION = 2
        private const val INDEX_LOCATION = 3
        private const val INDEX_DTSTART = 4
        private const val INDEX_DTEND = 5
        private const val INDEX_TIMEZONE = 6
        private const val INDEX_ALL_DAY = 7
        private const val INDEX_RRULE = 8
        private const val INDEX_DURATION = 9
        private const val INDEX_SYNC_ID = 10
        private const val INDEX_ORGANIZER = 11
    }

    @Synchronized
    private fun exportNamespace(): String {
        val prefs = context.getSharedPreferences("ical_export", Context.MODE_PRIVATE)
        return prefs.getString("namespace", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("namespace", it).apply()
        }
    }

    fun exportCalendar(calendarId: Long): VCalendar? {
        val calendar = VCalendar()
        calendar.addProperty(VCalendar.VERSION, "2.0")
        calendar.addProperty(VCalendar.PRODID, VCalendar.PRODUCT_IDENTIFIER)
        calendar.addProperty(VCalendar.CALSCALE, "GREGORIAN")

        val selection = "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0"
        val selectionArgs = arrayOf(calendarId.toString())

        context.contentResolver.query(
            Events.CONTENT_URI,
            PROJECTION,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val event = VEvent()

                val title = cursor.getString(INDEX_TITLE)
                val description = cursor.getString(INDEX_DESCRIPTION)
                val location = cursor.getString(INDEX_LOCATION)
                val dtStart = cursor.getLong(INDEX_DTSTART)
                val dtEnd = cursor.getLong(INDEX_DTEND)
                val timeZone = cursor.getString(INDEX_TIMEZONE) ?: "UTC"
                val allDay = cursor.getInt(INDEX_ALL_DAY) != 0
                val rrule = cursor.getString(INDEX_RRULE)
                val uid = cursor.getString(12)
                val duration = cursor.getString(INDEX_DURATION)
                val rdate = cursor.getString(13)
                val exdate = cursor.getString(14)
                // Do not export detached instances as unrelated independent events.
                if (!cursor.isNull(15) || !cursor.isNull(16)) return null
                val organizer = cursor.getString(INDEX_ORGANIZER)

                event.addProperty(VEvent.SUMMARY, title)
                event.addProperty(VEvent.DESCRIPTION, description)
                event.addProperty(VEvent.LOCATION, location)

                val dateFormat = DateTimeFormatter.ofPattern("uuuuMMdd", Locale.ROOT)
                val timeFormat = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss", Locale.ROOT)
                val zone = if (allDay) ZoneOffset.UTC else runCatching { ZoneId.of(timeZone) }.getOrDefault(ZoneOffset.UTC)
                fun putDate(key: String, millis: Long) {
                    val time = Instant.ofEpochMilli(millis).atZone(zone)
                    if (allDay) {
                        event.mProperties[key] = dateFormat.format(time)
                        event.mPropertyParameters[key] = "VALUE=DATE"
                    } else if (zone == ZoneOffset.UTC || zone.id == "UTC" || zone.id == "GMT") {
                        event.mProperties[key] = timeFormat.format(time) + "Z"
                    } else {
                        event.mProperties[key] = timeFormat.format(time)
                        event.mPropertyParameters[key] = "TZID=${zone.id}"
                    }
                }
                putDate(VEvent.DTSTART, dtStart)
                if (!duration.isNullOrEmpty() && (!rrule.isNullOrEmpty() || !rdate.isNullOrEmpty())) {
                    // Calendar Provider accepts P3600S; RFC 5545 uses PT3600S.
                    event.mProperties[VEvent.DURATION] = duration.replace(Regex("^P([0-9]+)S$"), "PT\$1S")
                } else if (!cursor.isNull(INDEX_DTEND)) {
                    putDate(VEvent.DTEND, dtEnd)
                } else if (allDay) {
                    putDate(VEvent.DTEND, dtStart + 86400000L)
                }
                if (!rrule.isNullOrEmpty()) event.mProperties[VEvent.RRULE] = rrule
                if (!rdate.isNullOrEmpty()) {
                    event.mProperties["RDATE"] = rdate
                    if (allDay) event.mPropertyParameters["RDATE"] = "VALUE=DATE"
                }
                if (!exdate.isNullOrEmpty()) {
                    event.mProperties["EXDATE"] = exdate
                    if (allDay) event.mPropertyParameters["EXDATE"] = "VALUE=DATE"
                }
                event.mProperties[VEvent.UID] = uid?.takeIf { it.isNotEmpty() }
                    ?: "${exportNamespace()}-${calendarId}-${cursor.getLong(INDEX_ID)}@ws.xsoh.etar"

                if (!organizer.isNullOrEmpty()) {
                    event.addOrganizer(Organizer(organizer, organizer))
                }

                calendar.addEvent(event)
            }
        } ?: return null

        return calendar
    }
}
