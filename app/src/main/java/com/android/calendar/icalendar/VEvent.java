/*
 * Copyright (C) 2014-2016 The CyanogenMod Project
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

package com.android.calendar.icalendar;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.UUID;

/**
 * Models the Event/VEvent component of the iCalendar format
 */
public class VEvent {

    // Valid property identifiers for an event component
    // TODO: only a partial list of attributes has been implemented, implement the rest
    public static String CLASS = "CLASS";
    public static String CREATED = "CREATED";
    public static String LOCATION = "LOCATION";
    public static String ORGANIZER = "ORGANIZER";
    public static String PRIORITY = "PRIORITY";
    public static String SEQ = "SEQ";
    public static String STATUS = "STATUS";
    public static String UID = "UID";
    public static String URL = "URL";
    public static String DTSTART = "DTSTART";
    public static String DTEND = "DTEND";
    public static String DURATION = "DURATION";
    public static String DTSTAMP = "DTSTAMP";
    public static String SUMMARY = "SUMMARY";
    public static String DESCRIPTION = "DESCRIPTION";
    public static String ATTENDEE = "ATTENDEE";
    public static String CATEGORIES = "CATEGORIES";
    public static String RRULE = "RRULE";

    // Stores the -arity of the attributes that this component can have
    private static HashMap<String, Integer> sPropertyList = new HashMap<String, Integer>();

    // Initialize the approved list of mProperties for a calendar event
    static {
        sPropertyList.put(CLASS,1);
        sPropertyList.put(CREATED,1);
        sPropertyList.put(LOCATION,1);
        sPropertyList.put(ORGANIZER,1);
        sPropertyList.put(PRIORITY,1);
        sPropertyList.put(SEQ,1);
        sPropertyList.put(STATUS,1);
        sPropertyList.put(UID,1);
        sPropertyList.put(URL,1);
        sPropertyList.put(DTSTART,1);
        sPropertyList.put(DTEND,1);
        sPropertyList.put(DURATION, 1);
        sPropertyList.put(DTSTAMP,1);
        sPropertyList.put(SUMMARY,1);
        sPropertyList.put(DESCRIPTION,1);
        sPropertyList.put(RRULE, 1);

        sPropertyList.put(ATTENDEE, Integer.MAX_VALUE);
        sPropertyList.put(CATEGORIES, Integer.MAX_VALUE);
        sPropertyList.put(CATEGORIES, Integer.MAX_VALUE);
    }

    // Stores attributes and their corresponding values belonging to the Event component
    public HashMap<String, String> mProperties;
    public HashMap<String, String> mPropertyParameters;

    public LinkedList<Attendee> mAttendees;
    public Organizer mOrganizer;
    public boolean hasAlarm;
    public boolean hasUnsupportedComponent;

    /**
     * Constructor
     */
    public VEvent() {
        mProperties = new HashMap<String, String>();
        mPropertyParameters = new HashMap<String, String>();
        mAttendees = new LinkedList<Attendee>();

        // Generate and add a unique identifier to this event - iCal requisite
        addProperty(UID , UUID.randomUUID().toString() + "@ws.xsoh.etar");
        addTimeStamp();
    }

    /**
     * For adding unary properties. For adding other property attributes , use the respective
     * component methods to create and add these special components.
     * @param property
     * @param value
     * @return
     */
    public boolean addProperty(String property, String value) {
        // Only unary-properties for now
        if (sPropertyList.containsKey(property) && sPropertyList.get(property) == 1 &&
                value != null) {
            mProperties.put(property, IcalendarUtils.cleanseString(value));
            return true;
        }
        return false;
    }

    /**
     * Returns the value of the requested event property or null if there isn't one
     */
    public String getProperty(String property) {
        if (ORGANIZER.equalsIgnoreCase(property) && mOrganizer != null) {
            return mOrganizer.mEmail;
        }
        return mProperties.get(property);
    }

    /**
     * Returns the parameters of the requested event property or null if there isn't one
     */
    public String getPropertyParameters(String property) {
        return mPropertyParameters.get(property);
    }

    /**
     * Add attendees to the event
     * @param attendee
     */
    public void addAttendee(Attendee attendee) {
        if(attendee != null) mAttendees.add(attendee);
    }

    /**
     * Add an Organizer to the Event
     * @param organizer
     */
    public void addOrganizer(Organizer organizer) {
        if (organizer != null) mOrganizer = organizer;
    }

