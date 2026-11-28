# Etar task sync provider

The only task database is Etar's `tasks.db`. This endpoint implements the DMFS subset consumed by DAVx5's task sync backend, with a single Etar extension for absolute alarms.

- Release package: `ws.xsoh.etar`.
- Authority: `ws.xsoh.etar.tasks` (derived from applicationId).
- Permissions: `ws.xsoh.etar.permission.READ_TASKS`, `ws.xsoh.etar.permission.WRITE_TASKS` (signature).
- Only `at.bitfire.davdroid` signed with the same certificate is authorized.
- Paths: `tasklists[/id]`, `tasks[/id]`, `properties[/id]`. All sync operations require the DMFS account_name, account_type and caller_is_syncadapter=true parameters, except the existing DAVx5 account rename operation.
- CRUD and transactional applyBatch support; tasks?load_properties=1 supports the DMFS relation query.
- No local list is exposed to DAVx5. Etar marks synced local edits dirty and sends the sync notification. Synced local deletion creates a tombstone; the sync adapter physically deletes it after synchronization.
- Fields use the DMFS contract; tasklists.sync_version is the serialized sync state, tasklists.sync1 is collection URL, tasks.sync_version is SEQUENCE, tasks.sync1 is ETag, tasks.sync2 is sync flags.
- All-day DTSTART and DUE share is_allday. A synced DUE must be later than DTSTART.
- Alarm data resides in properties: mimetype vnd.android.cursor.item/alarm, data0 = minutes before, data1 = DUE (1) or DTSTART (2), data2 = message, data3 = action. Etar extension data4 = absolute UTC epoch milliseconds (nullable; when set, data0/data1 are null). DAVx5 needs to preserve absolute triggers through this field instead of converting to relative minutes.
- alarm_at/alarm_fired, recurrence_anchor, repeat_minute and show_countdown are local-only fields. Recalculate derived alarm state after committed provider mutations.
- Etar currently presents one alarm per task, four preset recurrence rules and no recurrence exceptions; remote additional data is preserved where the existing DMFS mapping supports it. Moving existing remote tasks across lists is disabled until the delete-and-create sync operation is implemented.
- No database migration from the previous development schema. Use a clean task database when testing.

DAVx5 has not yet been modified or tested with this provider.
