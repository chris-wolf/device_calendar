package com.builttoroam.devicecalendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.builttoroam.devicecalendar.models.Event
import com.builttoroam.devicecalendar.models.RecurrenceRule
import com.builttoroam.devicecalendar.models.Reminder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.flutter.plugin.common.MethodChannel
import org.dmfs.rfc5545.recur.Freq
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Comprehensive on-device regression suite for all disappearing-event causes and
 * recurring-event bugs in the device_calendar fork.
 */
@RunWith(AndroidJUnit4::class)
class ForkDisappearingEventsTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR
    )

    private lateinit var context: Context
    private lateinit var delegate: CalendarDelegate
    private val createdCalendarIds = mutableListOf<Pair<Long, String>>()
    private val gson = Gson()

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val TEST_ACCOUNT_A = "fork_test_account_a"
        private const val TEST_ACCOUNT_B = "fork_test_account_b"
    }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        delegate = CalendarDelegate(null, context)
        createdCalendarIds.clear()
    }

    @After
    fun tearDown() {
        for ((calId, accountName) in createdCalendarIds) {
            try {
                val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calId)
                    .buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
                    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    .build()
                context.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
        createdCalendarIds.clear()
    }

    // -------------------------------------------------------------------------
    // Helper wrappers for CalendarDelegate (synchronous via CountDownLatch)
    // -------------------------------------------------------------------------

    private data class CallResult(
        val value: Any? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null
    ) {
        val isSuccess: Boolean get() = errorCode == null
    }

    private fun awaitResult(block: (MethodChannel.Result) -> Unit): CallResult {
        val latch = CountDownLatch(1)
        var callResult: CallResult? = null
        val callback = object : MethodChannel.Result {
            override fun success(result: Any?) {
                callResult = CallResult(value = result)
                latch.countDown()
            }

            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                callResult = CallResult(errorCode = errorCode, errorMessage = errorMessage)
                latch.countDown()
            }

            override fun notImplemented() {
                callResult = CallResult(errorCode = "NOT_IMPLEMENTED")
                latch.countDown()
            }
        }
        block(callback)
        assertTrue("CalendarDelegate callback timed out", latch.await(15, TimeUnit.SECONDS))
        return callResult!!
    }

    private fun syncAdapterUri(base: Uri, accountName: String): Uri {
        return base.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
    }

    /** Creates a pre-existing legacy local calendar with SYNC_EVENTS = 0 (like older versions of device_calendar). */
    private fun createLegacyLocalCalendar(
        name: String,
        accountName: String = TEST_ACCOUNT_A,
        syncEvents: Int = 0,
        visible: Int = 1
    ): Long {
        val uri = syncAdapterUri(Calendars.CONTENT_URI, accountName)
        val cv = ContentValues().apply {
            put(Calendars.NAME, name)
            put(Calendars.CALENDAR_DISPLAY_NAME, name)
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.SYNC_EVENTS, syncEvents)
            put(Calendars.VISIBLE, visible)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.CALENDAR_COLOR, Color.BLUE)
            put(Calendars.OWNER_ACCOUNT, accountName)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val inserted = context.contentResolver.insert(uri, cv)
        assertNotNull("Failed to insert legacy calendar", inserted)
        val calId = ContentUris.parseId(inserted!!)
        createdCalendarIds.add(calId to accountName)
        return calId
    }

    private fun createCalendarViaDelegate(
        name: String,
        accountName: String = TEST_ACCOUNT_A
    ): Long {
        val res = awaitResult { delegate.createCalendar(name, "0xFF0000FF", accountName, it) }
        assertTrue("createCalendar failed: ${res.errorMessage}", res.isSuccess)
        val calId = (res.value as String).toLong()
        createdCalendarIds.add(calId to accountName)
        return calId
    }

    private fun insertRawEvent(
        calId: Long,
        accountName: String,
        title: String,
        dtStart: Long,
        dtEnd: Long? = null,
        duration: String? = null,
        rrule: String? = null,
        allDay: Int = 0,
        syncId: String? = null
    ): Long {
        val cv = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            put(Events.DTSTART, dtStart)
            if (dtEnd != null) put(Events.DTEND, dtEnd)
            if (duration != null) put(Events.DURATION, duration)
            if (rrule != null) put(Events.RRULE, rrule)
            put(Events.ALL_DAY, allDay)
            put(Events.EVENT_TIMEZONE, if (allDay == 1) "UTC" else TimeZone.getDefault().id)
            if (syncId != null) put(Events._SYNC_ID, syncId)
        }
        val inserted = context.contentResolver.insert(syncAdapterUri(Events.CONTENT_URI, accountName), cv)
        assertNotNull("Failed to insert raw event", inserted)
        return ContentUris.parseId(inserted!!)
    }

    private fun getSyncEventsValue(calId: Long): Int {
        context.contentResolver.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, calId),
            arrayOf(Calendars.SYNC_EVENTS),
            null, null, null
        )?.use { c ->
            if (c.moveToFirst()) return c.getInt(0)
        }
        return -1
    }

    private fun retrieveEventsViaDelegate(
        calId: Long,
        startDate: Long?,
        endDate: Long?,
        eventIds: List<String> = emptyList()
    ): List<Map<String, Any?>> {
        val res = awaitResult {
            delegate.retrieveEvents(calId.toString(), startDate, endDate, eventIds, it)
        }
        assertTrue("retrieveEvents failed: ${res.errorCode} ${res.errorMessage}", res.isSuccess)
        val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
        return gson.fromJson(res.value as String, type)
    }

    private fun queryRawInstancesCount(calId: Long, begin: Long, end: Long): Int {
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        context.contentResolver.query(
            builder.build(),
            arrayOf(Instances.EVENT_ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} != 1",
            arrayOf(calId.toString()),
            null
        )?.use { return it.count }
        return 0
    }

    private fun triggerGlobalClearInstanceRange(calId: Long, accountName: String) {
        val now = System.currentTimeMillis()
        val tempId = insertRawEvent(
            calId = calId,
            accountName = accountName,
            title = "temp_clear_trigger",
            dtStart = now,
            duration = "PT1H",
            rrule = "FREQ=DAILY;COUNT=1"
        )
        context.contentResolver.delete(
            syncAdapterUri(ContentUris.withAppendedId(Events.CONTENT_URI, tempId), accountName),
            null, null
        )
    }

    private fun makeEvent(
        calId: Long,
        title: String,
        startMs: Long,
        endMs: Long,
        allDay: Boolean = false,
        rrule: RecurrenceRule? = null,
        eventId: String? = null,
        reminders: MutableList<Reminder> = mutableListOf()
    ): Event {
        return Event().apply {
            this.calendarId = calId.toString()
            this.eventId = eventId
            this.eventTitle = title
            this.eventStartDate = startMs
            this.eventEndDate = endMs
            this.eventAllDay = allDay
            this.eventStartTimeZone = if (allDay) "UTC" else TimeZone.getDefault().id
            this.eventEndTimeZone = if (allDay) "UTC" else TimeZone.getDefault().id
            this.recurrenceRule = rrule
            this.reminders = reminders
        }
    }

    private fun alignedNow(): Long {
        val now = System.currentTimeMillis()
        return now - (now % HOUR_MS) + 2 * HOUR_MS
    }

    private fun utcMidnightDaysFromNow(daysOffset: Int): Long {
        val now = System.currentTimeMillis()
        val todayMidnightUtc = now - (now % DAY_MS)
        return todayMidnightUtc + daysOffset * DAY_MS
    }

    // =========================================================================
    // PART 1: PRE-EXISTING SYNC_EVENTS = 0 LOCAL CALENDARS (EXISTING USERS!)
    // =========================================================================

    @Test
    fun test01_preExistingSyncEvents0Calendar_repairedByRetrieveCalendars_andEventsRecover() {
        // Simulate an existing user's local calendar created before SYNC_EVENTS=1 fix
        val calId = createLegacyLocalCalendar("Pre-existing Local Cal", TEST_ACCOUNT_A, syncEvents = 0)
        val base = alignedNow()
        insertRawEvent(calId, TEST_ACCOUNT_A, "Standalone 1", base, dtEnd = base + HOUR_MS)
        insertRawEvent(calId, TEST_ACCOUNT_A, "Standalone 2", base + DAY_MS, dtEnd = base + DAY_MS + HOUR_MS)

        // Trigger global CalendarMetaData reset (e.g. another calendar deleted a recurring event)
        val otherCalId = createLegacyLocalCalendar("Other Cal", TEST_ACCOUNT_B, syncEvents = 1)
        triggerGlobalClearInstanceRange(otherCalId, TEST_ACCOUNT_B)

        // Confirm events ALREADY vanished before plugin opens
        assertEquals(0, getSyncEventsValue(calId))
        assertEquals(0, queryRawInstancesCount(calId, base - DAY_MS, base + 5 * DAY_MS))

        // App calls retrieveCalendars() on startup -> must auto-repair SYNC_EVENTS=1 AND recover vanished events!
        val calRes = awaitResult { delegate.retrieveCalendars(it) }
        assertTrue("retrieveCalendars failed: ${calRes.errorMessage}", calRes.isSuccess)

        assertEquals(
            "Pre-existing local calendar SYNC_EVENTS must be repaired to 1 by retrieveCalendars()",
            1,
            getSyncEventsValue(calId)
        )

        val retrieved = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 5 * DAY_MS)
        assertEquals(
            "Already-vanished events in pre-existing local calendar must reappear after retrieveCalendars()",
            2,
            retrieved.size
        )
    }

    @Test
    fun test02_preExistingSyncEvents0Calendar_deleteRecurringWithException_doesNotWipeCalendar() {
        val calId = createLegacyLocalCalendar("Legacy Cal Delete Exception", TEST_ACCOUNT_A, syncEvents = 0)
        val base = alignedNow()

        // Create standalone event + recurring event via raw provider (simulating pre-existing data)
        insertRawEvent(calId, TEST_ACCOUNT_A, "Keep Me", base, dtEnd = base + HOUR_MS)
        val recId = insertRawEvent(
            calId, TEST_ACCOUNT_A, "Series With Exception",
            base + 2 * HOUR_MS, duration = "PT1H", rrule = "FREQ=DAILY;COUNT=5",
            syncId = "legacy-sync-id-1"
        )
        // Insert an exception row tied to recId
        val exUri = syncAdapterUri(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, recId), TEST_ACCOUNT_A)
        val exCv = ContentValues().apply {
            put(Events.ORIGINAL_INSTANCE_TIME, base + 2 * HOUR_MS + DAY_MS)
            put(Events.TITLE, "Edited Instance")
            put(Events.DTSTART, base + 2 * HOUR_MS + DAY_MS)
            put(Events.DURATION, "PT1H")
        }
        assertNotNull(context.contentResolver.insert(exUri, exCv))

        // Delete the recurring event via CalendarDelegate (WITHOUT calling retrieveCalendars first!)
        val delRes = awaitResult { delegate.deleteEvent(calId.toString(), recId.toString(), it) }
        assertTrue("deleteEvent failed: ${delRes.errorMessage}", delRes.isSuccess)

        // Standalone event MUST STILL BE VISIBLE!
        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(
            "Deleting recurring event with exception must NOT wipe standalone event in pre-existing local calendar",
            1,
            remaining.size
        )
        assertEquals("Keep Me", remaining[0]["eventTitle"])
    }

    @Test
    fun test03_preExistingSyncEvents0Calendar_deleteFollowingFromFirstInstance_doesNotWipeCalendar() {
        val calId = createLegacyLocalCalendar("Legacy Cal Delete Following", TEST_ACCOUNT_A, syncEvents = 0)
        val base = alignedNow()
        insertRawEvent(calId, TEST_ACCOUNT_A, "Surviving Event", base, dtEnd = base + HOUR_MS)
        val recId = insertRawEvent(
            calId, TEST_ACCOUNT_A, "Daily Series",
            base + 2 * HOUR_MS, duration = "PT1H", rrule = "FREQ=DAILY;COUNT=5"
        )

        // Delete "This and Following" from the 1st instance
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), recId.toString(), it,
                startDate = base + 2 * HOUR_MS,
                endDate = base + 3 * HOUR_MS,
                followingInstances = true
            )
        }
        assertTrue("deleteEvent(following=true) failed: ${delRes.errorMessage}", delRes.isSuccess)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(
            "Deleting first instance & following must NOT wipe surviving standalone event",
            1,
            remaining.size
        )
        assertEquals("Surviving Event", remaining[0]["eventTitle"])
    }

    @Test
    fun test04_preExistingLegacyDtendOnlyRecurringEvent_repairedAndEditable() {
        val calId = createLegacyLocalCalendar("Legacy DTEND Cal", TEST_ACCOUNT_A, syncEvents = 0)
        val now = alignedNow()
        // Realistic pre-existing legacy DTEND-only recurring event created 60 days ago with COUNT=100.
        // AOSP calculateLastDate sets LAST_DATE = base + 1h (60 days ago!), which is outside today's
        // expansion window, so occurrences around today vanish until migrated to DURATION + DTEND=null.
        val base = now - 60 * DAY_MS

        val cv = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, "Legacy DTEND Recurring")
            put(Events.DTSTART, base)
            put(Events.DTEND, base + HOUR_MS)
            put(Events.RRULE, "FREQ=DAILY;COUNT=100")
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val inserted = context.contentResolver.insert(Events.CONTENT_URI, cv)
        assertNotNull(inserted)
        val recId = ContentUris.parseId(inserted!!)

        // Clear Instances cache so today's query goes through getEntries (where LAST_DATE < minInstance & SYNC_EVENTS=0)
        val otherCalId = createLegacyLocalCalendar("Other Cal Trigger", TEST_ACCOUNT_B, syncEvents = 1)
        triggerGlobalClearInstanceRange(otherCalId, TEST_ACCOUNT_B)

        // Confirm occurrences around today are completely hidden BEFORE repair
        assertEquals(0, queryRawInstancesCount(calId, now - HOUR_MS, now + 5 * DAY_MS))

        // Trigger repair via retrieveCalendars()
        val calRes = awaitResult { delegate.retrieveCalendars(it) }
        assertTrue(calRes.isSuccess)

        // Query a 5-day window starting today -> must find all 5 occurrences!
        val todayInstances = retrieveEventsViaDelegate(calId, now - HOUR_MS, now + 5 * DAY_MS - 1)
        assertEquals(
            "Legacy DTEND-only recurring event must expand current occurrences after auto-repair",
            5,
            todayInstances.size
        )

        // Deleting a single instance on this legacy event must NOT crash with "Exceptions can't overwrite dtend"
        val day3Start = (todayInstances.map { (it["eventStartDate"] as Number).toLong() }.sorted())[2]
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), recId.toString(), it,
                startDate = day3Start,
                endDate = day3Start + HOUR_MS,
                followingInstances = false
            )
        }
        assertTrue("Single-instance delete on legacy event failed: ${delRes.errorMessage}", delRes.isSuccess)
        assertEquals(true, delRes.value)

        val afterDelete = retrieveEventsViaDelegate(calId, now - HOUR_MS, now + 5 * DAY_MS - 1)
        assertEquals("Expected 4 remaining instances in 5-day window after deleting 1", 4, afterDelete.size)
    }

    // =========================================================================
    // PART 2: BUGS ON BRAND-NEW SYNC_EVENTS = 1 LOCAL CALENDARS
    // =========================================================================

    @Test
    fun test05_retrieveEventsByEventIdsOnly_doesNotReturn2001RowsOrPoisonInstancesCache() {
        val calId = createCalendarViaDelegate("EventIds Lookup Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        // Create an indefinite daily recurring event
        val recEvent = makeEvent(
            calId, "Indefinite Daily", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY)
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val recId = createRes.value as String

        // Retrieve by eventIds ONLY (startDate = null, endDate = null)
        val byIds = retrieveEventsViaDelegate(calId, null, null, listOf(recId))
        assertEquals(
            "retrieveEvents(eventIds=[id]) with null dates must return 1 event row, NOT 2001 expanded instances",
            1,
            byIds.size
        )
        assertEquals(recId, byIds[0]["eventId"])
        val returnedEnd = (byIds[0]["eventEndDate"] as Number).toLong()
        assertEquals("End date must be computed from DURATION (start + 1h)", base + HOUR_MS, returnedEnd)

        // Also ensure querying a future window (+90d..+97d) still expands normally
        val future = retrieveEventsViaDelegate(calId, base + 90 * DAY_MS, base + 97 * DAY_MS)
        assertTrue("Expected >= 7 instances at +90d..+97d, got ${future.size}", future.size >= 7)
    }

    @Test
    fun test06_removeRecurrenceRuleOnUpdate_doesNotCorruptOrDisappearEvent() {
        val calId = createCalendarViaDelegate("Remove Rrule Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Initially Recurring", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val eventId = createRes.value as String

        assertEquals(5, retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS).size)

        // Update the event to REMOVE its recurrence rule (convert recurring -> single event)
        val updatedSingle = makeEvent(
            calId, "Now Single Event", base, base + 2 * HOUR_MS,
            rrule = null,
            eventId = eventId
        )
        val updateRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), updatedSingle, it) }
        assertTrue("Update removing RRULE failed: ${updateRes.errorMessage}", updateRes.isSuccess)

        // Verify in Events table that RRULE is null and DTEND is properly set
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId.toLong()),
            arrayOf(Events.RRULE, Events.DTEND, Events.DURATION),
            null, null, null
        )?.use { c ->
            assertTrue(c.moveToFirst())
            assertNull("RRULE must be cleared when recurrenceRule is set to null", c.getString(0))
            assertEquals("DTEND must be preserved and NOT scrubbed to null", base + 2 * HOUR_MS, c.getLong(1))
        }

        // Verify Instances returns exactly 1 instance with the new 2-hour duration
        val after = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals("Converting recurring to non-recurring must produce exactly 1 instance", 1, after.size)
        assertEquals("Now Single Event", after[0]["eventTitle"])
        assertEquals(base + 2 * HOUR_MS, (after[0]["eventEndDate"] as Number).toLong())
    }

    @Test
    fun test07_zeroDurationRecurringEvent_isValidAndSupportsInstanceDeletion() {
        val calId = createCalendarViaDelegate("Zero Duration Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        // Create a recurring event with start == end (0 duration)
        val zeroDurEvent = makeEvent(
            calId, "Zero Duration Recurring", base, base,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), zeroDurEvent, it) }
        assertTrue("Creating zero-duration recurring event failed: ${createRes.errorMessage}", createRes.isSuccess)
        val eventId = createRes.value as String

        // Verify DURATION is non-null in Events table (e.g. P0S)
        context.contentResolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId.toLong()),
            arrayOf(Events.DURATION, Events.DTEND),
            null, null, null
        )?.use { c ->
            assertTrue(c.moveToFirst())
            assertNotNull("Zero-duration recurring event must have non-null DURATION (e.g. P0S)", c.getString(0))
            assertTrue("DTEND must be null for recurring event", c.isNull(1))
        }

        // Verify single-instance delete works without crashing
        val day3 = base + 2 * DAY_MS
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), eventId, it,
                startDate = day3, endDate = day3, followingInstances = false
            )
        }
        assertTrue("Single-instance delete on zero-duration event failed: ${delRes.errorMessage}", delRes.isSuccess)
        assertEquals(true, delRes.value)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(4, remaining.size)
    }

    @Test
    fun test08_deleteDetachedOccurrence_andEditDetachedOccurrenceTwice() {
        val calId = createCalendarViaDelegate("Detached Exception Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Original Series", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val masterId = createRes.value as String

        val day3Start = base + 2 * DAY_MS
        val day3End = day3Start + HOUR_MS

        // Step 1: Edit Day 3 only ("Edit Only This Instance") -> creates detached exception row
        val edit1 = makeEvent(
            calId, "Detached v1", day3Start, day3End, eventId = masterId
        )
        val edit1Res = awaitResult {
            delegate.createOrUpdateEvent(
                calId.toString(), edit1, it,
                instanceStartDate = day3Start,
                instanceEndDate = day3End,
                updateFollowingInstances = false
            )
        }
        assertTrue("First single-instance edit failed: ${edit1Res.errorMessage}", edit1Res.isSuccess)
        val detachedId = edit1Res.value as String

        // Step 2: Edit the ALREADY-DETACHED exception a second time!
        val edit2 = makeEvent(
            calId, "Detached v2", day3Start + HOUR_MS, day3End + HOUR_MS, eventId = detachedId
        )
        val edit2Res = awaitResult {
            delegate.createOrUpdateEvent(
                calId.toString(), edit2, it,
                instanceStartDate = day3Start,
                instanceEndDate = day3End,
                updateFollowingInstances = false
            )
        }
        assertTrue("Editing detached occurrence a second time failed: ${edit2Res.errorMessage}", edit2Res.isSuccess)

        val afterEdit2 = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals("Should still have 5 instances total after re-editing detached instance", 5, afterEdit2.size)
        assertEquals(1, afterEdit2.count { it["eventTitle"] == "Detached v2" })

        // Step 3: Delete the detached occurrence ("Delete Only This Instance")
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), detachedId, it,
                startDate = day3Start + HOUR_MS,
                endDate = day3End + HOUR_MS,
                followingInstances = false
            )
        }
        assertTrue("Deleting detached occurrence failed: ${delRes.errorMessage}", delRes.isSuccess)
        assertEquals(true, delRes.value)

        // Trigger a full Instances re-expansion to ensure the deleted detached occurrence does NOT resurrect!
        triggerGlobalClearInstanceRange(calId, TEST_ACCOUNT_A)

        val afterDel = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals("Deleting detached occurrence must leave 4 instances (and NOT resurrect Day 3)", 4, afterDel.size)
        assertFalse(
            "Day 3 must remain deleted after full re-expansion",
            afterDel.any {
                val s = (it["eventStartDate"] as Number).toLong()
                s == day3Start || s == day3Start + HOUR_MS
            }
        )
    }

    @Test
    fun test09_deleteThisAndFollowing_indefiniteAllDayEvent_actuallyDeletesTargetDay() {
        val calId = createCalendarViaDelegate("AllDay Delete Following Cal", TEST_ACCOUNT_A)
        val day1 = utcMidnightDaysFromNow(2)

        // Indefinite daily all-day event (no COUNT, no UNTIL)
        val allDayRec = makeEvent(
            calId, "Daily All-Day", day1, day1 + DAY_MS,
            allDay = true,
            rrule = RecurrenceRule(Freq.DAILY)
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), allDayRec, it) }
        assertTrue(createRes.isSuccess)
        val eventId = createRes.value as String

        // Delete Day 3 and following
        val day3 = day1 + 2 * DAY_MS
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), eventId, it,
                startDate = day3,
                endDate = day3 + DAY_MS,
                followingInstances = true
            )
        }
        assertTrue("Delete following on all-day failed: ${delRes.errorMessage}", delRes.isSuccess)

        val remaining = retrieveEventsViaDelegate(calId, day1 - DAY_MS, day1 + 10 * DAY_MS)
        assertEquals(
            "Delete This and Following from Day 3 on indefinite all-day event must leave ONLY Day 1 and Day 2 (2 instances)",
            2,
            remaining.size
        )
        val starts = remaining.map { (it["eventStartDate"] as Number).toLong() }.sorted()
        assertEquals(listOf(day1, day1 + DAY_MS), starts)
    }

    @Test
    fun test10_deleteFollowing_andSplitFirst_cleanUpOrphanedFutureExceptions() {
        val calId = createCalendarViaDelegate("Orphaned Exceptions Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Series With Future Edit", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val masterId = createRes.value as String

        // Edit Day 4 only
        val day4 = base + 3 * DAY_MS
        val editDay4 = makeEvent(calId, "Edited Day 4", day4, day4 + HOUR_MS, eventId = masterId)
        val editRes = awaitResult {
            delegate.createOrUpdateEvent(
                calId.toString(), editDay4, it,
                instanceStartDate = day4, instanceEndDate = day4 + HOUR_MS,
                updateFollowingInstances = false
            )
        }
        assertTrue(editRes.isSuccess)

        // Now "Delete This and Following" starting at Day 3 -> Day 3, Day 4 (edited!), and Day 5 must ALL be gone!
        val day3 = base + 2 * DAY_MS
        val delRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), masterId, it,
                startDate = day3, endDate = day3 + HOUR_MS,
                followingInstances = true
            )
        }
        assertTrue(delRes.isSuccess)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(
            "Edited Day 4 exception must NOT remain orphaned after Delete This and Following from Day 3",
            2,
            remaining.size
        )
        assertFalse(remaining.any { it["eventTitle"] == "Edited Day 4" })
    }

    @Test
    fun test11_hiddenCalendarVisible0_singleDeleteAndDeleteFollowing_doNotCrashOrWipeSeries() {
        // Calendar with VISIBLE = 0 (user unchecked calendar visibility in Google Calendar app)
        val calId = createLegacyLocalCalendar("Hidden Local Cal", TEST_ACCOUNT_A, syncEvents = 1, visible = 0)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Hidden Cal Series", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val eventId = createRes.value as String

        // 1. Delete single instance (Day 2) on VISIBLE=0 calendar must NOT crash!
        val day2 = base + DAY_MS
        val delSingleRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), eventId, it,
                startDate = day2, endDate = day2 + HOUR_MS, followingInstances = false
            )
        }
        assertTrue("Single delete on VISIBLE=0 calendar failed: ${delSingleRes.errorMessage}", delSingleRes.isSuccess)
        assertEquals(true, delSingleRes.value)

        // 2. Delete This and Following from Day 4 on VISIBLE=0 calendar must keep Day 1 & Day 3 (NOT delete entire series!)
        val day4 = base + 3 * DAY_MS
        val delFollowingRes = awaitResult {
            delegate.deleteEvent(
                calId.toString(), eventId, it,
                startDate = day4, endDate = day4 + HOUR_MS, followingInstances = true
            )
        }
        assertTrue("Delete following on VISIBLE=0 calendar failed: ${delFollowingRes.errorMessage}", delFollowingRes.isSuccess)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(
            "On VISIBLE=0 calendar, Day 1 and Day 3 must survive (2 instances, not 0!)",
            2,
            remaining.size
        )
    }

    @Test
    fun test12_editOnlyThisInstance_doesNotDuplicateReminders() {
        val calId = createCalendarViaDelegate("Reminder Dedup Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Meeting With Reminder", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 3 },
            reminders = mutableListOf(Reminder(15))
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val masterId = createRes.value as String

        // Edit Day 2 only, keeping the same 15-min reminder
        val day2 = base + DAY_MS
        val editDay2 = makeEvent(
            calId, "Edited Meeting", day2, day2 + HOUR_MS,
            eventId = masterId,
            reminders = mutableListOf(Reminder(15))
        )
        val editRes = awaitResult {
            delegate.createOrUpdateEvent(
                calId.toString(), editDay2, it,
                instanceStartDate = day2, instanceEndDate = day2 + HOUR_MS,
                updateFollowingInstances = false
            )
        }
        assertTrue(editRes.isSuccess)
        val exceptionId = (editRes.value as String).toLong()

        var reminderCount = 0
        context.contentResolver.query(
            CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?",
            arrayOf(exceptionId.toString()),
            null
        )?.use { reminderCount = it.count }

        assertEquals(
            "Edit Only This Instance must NOT duplicate reminders copied by CalendarProvider2",
            1,
            reminderCount
        )
    }

    @Test
    fun test13_retrieveEventsWithNonNumericEventId_doesNotCrashSqlite() {
        val calId = createCalendarViaDelegate("Sanitize EventId Cal", TEST_ACCOUNT_A)
        val base = alignedNow()
        val res = awaitResult {
            delegate.retrieveEvents(calId.toString(), base - DAY_MS, base + DAY_MS, listOf("non_existent_id"), it)
        }
        assertTrue("Non-numeric eventId should not crash SQLite: ${res.errorMessage}", res.isSuccess)
    }

    @Test
    fun test14_updateCalendarColor_onLocalCalendar_succeeds() {
        val calId = createCalendarViaDelegate("Color Update Cal", TEST_ACCOUNT_A)
        val updated = delegate.updateCalendarColor(calId, null, Color.MAGENTA)
        assertTrue("updateCalendarColor on local calendar must succeed", updated)
    }

    @Test
    fun test15_singleInstanceDelete_survivesFullInstancesRebuild() {
        val calId = createCalendarViaDelegate("Rebuild Survival Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Recurring Rebuild Check", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val recId = createRes.value as String

        // Delete Day 2 and Day 4 individually
        val day2 = base + DAY_MS
        val day4 = base + 3 * DAY_MS
        assertTrue(awaitResult {
            delegate.deleteEvent(calId.toString(), recId, it, day2, day2 + HOUR_MS, false)
        }.isSuccess)
        assertTrue(awaitResult {
            delegate.deleteEvent(calId.toString(), recId, it, day4, day4 + HOUR_MS, false)
        }.isSuccess)

        // Force full Instances rebuild
        triggerGlobalClearInstanceRange(calId, TEST_ACCOUNT_A)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals("Deleted single instances must stay deleted after full rebuild", 3, remaining.size)
        val starts = remaining.map { (it["eventStartDate"] as Number).toLong() }.sorted()
        assertEquals(listOf(base, base + 2 * DAY_MS, base + 4 * DAY_MS), starts)
    }

    @Test
    fun test16_editThisAndFuture_acrossDifferentLocalAccounts_cleansUpOriginalExceptions() {
        val calA = createCalendarViaDelegate("Move Source Cal A", TEST_ACCOUNT_A)
        val calB = createCalendarViaDelegate("Move Dest Cal B", TEST_ACCOUNT_B)
        val base = alignedNow()

        val recEvent = makeEvent(
            calA, "Series To Split Across Accounts", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val createRes = awaitResult { delegate.createOrUpdateEvent(calA.toString(), recEvent, it) }
        assertTrue(createRes.isSuccess)
        val masterId = createRes.value as String

        // Edit Day 4 in Cal A
        val day4 = base + 3 * DAY_MS
        val editDay4 = makeEvent(calA, "Edited Day 4 In Cal A", day4, day4 + HOUR_MS, eventId = masterId)
        assertTrue(awaitResult {
            delegate.createOrUpdateEvent(
                calA.toString(), editDay4, it,
                instanceStartDate = day4, instanceEndDate = day4 + HOUR_MS,
                updateFollowingInstances = false
            )
        }.isSuccess)

        // Now "Edit This and Future" from Day 3 moving to Cal B (different local account!)
        val day3 = base + 2 * DAY_MS
        val movedEvent = makeEvent(
            calB, "Moved Series In Cal B", day3, day3 + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 },
            eventId = masterId
        )
        val splitRes = awaitResult {
            delegate.createOrUpdateEvent(
                calB.toString(), movedEvent, it,
                instanceStartDate = day3, instanceEndDate = day3 + HOUR_MS,
                updateFollowingInstances = true
            )
        }
        assertTrue("Cross-account Edit This and Future failed: ${splitRes.errorMessage}", splitRes.isSuccess)

        val calAEvents = retrieveEventsViaDelegate(calA, base - DAY_MS, base + 7 * DAY_MS)
        val calBEvents = retrieveEventsViaDelegate(calB, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals("Cal A must retain ONLY Day 1 & Day 2 (no orphaned Day 4 exception!)", 2, calAEvents.size)
        assertEquals("Cal B must have Day 3, 4, 5 (3 instances)", 3, calBEvents.size)
    }

    @Test
    fun test17_preExistingSyncEvents0Calendar_repairedDirectlyByRetrieveEvents() {
        val calId = createLegacyLocalCalendar("Direct RetrieveEvents Cal", TEST_ACCOUNT_A, syncEvents = 0)
        val base = alignedNow()
        insertRawEvent(calId, TEST_ACCOUNT_A, "Cached CalId Event", base, dtEnd = base + HOUR_MS)

        // Wipe Instances cache via another calendar
        val otherCalId = createLegacyLocalCalendar("Trigger Cal", TEST_ACCOUNT_B, syncEvents = 1)
        triggerGlobalClearInstanceRange(otherCalId, TEST_ACCOUNT_B)

        assertEquals(0, getSyncEventsValue(calId))
        assertEquals(0, queryRawInstancesCount(calId, base - DAY_MS, base + 3 * DAY_MS))

        // App skips retrieveCalendars() and calls retrieveEvents() directly with cached calendarId
        val events = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 3 * DAY_MS)
        assertEquals("retrieveEvents() must auto-repair SYNC_EVENTS=0 calendar via retrieveCalendar()", 1, getSyncEventsValue(calId))
        assertEquals("Vanished event must reappear immediately on direct retrieveEvents()", 1, events.size)
    }

    @Test
    fun test18_preExistingSyncEvents0Calendar_otherCalendarDeleteRecurring_doesNotWipeAfterRepair() {
        val calA = createLegacyLocalCalendar("Repaired Local Cal A", TEST_ACCOUNT_A, syncEvents = 0)
        val calB = createCalendarViaDelegate("Other Cal B", TEST_ACCOUNT_B)
        val base = alignedNow()
        insertRawEvent(calA, TEST_ACCOUNT_A, "Event In Cal A", base, dtEnd = base + HOUR_MS)

        // Auto-repair via retrieveCalendars()
        assertTrue(awaitResult { delegate.retrieveCalendars(it) }.isSuccess)
        assertEquals(1, getSyncEventsValue(calA))

        // Delete recurring event in Cal B -> triggers global clearInstanceRange()
        triggerGlobalClearInstanceRange(calB, TEST_ACCOUNT_B)

        val eventsA = retrieveEventsViaDelegate(calA, base - DAY_MS, base + 3 * DAY_MS)
        assertEquals("Cal A events must survive global clearInstanceRange after repair", 1, eventsA.size)
    }

    @Test
    fun test19_deleteDetachedOccurrenceDirectly_doesNotCrashOrResurrect() {
        val calId = createCalendarViaDelegate("Delete Detached Directly Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        val recEvent = makeEvent(
            calId, "Series For Direct Detach Delete", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val masterId = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }.value as String

        val day3 = base + 2 * DAY_MS
        val edited = makeEvent(calId, "Detached Day 3", day3, day3 + HOUR_MS, eventId = masterId)
        val detachedId = awaitResult {
            delegate.createOrUpdateEvent(calId.toString(), edited, it, day3, day3 + HOUR_MS, false)
        }.value as String

        // Delete the detached occurrence directly (matching recurring_deletion_test.dart Test 5)
        val delRes = awaitResult {
            delegate.deleteEvent(calId.toString(), detachedId, it, day3, day3 + HOUR_MS, false)
        }
        assertTrue("Deleting detached occurrence directly failed: ${delRes.errorMessage}", delRes.isSuccess)
        assertEquals(true, delRes.value)

        // Trigger full rebuild to guarantee Day 3 does NOT resurrect
        triggerGlobalClearInstanceRange(calId, TEST_ACCOUNT_A)

        val remaining = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 7 * DAY_MS)
        assertEquals(4, remaining.size)
        assertFalse(remaining.any { (it["eventStartDate"] as Number).toLong() == day3 })
    }

    @Test
    fun test20_readOnlySyncEvents0LocalCalendar_isNotModifiedByAutoRepair() {
        val uri = syncAdapterUri(Calendars.CONTENT_URI, TEST_ACCOUNT_A)
        val cv = ContentValues().apply {
            put(Calendars.NAME, "ReadOnly Local Cal")
            put(Calendars.CALENDAR_DISPLAY_NAME, "ReadOnly Local Cal")
            put(Calendars.ACCOUNT_NAME, TEST_ACCOUNT_A)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.SYNC_EVENTS, 0)
            put(Calendars.VISIBLE, 1)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
            put(Calendars.CALENDAR_COLOR, Color.GRAY)
            put(Calendars.OWNER_ACCOUNT, TEST_ACCOUNT_A)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val calId = ContentUris.parseId(context.contentResolver.insert(uri, cv)!!)
        createdCalendarIds.add(calId to TEST_ACCOUNT_A)

        assertTrue(awaitResult { delegate.retrieveCalendars(it) }.isSuccess)
        assertEquals("Read-only calendar must NOT be mutated by auto-repair", 0, getSyncEventsValue(calId))
    }

    @Test
    fun test21_countSeriesWithEditedAndDeletedPriorExceptions_editThisAndFuturePreservesExactCounts() {
        val calId = createCalendarViaDelegate("Prior Exceptions Split Cal", TEST_ACCOUNT_A)
        val base = alignedNow()

        // 5-day series: Day 1, 2, 3, 4, 5
        val recEvent = makeEvent(
            calId, "Base Series", base, base + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 }
        )
        val masterId = awaitResult { delegate.createOrUpdateEvent(calId.toString(), recEvent, it) }.value as String

        // Edit Day 1 only (exception #1, non-canceled)
        val editDay1 = makeEvent(calId, "Edited Day 1", base, base + HOUR_MS, eventId = masterId)
        assertTrue(awaitResult {
            delegate.createOrUpdateEvent(calId.toString(), editDay1, it, base, base + HOUR_MS, false)
        }.isSuccess)

        // Delete Day 2 only (exception #2, canceled)
        val day2 = base + DAY_MS
        assertTrue(awaitResult {
            delegate.deleteEvent(calId.toString(), masterId, it, day2, day2 + HOUR_MS, false)
        }.isSuccess)

        // Now "Edit This and Future" from Day 4 -> original must keep Day 1 (edited) & Day 3 (2 visible!),
        // and new split series must have ONLY Day 4 & Day 5 (2 instances, NOT 4!)
        val day4 = base + 3 * DAY_MS
        val splitEvent = makeEvent(
            calId, "Split Day 4+", day4, day4 + HOUR_MS,
            rrule = RecurrenceRule(Freq.DAILY).apply { count = 5 },
            eventId = masterId
        )
        val splitRes = awaitResult {
            delegate.createOrUpdateEvent(calId.toString(), splitEvent, it, day4, day4 + HOUR_MS, true)
        }
        assertTrue("Split with prior exceptions failed: ${splitRes.errorMessage}", splitRes.isSuccess)
        val newSeriesId = splitRes.value as String

        val all = retrieveEventsViaDelegate(calId, base - DAY_MS, base + 10 * DAY_MS)
        val newInstances = all.filter { it["eventId"] == newSeriesId }
        val oldInstances = all.filter { it["eventId"] != newSeriesId }

        assertEquals("Original series must keep Day 1 (edited) and Day 3", 2, oldInstances.size)
        assertEquals("Split series must have exactly Day 4 and Day 5 (count=2)", 2, newInstances.size)
        assertTrue(oldInstances.any { it["eventTitle"] == "Edited Day 1" })
        assertTrue(oldInstances.any { it["eventTitle"] == "Base Series" })
    }
}