    /**
     * Add an start date-time to the event
     */
    public void addEventStart(long startMillis, String timeZone) {
        if (startMillis < 0) return;

        String formattedDateTime = IcalendarUtils.getICalFormattedDateTime(startMillis, timeZone);
        addProperty(DTSTART, formattedDateTime);
    }

    /**
     * Add an end date-time for event
     */
    public void addEventEnd(long endMillis, String timeZone) {
        if (endMillis < 0) return;

        String formattedDateTime = IcalendarUtils.getICalFormattedDateTime(endMillis, timeZone);
        addProperty(DTEND, formattedDateTime);
    }

    /**
     * Timestamps the events with the current date-time
     */
    private void addTimeStamp() {
        String formattedDateTime = IcalendarUtils.getICalFormattedDateTime(
                System.currentTimeMillis(), "UTC");
        addProperty(DTSTAMP, formattedDateTime);
    }

    /**
     * Returns the iCal representation of the Event component
     */
    public String getICalFormattedString() {
        StringBuilder sb = new StringBuilder();

        // Add Event properties
        sb.append("BEGIN:VEVENT\n");
        for (String property : mProperties.keySet() ) {
            sb.append(property);
            String parameters = mPropertyParameters.get(property);
            if (parameters != null) sb.append(';').append(parameters);
            sb.append(':').append(mProperties.get(property)).append('\n');
        }

        // Enforce line length requirements
        sb = IcalendarUtils.enforceICalLineLength(sb);

        if (mOrganizer != null) {
            sb.append(mOrganizer.getICalFormattedString());
        }

        // Add event Attendees
        for (Attendee attendee : mAttendees) {
            sb.append(attendee.getICalFormattedString());
        }

        sb.append("END:VEVENT\n");

        return sb.toString();
    }

    public void populateFromEntries(ListIterator<String> iter) {
        int depth = 0;
        // A parsed event must not inherit the synthetic UID used for newly exported events.
        mProperties.clear();
        while (iter.hasNext()) {
            String line = iter.next();
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                // Continue
            } else if (line.equalsIgnoreCase("END:VEVENT") && depth == 0) {
                return;
            } else if (line.regionMatches(true, 0, "BEGIN:", 0, 6)) {
                if (line.equalsIgnoreCase("BEGIN:VALARM")) hasAlarm = true;
                else hasUnsupportedComponent = true;
                depth++;
            } else if (line.regionMatches(true, 0, "END:", 0, 4)) {
                if (depth == 0) throw new IllegalArgumentException("Invalid event component");
                depth--;
            } else if (depth != 0) {
                // Nested component properties must not overwrite VEVENT properties.
            } else if (line.regionMatches(true, 0, "ORGANIZER", 0, 9)) {
                String entry = parseTillNextAttribute(iter, line);
                mOrganizer = Organizer.populateFromICalString(entry);
            } else if (line.regionMatches(true, 0, "ATTENDEE", 0, 8)) {
                // Go one previous, so VEvent, parses current line
                iter.previous();

                // Offload to Attendee for parsing
                Attendee attendee = new Attendee();
                attendee.populateFromEntries(iter);
                mAttendees.add(attendee);
            } else if (line.contains(":")) {
                String entry = parseTillNextAttribute(iter, line);
                int indexOfFirstColon = entry.indexOf(":");
                int indexOfFirstParamDelimiter = entry.indexOf(";");
                String key;
                if (indexOfFirstParamDelimiter != -1 && indexOfFirstParamDelimiter < indexOfFirstColon) {
                    key = entry.substring(0, indexOfFirstParamDelimiter);
                    String params = entry.substring(indexOfFirstParamDelimiter + 1, indexOfFirstColon);
                    mPropertyParameters.put(key.toUpperCase(java.util.Locale.ROOT), params);
                } else {
                    key = entry.substring(0, indexOfFirstColon);
                }
                String value = entry.substring(indexOfFirstColon + 1);
                key = key.toUpperCase(java.util.Locale.ROOT);
                if (mProperties.containsKey(key) && ("RDATE".equals(key) || "EXDATE".equals(key)
                        || "RRULE".equals(key))) hasUnsupportedComponent = true;
                mProperties.put(key, value);
            }
        }
        throw new IllegalArgumentException("Unterminated event");
    }

    public static String parseTillNextAttribute(ListIterator<String> iter, String currentLine) {
        StringBuilder parse = new StringBuilder();
        parse.append(currentLine);
        while (iter.hasNext()) {
            String line = iter.next();
            if (line.startsWith(" ") || line.startsWith("\t")) {
                parse.append(line.substring(1));
            } else {
                iter.previous();
                break;
            }
        }
        return parse.toString();
    }

}
