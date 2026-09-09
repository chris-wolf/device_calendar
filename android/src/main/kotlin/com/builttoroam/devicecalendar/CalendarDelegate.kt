package com.builttoroam.devicecalendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.CalendarContract.CALLER_IS_SYNCADAPTER
import android.provider.CalendarContract.Events
import android.text.format.DateUtils
import com.builttoroam.devicecalendar.common.ErrorMessages
import com.builttoroam.devicecalendar.models.*
import com.builttoroam.devicecalendar.models.Calendar
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import kotlinx.coroutines.*
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.DateTime.UTC
import org.dmfs.rfc5545.Weekday
import org.dmfs.rfc5545.recur.RecurrenceRule.WeekdayNum
import java.util.*
import kotlin.math.absoluteValue
import kotlin.time.DurationUnit
import kotlin.time.toDuration
import com.builttoroam.devicecalendar.common.Constants.Companion as Cst
import com.builttoroam.devicecalendar.common.ErrorCodes.Companion as EC
import com.builttoroam.devicecalendar.common.ErrorMessages.Companion as EM
import org.dmfs.rfc5545.recur.Freq as RruleFreq
import org.dmfs.rfc5545.recur.RecurrenceRule as Rrule
import android.provider.CalendarContract.Colors
import androidx.collection.SparseArrayCompat
import android.content.ContentProviderResult
import android.content.ContentProviderOperation

private const val RETRIEVE_CALENDARS_REQUEST_CODE = 0
private const val RETRIEVE_EVENTS_REQUEST_CODE = RETRIEVE_CALENDARS_REQUEST_CODE + 1
private const val RETRIEVE_CALENDAR_REQUEST_CODE = RETRIEVE_EVENTS_REQUEST_CODE + 1
private const val CREATE_OR_UPDATE_EVENT_REQUEST_CODE = RETRIEVE_CALENDAR_REQUEST_CODE + 1
private const val DELETE_EVENT_REQUEST_CODE = CREATE_OR_UPDATE_EVENT_REQUEST_CODE + 1
private const val REQUEST_PERMISSIONS_REQUEST_CODE = DELETE_EVENT_REQUEST_CODE + 1
private const val DELETE_CALENDAR_REQUEST_CODE = REQUEST_PERMISSIONS_REQUEST_CODE + 1

class CalendarDelegate(binding: ActivityPluginBinding?, context: Context) :
    PluginRegistry.RequestPermissionsResultListener {

    private val _cachedParametersMap: MutableMap<Int, CalendarMethodsParametersCacheModel> =
        mutableMapOf()
    private var _binding: ActivityPluginBinding? = binding
    private var _context: Context? = context
    private var _gson: Gson? = null

    private val uiThreadHandler = Handler(Looper.getMainLooper())

    init {
        val gsonBuilder = GsonBuilder()
        gsonBuilder.registerTypeAdapter(Availability::class.java, AvailabilitySerializer())
        gsonBuilder.registerTypeAdapter(EventStatus::class.java, EventStatusSerializer())
        _gson = gsonBuilder.create()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ): Boolean {
        val permissionGranted =
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        if (!_cachedParametersMap.containsKey(requestCode)) {
            // this plugin doesn't handle this request code
            return false
        }

        val cachedValues: CalendarMethodsParametersCacheModel = _cachedParametersMap[requestCode]
            ?: // unlikely scenario where another plugin is potentially using the same request code but it's not one we are tracking so return to
            // indicate we're not handling the request
            return false

        try {
            if (!permissionGranted) {
                finishWithError(
                    EC.NOT_AUTHORIZED,
                    EM.NOT_AUTHORIZED_MESSAGE,
                    cachedValues.pendingChannelResult
                )
                return false
            }

            when (cachedValues.calendarDelegateMethodCode) {
                RETRIEVE_CALENDARS_REQUEST_CODE -> {
                    retrieveCalendars(cachedValues.pendingChannelResult)
                }
                RETRIEVE_EVENTS_REQUEST_CODE -> {
                    retrieveEvents(
                        cachedValues.calendarId,
                        cachedValues.calendarEventsStartDate,
                        cachedValues.calendarEventsEndDate,
                        cachedValues.calendarEventsIds,
                        cachedValues.pendingChannelResult
                    )
                }
                RETRIEVE_CALENDAR_REQUEST_CODE -> {
                    retrieveCalendar(cachedValues.calendarId, cachedValues.pendingChannelResult)
                }
                CREATE_OR_UPDATE_EVENT_REQUEST_CODE -> {
                    createOrUpdateEvent(
                        cachedValues.calendarId,
                        cachedValues.event,
                        cachedValues.pendingChannelResult
                    )
                }
                DELETE_EVENT_REQUEST_CODE -> {
                    deleteEvent(
                        cachedValues.calendarId,
                        cachedValues.eventId,
                        cachedValues.pendingChannelResult
                    )
                }
                REQUEST_PERMISSIONS_REQUEST_CODE -> {
                    finishWithSuccess(permissionGranted, cachedValues.pendingChannelResult)
                }
                DELETE_CALENDAR_REQUEST_CODE -> {
                    deleteCalendar(cachedValues.calendarId, cachedValues.pendingChannelResult)
                }
            }

            return true
        } finally {
            _cachedParametersMap.remove(cachedValues.calendarDelegateMethodCode)
        }
    }

    fun requestPermissions(pendingChannelResult: MethodChannel.Result) {
        if (arePermissionsGranted()) {
            finishWithSuccess(true, pendingChannelResult)
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
            requestPermissions(parameters)
        }
    }

    fun hasPermissions(pendingChannelResult: MethodChannel.Result) {
        finishWithSuccess(arePermissionsGranted(), pendingChannelResult)
    }

    @SuppressLint("MissingPermission")
    fun retrieveCalendars(pendingChannelResult: MethodChannel.Result) {
        if (arePermissionsGranted()) {
            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                val contentResolver: ContentResolver? = _context?.contentResolver
                val uri: Uri = CalendarContract.Calendars.CONTENT_URI
                val cursor: Cursor? = if (atLeastAPI(17)) {
                    contentResolver?.query(uri, Cst.CALENDAR_PROJECTION, null, null, null)
                } else {
                    contentResolver?.query(uri, Cst.CALENDAR_PROJECTION_OLDER_API, null, null, null)
                }
                val calendars: MutableList<Calendar> = mutableListOf()
                try {
                    while (cursor?.moveToNext() == true) {
                        val calendar = parseCalendarRow(cursor) ?: continue
                        calendars.add(calendar)
                    }

                    val json = _gson?.toJson(calendars)
                    uiThreadHandler.post {
                        finishWithSuccess(json, pendingChannelResult)
                    }
                } catch (e: Exception) {
                    uiThreadHandler.post {
                        finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
                    }
                } finally {
                    cursor?.close()
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_CALENDARS_REQUEST_CODE
            )
            requestPermissions(parameters)
        }
    }

    private fun retrieveCalendar(
        calendarId: String,
        pendingChannelResult: MethodChannel.Result,
        isInternalCall: Boolean = false
    ): Calendar? {
        if (isInternalCall || arePermissionsGranted()) {
            val calendarIdNumber = calendarId.toLongOrNull()
            if (calendarIdNumber == null) {
                if (!isInternalCall) {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        EM.CALENDAR_ID_INVALID_ARGUMENT_NOT_A_NUMBER_MESSAGE,
                        pendingChannelResult
                    )
                }
                return null
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val uri: Uri = CalendarContract.Calendars.CONTENT_URI

            val cursor: Cursor? = if (atLeastAPI(17)) {
                contentResolver?.query(
                    ContentUris.withAppendedId(uri, calendarIdNumber),
                    Cst.CALENDAR_PROJECTION,
                    null,
                    null,
                    null
                )
            } else {
                contentResolver?.query(
                    ContentUris.withAppendedId(uri, calendarIdNumber),
                    Cst.CALENDAR_PROJECTION_OLDER_API,
                    null,
                    null,
                    null
                )
            }

            try {
                if (cursor?.moveToFirst() == true) {
                    val calendar = parseCalendarRow(cursor)
                    if (isInternalCall) {
                        return calendar
                    } else {
                        finishWithSuccess(_gson?.toJson(calendar), pendingChannelResult)
                    }
                } else {
                    if (!isInternalCall) {
                        finishWithError(
                            EC.NOT_FOUND,
                            "The calendar with the ID $calendarId could not be found",
                            pendingChannelResult
                        )
                    }
                }
            } catch (e: Exception) {
                finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
            } finally {
                cursor?.close()
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_CALENDAR_REQUEST_CODE,
                calendarId
            )
            requestPermissions(parameters)
        }

        return null
    }

    fun deleteCalendar(
        calendarId: String,
        pendingChannelResult: MethodChannel.Result,
        isInternalCall: Boolean = false
    ): Calendar? {
        if (isInternalCall || arePermissionsGranted()) {
            val calendarIdNumber = calendarId.toLongOrNull()
            if (calendarIdNumber == null) {
                if (!isInternalCall) {
                    finishWithError(
                        EC.INVALID_ARGUMENT,
                        EM.CALENDAR_ID_INVALID_ARGUMENT_NOT_A_NUMBER_MESSAGE,
                        pendingChannelResult
                    )
                }
                return null
            }

            val contentResolver: ContentResolver? = _context?.contentResolver

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar != null) {
                val calenderUriWithId = ContentUris.withAppendedId(
                    CalendarContract.Calendars.CONTENT_URI,
                    calendarIdNumber
                )
                val deleteSucceeded = contentResolver?.delete(calenderUriWithId, null, null) ?: 0
                finishWithSuccess(deleteSucceeded > 0, pendingChannelResult)
            } else {
                if (!isInternalCall) {
                    finishWithError(
                        EC.NOT_FOUND,
                        "The calendar with the ID $calendarId could not be found",
                        pendingChannelResult
                    )
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult = pendingChannelResult,
                calendarDelegateMethodCode = DELETE_CALENDAR_REQUEST_CODE,
                calendarId = calendarId
            )
            requestPermissions(parameters)
        }

        return null
    }

    fun createCalendar(
        calendarName: String,
        calendarColor: String?,
        localAccountName: String,
        pendingChannelResult: MethodChannel.Result
    ) {
        val contentResolver: ContentResolver? = _context?.contentResolver

        var uri = CalendarContract.Calendars.CONTENT_URI
        uri = uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, localAccountName)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            .build()
        val values = ContentValues()
        values.put(CalendarContract.Calendars.NAME, calendarName)
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, calendarName)
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, localAccountName)
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1)
        values.put(CalendarContract.Calendars.VISIBLE, 1)
        values.put(
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CAL_ACCESS_OWNER
        )
        values.put(
            CalendarContract.Calendars.CALENDAR_COLOR, Color.parseColor(
                (calendarColor
                    ?: "0xFFFF0000").replace("0x", "#")
            )
        ) // Red colour as a default
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, localAccountName)
        values.put(
            CalendarContract.Calendars.CALENDAR_TIME_ZONE,
            java.util.Calendar.getInstance().timeZone.id
        )

        val result = contentResolver?.insert(uri, values)
        // Get the calendar ID that is the last element in the Uri
        val calendarId = java.lang.Long.parseLong(result?.lastPathSegment!!)

        finishWithSuccess(calendarId.toString(), pendingChannelResult)
    }

    fun retrieveEvents(
        calendarId: String,
        startDate: Long?,
        endDate: Long?,
        eventIds: List<String>,
        pendingChannelResult: MethodChannel.Result
    ) {
        if (startDate == null && endDate == null && eventIds.isEmpty()) {
            finishWithError(
                EC.INVALID_ARGUMENT,
                ErrorMessages.RETRIEVE_EVENTS_ARGUMENTS_NOT_VALID_MESSAGE,
                pendingChannelResult
            )
            return
        }

        if (arePermissionsGranted()) {
            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            val safeEndDate = endDate ?: 4102444800000L // Jan 1, 2100 00:00:00 UTC, safely fits in Julian Day calculations without integer overflow
            val eventsUriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(eventsUriBuilder, startDate ?: 0L)
            ContentUris.appendId(eventsUriBuilder, safeEndDate)

            val eventsUri = eventsUriBuilder.build()
            val eventsCalendarQuery = "(${Events.CALENDAR_ID} = $calendarId)"
            val eventsNotDeletedQuery = "(${Events.DELETED} != 1)"
            val eventsIdsQuery =
                "(${CalendarContract.Instances.EVENT_ID} IN (${eventIds.joinToString()}))"

            var eventsSelectionQuery = "$eventsCalendarQuery AND $eventsNotDeletedQuery"
            if (eventIds.isNotEmpty()) {
                eventsSelectionQuery += " AND ($eventsIdsQuery)"
            }
            val eventsSortOrder = Events.DTSTART + " DESC"

            val eventsCursor = contentResolver?.query(
                eventsUri,
                Cst.EVENT_PROJECTION,
                eventsSelectionQuery,
                null,
                eventsSortOrder
            )

            val events: MutableList<Event> = mutableListOf()

            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                while (eventsCursor?.moveToNext() == true) {
                    val event = parseEvent(calendarId, eventsCursor) ?: continue
                    events.add(event)
                }
                val attendeesCache = mutableMapOf<String, List<Attendee>>()
                val remindersCache = mutableMapOf<String, List<Reminder>>()
                for (event in events) {
                    val eventId = event.eventId
                    if (eventId != null) {
                        val attendees = attendeesCache.getOrPut(eventId) {
                            retrieveAttendees(calendar, eventId, contentResolver)
                        }
                        event.organizer =
                            attendees.firstOrNull { it.isOrganizer != null && it.isOrganizer }
                        event.attendees = attendees
                        event.reminders = remindersCache.getOrPut(eventId) {
                            retrieveReminders(eventId, contentResolver)
                        }
                    }
                }
            }.invokeOnCompletion { cause ->
                eventsCursor?.close()
                if (cause == null) {
                    uiThreadHandler.post {
                        finishWithSuccess(_gson?.toJson(events), pendingChannelResult)
                    }
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                RETRIEVE_EVENTS_REQUEST_CODE,
                calendarId,
                startDate,
                endDate
            )
            requestPermissions(parameters)
        }

        return
    }

    fun createOrUpdateEvent(
        calendarId: String,
        event: Event?,
        pendingChannelResult: MethodChannel.Result,
        instanceStartDate: Long? = null,
        instanceEndDate: Long? = null,
        updateFollowingInstances: Boolean? = null
    ) {
        if (arePermissionsGranted()) {
            if (event == null) {
                finishWithError(
                    EC.GENERIC_ERROR,
                    EM.CREATE_EVENT_ARGUMENTS_NOT_VALID_MESSAGE,
                    pendingChannelResult
                )
                return
            }

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val values = buildEventContentValues(event, calendarId)
            val eventId: Long? = event.eventId?.toLongOrNull()

            val buildUri = { uri: Uri ->
                if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                    uri.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, calendar.accountName)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, calendar.accountType)
                        .build()
                } else {
                    uri
                }
            }

            if (eventId != null && instanceStartDate != null && instanceEndDate != null && updateFollowingInstances != null) {
                val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                    uiThreadHandler.post {
                        finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                    }
                }

                if (!updateFollowingInstances) {
                    // --- Case 2: Edit Only This Instance ---
                    GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        val masterEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                        val masterEventCursor = contentResolver?.query(
                            masterEventUri,
                            arrayOf(Events.DTSTART, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE, Events._SYNC_ID),
                            null, null, null
                        )
                        var masterStart: Long? = null
                        var masterDuration: String? = null
                        var masterTimezone: String? = null
                        var masterRrule: String? = null
                        var masterSyncId: String? = null
                        try {
                            if (masterEventCursor != null && masterEventCursor.moveToFirst()) {
                                masterStart = masterEventCursor.getLong(0)
                                masterDuration = masterEventCursor.getString(1)
                                masterTimezone = masterEventCursor.getString(2)
                                masterRrule = masterEventCursor.getString(3)
                                masterSyncId = masterEventCursor.getString(4)
                            }
                        } finally {
                            masterEventCursor?.close()
                        }

                        if (masterSyncId.isNullOrEmpty() && calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                            masterSyncId = UUID.randomUUID().toString()
                            val updateValues = ContentValues().apply {
                                put(Events._SYNC_ID, masterSyncId)
                            }
                            contentResolver?.update(buildUri(masterEventUri), updateValues, null, null)
                        }

                        val exceptionValues = ContentValues().apply {
                            putAll(values)
                            remove(Events.CALENDAR_ID)
                            remove(Events.RRULE)
                            remove(Events.EVENT_COLOR_KEY)
                        }

                        exceptionValues.put(Events.ORIGINAL_INSTANCE_TIME, instanceStartDate)
                        if (!masterSyncId.isNullOrEmpty()) {
                            exceptionValues.put(Events.ORIGINAL_SYNC_ID, masterSyncId)
                        }

                        if (masterDuration != null) {
                            exceptionValues.remove(Events.DTEND)
                            val difference = event.eventEndDate!!.minus(event.eventStartDate!!)
                            val rawDuration = difference.toDuration(DurationUnit.MILLISECONDS)
                            var duration = "P0D"
                            rawDuration.toComponents { days, hours, minutes, seconds, _ ->
                                if (days > 0 || hours > 0 || minutes > 0 || seconds > 0) {
                                    duration = "P"
                                    if (days > 0) duration = duration.plus("${days}D")
                                    if (hours > 0 || minutes > 0 || seconds > 0) duration = duration.plus("T")
                                    if (hours > 0) duration = duration.plus("${hours}H")
                                    if (minutes > 0) duration = duration.plus("${minutes}M")
                                    if (seconds > 0) duration = duration.plus("${seconds}S")
                                }
                            }
                            exceptionValues.put(Events.DURATION, duration)
                        } else {
                            exceptionValues.remove(Events.DURATION)
                            exceptionValues.put(Events.DTEND, event.eventEndDate)
                        }

                        val exceptionUriWithId = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
                        val insertedUri = contentResolver?.insert(buildUri(exceptionUriWithId), exceptionValues)
                        val exceptionEventId = insertedUri?.lastPathSegment?.toLongOrNull()

                        if (exceptionEventId != null) {
                            if (event.eventColorKey != null) {
                                val colorValues = ContentValues().apply {
                                    put(Events.EVENT_COLOR_KEY, event.eventColorKey)
                                }
                                val exceptionEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, exceptionEventId)
                                contentResolver?.update(buildUri(exceptionEventUri), colorValues, null, null)
                            }

                            insertAttendees(event.attendees, exceptionEventId, contentResolver)
                            insertReminders(event.reminders, exceptionEventId, contentResolver)

                            val touchValues = ContentValues()
                            if (masterStart != null) touchValues.put(Events.DTSTART, masterStart)
                            if (masterDuration != null) touchValues.put(Events.DURATION, masterDuration)
                            if (masterTimezone != null) touchValues.put(Events.EVENT_TIMEZONE, masterTimezone)
                            if (masterRrule != null) touchValues.put(Events.RRULE, masterRrule)
                            touchValues.putNull(Events.LAST_DATE)
                            if (touchValues.size() > 0) {
                                contentResolver?.update(buildUri(masterEventUri), touchValues, null, null)
                            }

                            uiThreadHandler.post {
                                finishWithSuccess(exceptionEventId.toString(), pendingChannelResult)
                            }
                        } else {
                            uiThreadHandler.post {
                                finishWithError(EC.GENERIC_ERROR, "Failed to insert exception event", pendingChannelResult)
                            }
                        }
                    }
                } else {
                    // --- Case 3: Edit This and Future Instances ---
                    GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        val originalEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                        val originalEventCursor = contentResolver?.query(
                            originalEventUri,
                            arrayOf(Events.DTSTART, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE),
                            null, null, null
                        )
                        var originalStart: Long? = null
                        var originalDuration: String? = null
                        var originalTimezone: String? = null
                        var originalRrule: String? = null
                        try {
                            if (originalEventCursor != null && originalEventCursor.moveToFirst()) {
                                originalStart = originalEventCursor.getLong(0)
                                originalDuration = originalEventCursor.getString(1)
                                originalTimezone = originalEventCursor.getString(2)
                                originalRrule = originalEventCursor.getString(3)
                            }
                        } finally {
                            originalEventCursor?.close()
                        }

                        if (originalRrule == null) {
                            uiThreadHandler.post {
                                finishWithError(EC.GENERIC_ERROR, "Cannot split a non-recurring event", pendingChannelResult)
                            }
                            return@launch
                        }

                        // Query instances ONCE before modifying the original RRULE to avoid race conditions
                        val newRule = Rrule(originalRrule)
                        val instancesCursor = CalendarContract.Instances.query(
                            contentResolver,
                            Cst.EVENT_INSTANCE_DELETION,
                            originalStart ?: 0,
                            instanceStartDate - 1
                        )
                        var occurrencesBeforeSplit = 0
                        var lastRecurrenceBeginDate: Long? = null
                        if (instancesCursor != null) {
                            try {
                                while (instancesCursor.moveToNext()) {
                                    if (eventId == instancesCursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)) {
                                        occurrencesBeforeSplit++
                                        lastRecurrenceBeginDate = instancesCursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                                    }
                                }
                            } finally {
                                instancesCursor.close()
                            }
                        }

                        if (occurrencesBeforeSplit == 0) {
                            println("LOG_REPRO_KOTLIN: occurrencesBeforeSplit is 0, deleting original event $eventId")
                            contentResolver?.delete(buildUri(originalEventUri), null, null)
                        } else {
                            // Truncate the original event's RRULE
                            if (newRule.count != null && newRule.count > 0) {
                                newRule.count = occurrencesBeforeSplit
                            } else {
                                if (lastRecurrenceBeginDate != null) {
                                    newRule.until = DateTime(lastRecurrenceBeginDate)
                                } else {
                                    newRule.until = DateTime(instanceStartDate - 1)
                                }
                            }

                            val truncateValues = ContentValues().apply {
                                put(Events.RRULE, newRule.toString())
                                putNull(Events.LAST_DATE)
                                if (originalStart != null) {
                                    put(Events.DTSTART, originalStart)
                                }
                                if (originalDuration != null) {
                                    put(Events.DURATION, originalDuration)
                                }
                                if (originalTimezone != null) {
                                    put(Events.EVENT_TIMEZONE, originalTimezone)
                                }
                            }
                            println("LOG_REPRO_KOTLIN: updating original event $eventId via originalEventUri with new RRULE: ${newRule.toString()}")
                            contentResolver?.update(buildUri(originalEventUri), truncateValues, null, null)
                        }

                        // Adjust the new split event's COUNT using the pre-computed occurrencesBeforeSplit
                        if (event.recurrenceRule != null) {
                            val origRfcRule = Rrule(originalRrule)
                            if (origRfcRule.count != null && origRfcRule.count > 0) {
                                val remainingCount = origRfcRule.count - occurrencesBeforeSplit
                                if (remainingCount > 0) {
                                    event.recurrenceRule!!.count = remainingCount
                                }
                            }
                        }

                        val newValues = buildEventContentValues(event, calendarId)
                        if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                            newValues.put(Events._SYNC_ID, UUID.randomUUID().toString())
                        }
                        val targetUri = buildUri(Events.CONTENT_URI)
                        val insertedUri = contentResolver?.insert(targetUri, newValues)
                        val newEventId = insertedUri?.lastPathSegment?.toLongOrNull()

                        if (newEventId != null) {
                            insertAttendees(event.attendees, newEventId, contentResolver)
                            insertReminders(event.reminders, newEventId, contentResolver)

                            uiThreadHandler.post {
                                finishWithSuccess(newEventId.toString(), pendingChannelResult)
                            }
                        } else {
                            uiThreadHandler.post {
                                finishWithError(EC.GENERIC_ERROR, "Failed to insert split event", pendingChannelResult)
                            }
                        }
                    }
                }
            } else {
                // --- Case 1: Edit All Instances ---
                val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                    uiThreadHandler.post {
                        finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                    }
                }

                val job: Job
                var eventIdNumber: Long? = eventId
                if (eventIdNumber == null) {
                    if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                        values.put(Events._SYNC_ID, UUID.randomUUID().toString())
                    }
                    val targetUri = buildUri(Events.CONTENT_URI)
                    val uri = contentResolver?.insert(targetUri, values)
                    eventIdNumber = java.lang.Long.parseLong(uri?.lastPathSegment!!)
                    job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        insertAttendees(event.attendees, eventIdNumber, contentResolver)
                        insertReminders(event.reminders, eventIdNumber, contentResolver)
                    }
                } else {
                    job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        // For recurring events, preserve the series' original DTSTART to avoid
                        // overwriting it with an instance date (e.g., editing the Feb 4 instance
                        // should not change the series start from Jan 1 to Feb 4).
                        // For non-recurring events, the user-provided start date is used as-is.
                        if (event.recurrenceRule != null) {
                            val masterUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                            val masterCursor = contentResolver?.query(
                                masterUri,
                                arrayOf(Events.DTSTART),
                                null, null, null
                            )
                            try {
                                if (masterCursor != null && masterCursor.moveToFirst()) {
                                    val originalDtStart = masterCursor.getLong(0)
                                    if (event.eventAllDay) {
                                        // All-day: preserve original DTSTART as-is (midnight UTC)
                                        values.put(Events.DTSTART, originalDtStart)
                                    } else {
                                        // Timed event: preserve the original DATE, apply user's TIME-OF-DAY
                                        val originalCal = java.util.Calendar.getInstance().apply {
                                            timeInMillis = originalDtStart
                                        }
                                        val userCal = java.util.Calendar.getInstance().apply {
                                            timeInMillis = event.eventStartDate!!
                                        }
                                        originalCal.set(java.util.Calendar.HOUR_OF_DAY, userCal.get(java.util.Calendar.HOUR_OF_DAY))
                                        originalCal.set(java.util.Calendar.MINUTE, userCal.get(java.util.Calendar.MINUTE))
                                        originalCal.set(java.util.Calendar.SECOND, userCal.get(java.util.Calendar.SECOND))
                                        originalCal.set(java.util.Calendar.MILLISECOND, userCal.get(java.util.Calendar.MILLISECOND))
                                        values.put(Events.DTSTART, originalCal.timeInMillis)
                                    }
                                }
                            } finally {
                                masterCursor?.close()
                            }
                        }

                        val targetUri = buildUri(ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber))
                        contentResolver?.update(
                            targetUri,
                            values,
                            null,
                            null
                        )
                        val existingAttendees =
                            retrieveAttendees(calendar, eventIdNumber.toString(), contentResolver)
                        val attendeesToDelete =
                            if (event.attendees.isNotEmpty()) existingAttendees.filter { existingAttendee -> event.attendees.all { it.emailAddress != existingAttendee.emailAddress } } else existingAttendees
                        for (attendeeToDelete in attendeesToDelete) {
                            deleteAttendee(eventIdNumber, attendeeToDelete, contentResolver)
                        }

                        val attendeesToInsert =
                            event.attendees.filter { existingAttendees.all { existingAttendee -> existingAttendee.emailAddress != it.emailAddress } }
                        insertAttendees(attendeesToInsert, eventIdNumber, contentResolver)
                        deleteExistingReminders(contentResolver, eventIdNumber)
                        insertReminders(event.reminders, eventIdNumber, contentResolver!!)

                        val existingSelfAttendee = existingAttendees.firstOrNull {
                            it.emailAddress == calendar.ownerAccount
                        }
                        val newSelfAttendee = event.attendees.firstOrNull {
                            it.emailAddress == calendar.ownerAccount
                        }
                        if (existingSelfAttendee != null && newSelfAttendee != null &&
                            newSelfAttendee.attendanceStatus != null &&
                            existingSelfAttendee.attendanceStatus != newSelfAttendee.attendanceStatus
                        ) {
                            updateAttendeeStatus(eventIdNumber, newSelfAttendee, contentResolver)
                        }
                    }
                }
                job.invokeOnCompletion { cause ->
                    if (cause == null) {
                        uiThreadHandler.post {
                            finishWithSuccess(eventIdNumber.toString(), pendingChannelResult)
                        }
                    }
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                CREATE_OR_UPDATE_EVENT_REQUEST_CODE,
                calendarId
            )
            parameters.event = event
            requestPermissions(parameters)
        }
    }

    private fun deleteExistingReminders(contentResolver: ContentResolver?, eventId: Long) {
        val cursor = CalendarContract.Reminders.query(
            contentResolver, eventId, arrayOf(
                CalendarContract.Reminders._ID
            )
        )
        while (cursor != null && cursor.moveToNext()) {
            var reminderUri: Uri? = null
            val reminderId = cursor.getLong(0)
            if (reminderId > 0) {
                reminderUri =
                    ContentUris.withAppendedId(CalendarContract.Reminders.CONTENT_URI, reminderId)
            }
            if (reminderUri != null) {
                contentResolver?.delete(reminderUri, null, null)
            }
        }
        cursor?.close()
    }

    @SuppressLint("MissingPermission")
    private fun insertReminders(
        reminders: List<Reminder>,
        eventId: Long?,
        contentResolver: ContentResolver
    ) {
        if (reminders.isEmpty()) {
            return
        }
        val remindersContentValues = reminders.map {
            ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, eventId)
                put(CalendarContract.Reminders.MINUTES, it.minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            }
        }.toTypedArray()
        contentResolver.bulkInsert(CalendarContract.Reminders.CONTENT_URI, remindersContentValues)
    }

    private fun buildEventContentValues(event: Event, calendarId: String): ContentValues {
        val values = ContentValues()

        values.put(Events.ALL_DAY, if (event.eventAllDay) 1 else 0)
        values.put(Events.DTSTART, event.eventStartDate!!)
        values.put(Events.EVENT_TIMEZONE, getTimeZone(event.eventStartTimeZone).id)
        values.put(Events.TITLE, event.eventTitle)
        values.put(Events.DESCRIPTION, event.eventDescription)
        values.put(Events.EVENT_LOCATION, event.eventLocation)
        values.put(Events.CUSTOM_APP_URI, event.eventURL)
        values.put(Events.CALENDAR_ID, calendarId)
        val availability = getAvailability(event.availability)
        if (availability != null) {
            values.put(Events.AVAILABILITY, availability)
        }
        var status: Int? = getEventStatus(event.eventStatus)
        if (status != null) {
            values.put(Events.STATUS, status)
        }

        var duration: String? = null
        var end: Long? = null
        var endTimeZone: String? = null

        if (event.recurrenceRule != null) {
            val recurrenceRuleParams = buildRecurrenceRuleParams(event.recurrenceRule!!)
            values.put(Events.RRULE, recurrenceRuleParams)
            val difference = event.eventEndDate!!.minus(event.eventStartDate!!)
            val rawDuration = difference.toDuration(DurationUnit.MILLISECONDS)
            rawDuration.toComponents { days, hours, minutes, seconds, _ ->
                if (days > 0 || hours > 0 || minutes > 0 || seconds > 0) duration = "P"
                if (days > 0) duration = duration.plus("${days}D")
                if (hours > 0 || minutes > 0 || seconds > 0) duration = duration.plus("T")
                if (hours > 0) duration = duration.plus("${hours}H")
                if (minutes > 0) duration = duration.plus("${minutes}M")
                if (seconds > 0) duration = duration.plus("${seconds}S")
            }
        } else {
            end = event.eventEndDate!!
            endTimeZone = getTimeZone(event.eventEndTimeZone).id
        }
        values.put(Events.DTEND, end)
        values.put(Events.EVENT_END_TIMEZONE, endTimeZone)
        values.put(Events.DURATION, duration)
        values.put(Events.EVENT_COLOR_KEY, event.eventColorKey)
        values.put(Events.EVENT_COLOR, event.eventColor)
        return values
    }

    private fun getTimeZone(timeZoneString: String?): TimeZone {
        val deviceTimeZone: TimeZone = java.util.Calendar.getInstance().timeZone
        var timeZone = TimeZone.getTimeZone(timeZoneString ?: deviceTimeZone.id)

        // Invalid time zone names defaults to GMT so update that to be device's time zone
        if (timeZone.id == "GMT" && timeZoneString != "GMT") {
            timeZone = TimeZone.getTimeZone(deviceTimeZone.id)
        }

        return timeZone
    }

    private fun getAvailability(availability: Availability?): Int? = when (availability) {
        Availability.BUSY -> Events.AVAILABILITY_BUSY
        Availability.FREE -> Events.AVAILABILITY_FREE
        Availability.TENTATIVE -> Events.AVAILABILITY_TENTATIVE
        else -> null
    }

    private fun getEventStatus(eventStatus: EventStatus?): Int? = when (eventStatus) {
        EventStatus.CONFIRMED -> Events.STATUS_CONFIRMED
        EventStatus.TENTATIVE -> Events.STATUS_TENTATIVE
        EventStatus.CANCELED -> Events.STATUS_CANCELED
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun insertAttendees(
        attendees: List<Attendee>,
        eventId: Long?,
        contentResolver: ContentResolver?
    ) {
        if (attendees.isEmpty()) {
            return
        }

        val attendeesValues = attendees.map {
            ContentValues().apply {
                put(CalendarContract.Attendees.ATTENDEE_NAME, it.name)
                put(CalendarContract.Attendees.ATTENDEE_EMAIL, it.emailAddress)
                put(
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                )
                put(CalendarContract.Attendees.ATTENDEE_TYPE, it.role)
                put(
                    CalendarContract.Attendees.ATTENDEE_STATUS,
                    it.attendanceStatus
                )
                put(CalendarContract.Attendees.EVENT_ID, eventId)
            }
        }.toTypedArray()

        contentResolver?.bulkInsert(CalendarContract.Attendees.CONTENT_URI, attendeesValues)
    }

    @SuppressLint("MissingPermission")
    private fun deleteAttendee(
        eventId: Long,
        attendee: Attendee,
        contentResolver: ContentResolver?
    ) {
        val selection =
            "(" + CalendarContract.Attendees.EVENT_ID + " = ?) AND (" + CalendarContract.Attendees.ATTENDEE_EMAIL + " = ?)"
        val selectionArgs = arrayOf(eventId.toString() + "", attendee.emailAddress)
        contentResolver?.delete(CalendarContract.Attendees.CONTENT_URI, selection, selectionArgs)

    }

    private fun updateAttendeeStatus(
        eventId: Long,
        attendee: Attendee,
        contentResolver: ContentResolver?
    ) {
        val selection =
            "(" + CalendarContract.Attendees.EVENT_ID + " = ?) AND (" + CalendarContract.Attendees.ATTENDEE_EMAIL + " = ?)"
        val selectionArgs = arrayOf(eventId.toString() + "", attendee.emailAddress)
        val values = ContentValues()
        values.put(CalendarContract.Attendees.ATTENDEE_STATUS, attendee.attendanceStatus)
        contentResolver?.update(
            CalendarContract.Attendees.CONTENT_URI,
            values,
            selection,
            selectionArgs
        )
    }

    fun deleteEvent(
        calendarId: String,
        eventId: String,
        pendingChannelResult: MethodChannel.Result,
        startDate: Long? = null,
        endDate: Long? = null,
        followingInstances: Boolean? = null
    ) {
        println("LOG_REPRO_KOTLIN: deleteEvent called with calendarId=$calendarId, eventId=$eventId, startDate=$startDate, endDate=$endDate, followingInstances=$followingInstances")
        if (arePermissionsGranted()) {
            val existingCal = retrieveCalendar(calendarId, pendingChannelResult, true)
            if (existingCal == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The calendar with the ID $calendarId could not be found",
                    pendingChannelResult
                )
                return
            }
            println("LOG_REPRO_KOTLIN: existingCal accountName=${existingCal.accountName}, accountType=${existingCal.accountType}")

            if (existingCal.isReadOnly) {
                finishWithError(
                    EC.NOT_ALLOWED,
                    "Calendar with ID $calendarId is read-only",
                    pendingChannelResult
                )
                return
            }

            val eventIdNumber = eventId.toLongOrNull()
            if (eventIdNumber == null) {
                finishWithError(
                    EC.INVALID_ARGUMENT,
                    EM.EVENT_ID_CANNOT_BE_NULL_ON_DELETION_MESSAGE,
                    pendingChannelResult
                )
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val buildUri = { uri: Uri ->
                if (existingCal.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                    uri.buildUpon()
                        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, existingCal.accountName)
                        .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, existingCal.accountType)
                        .build()
                } else {
                    uri
                }
            }

            if (startDate == null && endDate == null && followingInstances == null) { // Delete all instances
                println("LOG_REPRO_KOTLIN: Delete all instances branch")
                val eventsUriWithId = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                val masterEventCursor = contentResolver?.query(
                    eventsUriWithId,
                    arrayOf(Events._SYNC_ID),
                    null, null, null
                )
                var masterSyncId: String? = null
                if (masterEventCursor != null && masterEventCursor.moveToFirst()) {
                    masterSyncId = masterEventCursor.getString(0)
                    masterEventCursor.close()
                }

                val ops = ArrayList<ContentProviderOperation>()
                val clearRecurrenceValues = ContentValues().apply {
                    putNull(Events.RRULE)
                    putNull(Events.EXRULE)
                    putNull(Events.EXDATE)
                } // Clear recurrence data to avoid leaving orphaned recurrence info
                ops.add(
                    ContentProviderOperation.newUpdate(buildUri(eventsUriWithId))
                        .withValues(clearRecurrenceValues)
                        .build()
                )
                ops.add(
                    ContentProviderOperation.newDelete(buildUri(eventsUriWithId))
                        .build()
                )

                // Also delete any exception events tied to this master event
                val exceptionWhere = if (!masterSyncId.isNullOrEmpty()) {
                    "(${Events.ORIGINAL_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)"
                } else {
                    "${Events.ORIGINAL_ID} = ?"
                }
                val exceptionArgs = if (!masterSyncId.isNullOrEmpty()) {
                    arrayOf(eventIdNumber.toString(), masterSyncId)
                } else {
                    arrayOf(eventIdNumber.toString())
                }
                ops.add(
                    ContentProviderOperation.newDelete(buildUri(Events.CONTENT_URI))
                        .withSelection(exceptionWhere, exceptionArgs)
                        .build()
                )

                try {
                    // Apply operations as a single atomic batch
                    val results = contentResolver?.applyBatch(CalendarContract.AUTHORITY, ops)
                    // The delete operation is at index 1.
                    val deleteSucceeded = results?.get(1)?.count ?: 0 > 0
                    finishWithSuccess(deleteSucceeded, pendingChannelResult)
                } catch (e: Exception) {
                    // Handle potential exceptions from applyBatch, like OperationApplicationException
                    finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
                }
            } else {
                if (!followingInstances!!) { // Only this instance
                    println("LOG_REPRO_KOTLIN: Only this instance branch")
                    val masterEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                    val masterEventCursor = contentResolver?.query(
                        masterEventUri,
                        arrayOf(Events.DTSTART, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE, Events._SYNC_ID),
                        null, null, null
                    )
                    var masterStart: Long? = null
                    var masterDuration: String? = null
                    var masterTimezone: String? = null
                    var masterRrule: String? = null
                    var masterSyncId: String? = null
                    if (masterEventCursor != null && masterEventCursor.moveToFirst()) {
                        masterStart = masterEventCursor.getLong(0)
                        masterDuration = masterEventCursor.getString(1)
                        masterTimezone = masterEventCursor.getString(2)
                        masterRrule = masterEventCursor.getString(3)
                        masterSyncId = masterEventCursor.getString(4)
                        masterEventCursor.close()
                    }

                    if (masterSyncId.isNullOrEmpty() && existingCal.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                        masterSyncId = UUID.randomUUID().toString()
                        val updateValues = ContentValues().apply {
                            put(Events._SYNC_ID, masterSyncId)
                        }
                        contentResolver?.update(buildUri(masterEventUri), updateValues, null, null)
                        println("LOG_REPRO_KOTLIN: Updated master event $eventIdNumber with generated _sync_id: $masterSyncId")
                    }

                    val exceptionUriWithId =
                        ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventIdNumber)
                    val values = ContentValues()
                    val instanceCursor = CalendarContract.Instances.query(
                        contentResolver,
                        Cst.EVENT_INSTANCE_DELETION,
                        startDate!!,
                        endDate!!
                    )
                    println("LOG_REPRO_KOTLIN: instanceCursor size: ${instanceCursor.count}")

                    while (instanceCursor.moveToNext()) {
                        val foundEventID =
                            instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)
                        println("LOG_REPRO_KOTLIN: foundEventID=$foundEventID, eventIdNumber=$eventIdNumber")

                        if (eventIdNumber == foundEventID) {
                            val instanceBegin = instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                            val instanceEnd = instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_END_INDEX)
                            values.put(Events.ORIGINAL_INSTANCE_TIME, instanceBegin)
                            values.put(Events.STATUS, Events.STATUS_CANCELED)
                            values.put(Events.DTSTART, instanceBegin)
                            if (!masterSyncId.isNullOrEmpty()) {
                                values.put(Events.ORIGINAL_SYNC_ID, masterSyncId)
                            }
                            if (masterDuration != null) {
                                values.put(Events.DURATION, masterDuration)
                            } else {
                                values.put(Events.DTEND, instanceEnd)
                            }
                            if (masterTimezone != null) {
                                values.put(Events.EVENT_TIMEZONE, masterTimezone)
                            } else {
                                values.put(Events.EVENT_TIMEZONE, "UTC")
                            }
                        }
                    }

                    val targetUri = buildUri(exceptionUriWithId)
                    println("LOG_REPRO_KOTLIN: inserting exception to $targetUri with values: $values")
                    val deleteSucceeded = contentResolver?.insert(targetUri, values)
                    println("LOG_REPRO_KOTLIN: deleteSucceeded result: $deleteSucceeded")

                    instanceCursor.close()

                    if (deleteSucceeded != null) {
                        // For local account calendars (SYNC_EVENTS = 0), we must perform a safe "touch" update
                        // on the master event to force the Calendar Provider to re-expand the virtual instances.
                        // We must explicitly include the RRULE in the update values to trigger the recalculation.
                        val touchValues = ContentValues()
                        if (masterStart != null) {
                            touchValues.put(Events.DTSTART, masterStart)
                        }
                        if (masterDuration != null) {
                            touchValues.put(Events.DURATION, masterDuration)
                        }
                        if (masterTimezone != null) {
                            touchValues.put(Events.EVENT_TIMEZONE, masterTimezone)
                        }
                        if (masterRrule != null) {
                            touchValues.put(Events.RRULE, masterRrule)
                        }
                        touchValues.putNull(Events.LAST_DATE) // Clear LAST_DATE to force recalculation of the recurrence
                        if (touchValues.size() > 0) {
                            println("LOG_REPRO_KOTLIN: performing touch update on master event $eventIdNumber AFTER exception insertion")
                            contentResolver?.update(buildUri(masterEventUri), touchValues, null, null)
                        }
                    }
                    finishWithSuccess(deleteSucceeded != null, pendingChannelResult)
                } else { // This and following instances
                    println("LOG_REPRO_KOTLIN: This and following instances branch for event $eventIdNumber starting at $startDate")
                    val eventsUriWithId = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                    val masterEventCursor = contentResolver?.query(
                        eventsUriWithId,
                        arrayOf(Events.DTSTART, Events.DURATION, Events.EVENT_TIMEZONE, Events.RRULE),
                        null, null, null
                    )
                    var masterStart: Long? = null
                    var masterDuration: String? = null
                    var masterTimezone: String? = null
                    var masterRrule: String? = null
                    if (masterEventCursor != null && masterEventCursor.moveToFirst()) {
                        masterStart = masterEventCursor.getLong(0)
                        masterDuration = masterEventCursor.getString(1)
                        masterTimezone = masterEventCursor.getString(2)
                        masterRrule = masterEventCursor.getString(3)
                        masterEventCursor.close()
                    }

                    if (masterRrule.isNullOrEmpty()) {
                        println("LOG_REPRO_KOTLIN: master event has no RRULE, deleting entire event")
                        contentResolver?.delete(buildUri(eventsUriWithId), null, null)
                        finishWithSuccess(true, pendingChannelResult)
                    } else {
                        val newRule = Rrule(masterRrule)
                        val queryStart = masterStart ?: (startDate!! - DateUtils.YEAR_IN_MILLIS)
                        val cursor = CalendarContract.Instances.query(
                            contentResolver,
                            Cst.EVENT_INSTANCE_DELETION,
                            queryStart,
                            startDate!! - 1
                        )
                        var lastRecurrenceDate: Long? = null
                        var remainingCount = 0
                        if (cursor != null) {
                            while (cursor.moveToNext()) {
                                if (eventIdNumber == cursor.getLong(Cst.EVENT_INSTANCE_DELETION_ID_INDEX)) {
                                    remainingCount++
                                    lastRecurrenceDate = cursor.getLong(Cst.EVENT_INSTANCE_DELETION_END_INDEX)
                                }
                            }
                            cursor.close()
                        }
                        println("LOG_REPRO_KOTLIN: remainingCount=$remainingCount, lastRecurrenceDate=$lastRecurrenceDate")

                        if (lastRecurrenceDate != null && remainingCount > 0) {
                            if (newRule.count != null && newRule.count > 0) {
                                newRule.count = remainingCount
                            } else {
                                newRule.until = DateTime(lastRecurrenceDate)
                            }

                            val values = ContentValues().apply {
                                put(Events.RRULE, newRule.toString())
                                putNull(Events.LAST_DATE)
                                if (masterStart != null) put(Events.DTSTART, masterStart)
                                if (masterDuration != null) put(Events.DURATION, masterDuration)
                                if (masterTimezone != null) put(Events.EVENT_TIMEZONE, masterTimezone)
                            }
                            val targetUri = buildUri(eventsUriWithId)
                            println("LOG_REPRO_KOTLIN: updating event $eventIdNumber via $targetUri with values: $values")
                            contentResolver?.update(targetUri, values, null, null)
                            finishWithSuccess(true, pendingChannelResult)
                        } else {
                            println("LOG_REPRO_KOTLIN: deleting event $eventIdNumber because 0 instances remain prior to $startDate")
                            val targetUri = buildUri(eventsUriWithId)
                            contentResolver?.delete(targetUri, null, null)
                            finishWithSuccess(true, pendingChannelResult)
                        }
                    }
                }
            }
        } else {
            val parameters = CalendarMethodsParametersCacheModel(
                pendingChannelResult,
                DELETE_EVENT_REQUEST_CODE,
                calendarId
            )
            parameters.eventId = eventId
            requestPermissions(parameters)
        }
    }

    private fun arePermissionsGranted(): Boolean {
        if (atLeastAPI(23) && _binding != null) {
            val writeCalendarPermissionGranted = _binding!!.activity.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            val readCalendarPermissionGranted = _binding!!.activity.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
            return writeCalendarPermissionGranted && readCalendarPermissionGranted
        }

        return true
    }

    private fun requestPermissions(parameters: CalendarMethodsParametersCacheModel) {
        val requestCode: Int = generateUniqueRequestCodeAndCacheParameters(parameters)
        requestPermissions(requestCode)
    }

    private fun requestPermissions(requestCode: Int) {
        if (atLeastAPI(23)) {
            _binding!!.activity.requestPermissions(
                arrayOf(
                    Manifest.permission.WRITE_CALENDAR,
                    Manifest.permission.READ_CALENDAR
                ), requestCode
            )
        }
    }

    private fun getColumnString(cursor: Cursor, columnName: String, fallbackIndex: Int = -1): String? {
        val index = cursor.getColumnIndex(columnName)
        val effectiveIndex = if (index >= 0) index else fallbackIndex
        return if (effectiveIndex >= 0 && !cursor.isNull(effectiveIndex)) {
            try {
                cursor.getString(effectiveIndex)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    private fun getColumnLong(cursor: Cursor, columnName: String, fallbackIndex: Int = -1, default: Long = 0L): Long {
        val index = cursor.getColumnIndex(columnName)
        val effectiveIndex = if (index >= 0) index else fallbackIndex
        return if (effectiveIndex >= 0 && !cursor.isNull(effectiveIndex)) {
            try {
                cursor.getLong(effectiveIndex)
            } catch (e: Exception) {
                default
            }
        } else default
    }

    private fun getColumnInt(cursor: Cursor, columnName: String, fallbackIndex: Int = -1, default: Int = 0): Int {
        val index = cursor.getColumnIndex(columnName)
        val effectiveIndex = if (index >= 0) index else fallbackIndex
        return if (effectiveIndex >= 0 && !cursor.isNull(effectiveIndex)) {
            try {
                cursor.getInt(effectiveIndex)
            } catch (e: Exception) {
                default
            }
        } else default
    }

    private fun parseCalendarRow(cursor: Cursor?): Calendar? {
        if (cursor == null) {
            return null
        }

        val calId = getColumnLong(cursor, CalendarContract.Calendars._ID, Cst.CALENDAR_PROJECTION_ID_INDEX)
        val displayName = getColumnString(cursor, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, Cst.CALENDAR_PROJECTION_DISPLAY_NAME_INDEX) ?: ""
        val accessLevel = getColumnInt(cursor, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, Cst.CALENDAR_PROJECTION_ACCESS_LEVEL_INDEX)
        val calendarColor = getColumnInt(cursor, CalendarContract.Calendars.CALENDAR_COLOR, Cst.CALENDAR_PROJECTION_COLOR_INDEX)
        val accountName = getColumnString(cursor, CalendarContract.Calendars.ACCOUNT_NAME, Cst.CALENDAR_PROJECTION_ACCOUNT_NAME_INDEX) ?: ""
        val accountType = getColumnString(cursor, CalendarContract.Calendars.ACCOUNT_TYPE, Cst.CALENDAR_PROJECTION_ACCOUNT_TYPE_INDEX) ?: ""
        val ownerAccount = getColumnString(cursor, CalendarContract.Calendars.OWNER_ACCOUNT, Cst.CALENDAR_PROJECTION_OWNER_ACCOUNT_INDEX) ?: ""

        val calendar = Calendar(
            calId.toString(),
            displayName,
            calendarColor,
            accountName,
            accountType,
            ownerAccount
        )

        calendar.isReadOnly = isCalendarReadOnly(accessLevel)
        if (atLeastAPI(17)) {
            val isPrimary = getColumnString(cursor, CalendarContract.Calendars.IS_PRIMARY, Cst.CALENDAR_PROJECTION_IS_PRIMARY_INDEX)
            calendar.isDefault = isPrimary == "1"
        } else {
            calendar.isDefault = false
        }
        return calendar
    }

    private fun parseEvent(calendarId: String, cursor: Cursor?): Event? {
        if (cursor == null) {
            return null
        }
        val eventId = getColumnLong(cursor, CalendarContract.Instances.EVENT_ID, Cst.EVENT_PROJECTION_ID_INDEX)
        val title = getColumnString(cursor, Events.TITLE, Cst.EVENT_PROJECTION_TITLE_INDEX)
        val description = getColumnString(cursor, Events.DESCRIPTION, Cst.EVENT_PROJECTION_DESCRIPTION_INDEX)
        val begin = getColumnLong(cursor, CalendarContract.Instances.BEGIN, Cst.EVENT_PROJECTION_BEGIN_INDEX)
        val end = getColumnLong(cursor, CalendarContract.Instances.END, Cst.EVENT_PROJECTION_END_INDEX)
        val recurringRule = getColumnString(cursor, Events.RRULE, Cst.EVENT_PROJECTION_RECURRING_RULE_INDEX)
        val allDay = getColumnInt(cursor, Events.ALL_DAY, Cst.EVENT_PROJECTION_ALL_DAY_INDEX) > 0
        val location = getColumnString(cursor, Events.EVENT_LOCATION, Cst.EVENT_PROJECTION_EVENT_LOCATION_INDEX)
        val url = getColumnString(cursor, Events.CUSTOM_APP_URI, Cst.EVENT_PROJECTION_CUSTOM_APP_URI_INDEX)
        val startTimeZone = getColumnString(cursor, Events.EVENT_TIMEZONE, Cst.EVENT_PROJECTION_START_TIMEZONE_INDEX)
        val endTimeZone = getColumnString(cursor, Events.EVENT_END_TIMEZONE, Cst.EVENT_PROJECTION_END_TIMEZONE_INDEX)
        val availability = parseAvailability(getColumnInt(cursor, Events.AVAILABILITY, Cst.EVENT_PROJECTION_AVAILABILITY_INDEX))
        val eventStatus = parseEventStatus(getColumnInt(cursor, Events.STATUS, Cst.EVENT_PROJECTION_STATUS_INDEX))
        val eventColor = getColumnInt(cursor, Events.EVENT_COLOR, Cst.EVENT_PROJECTION_EVENT_COLOR_INDEX)
        val eventColorKey = getColumnInt(cursor, Events.EVENT_COLOR_KEY, Cst.EVENT_PROJECTION_EVENT_COLOR_KEY_INDEX)
        val event = Event()
        event.eventTitle = title ?: "New Event"
        event.eventId = eventId.toString()
        event.calendarId = calendarId
        event.eventDescription = description
        event.eventStartDate = begin
        event.eventEndDate = end
        event.eventAllDay = allDay
        event.eventLocation = location
        event.eventURL = url
        event.recurrenceRule = parseRecurrenceRuleString(recurringRule)
        event.eventStartTimeZone = startTimeZone
        event.eventEndTimeZone = endTimeZone
        event.availability = availability
        event.eventStatus = eventStatus
        event.eventColor = if (eventColor == 0) null else eventColor
        event.eventColorKey = if (eventColorKey == 0) null else eventColorKey

        return event
    }

    private fun parseRecurrenceRuleString(recurrenceRuleString: String?): RecurrenceRule? {
        if (recurrenceRuleString == null) {
            return null
        }
        val rfcRecurrenceRule = Rrule(recurrenceRuleString)
        val frequency = when (rfcRecurrenceRule.freq) {
            RruleFreq.YEARLY -> RruleFreq.YEARLY
            RruleFreq.MONTHLY -> RruleFreq.MONTHLY
            RruleFreq.WEEKLY -> RruleFreq.WEEKLY
            RruleFreq.DAILY -> RruleFreq.DAILY
            else -> null
        } ?: return null
        //Avoid handling HOURLY/MINUTELY/SECONDLY frequencies for now

        val recurrenceRule = RecurrenceRule(frequency)

        recurrenceRule.count = rfcRecurrenceRule.count
        recurrenceRule.interval = rfcRecurrenceRule.interval

        val until = rfcRecurrenceRule.until
        if (until != null) {
            recurrenceRule.until = formatDateTime(dateTime = until)
        }

        recurrenceRule.sourceRruleString = recurrenceRuleString

        //TODO: Force set to Monday (atm RRULE package only seem to support Monday)
        recurrenceRule.wkst = /*rfcRecurrenceRule.weekStart.name*/Weekday.MO.name
        recurrenceRule.byday = rfcRecurrenceRule.byDayPart?.mapNotNull {
            it.toString()
        }?.toMutableList()
        recurrenceRule.bymonthday = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTHDAY)
        recurrenceRule.byyearday = rfcRecurrenceRule.getByPart(Rrule.Part.BYYEARDAY)
        recurrenceRule.byweekno = rfcRecurrenceRule.getByPart(Rrule.Part.BYWEEKNO)

        // Below adjustment of byMonth ints is necessary as the library somehow gives a wrong int
        // See also [buildRecurrenceRuleParams] where 1 is subtracted.
        val oldByMonth = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTH)
        if (oldByMonth != null) {
            val newByMonth = mutableListOf<Int>()
            for (month in oldByMonth) {
                newByMonth.add(month + 1)
            }
            recurrenceRule.bymonth = newByMonth
        } else {
            recurrenceRule.bymonth = rfcRecurrenceRule.getByPart(Rrule.Part.BYMONTH)
        }

        recurrenceRule.bysetpos = rfcRecurrenceRule.getByPart(Rrule.Part.BYSETPOS)

        return recurrenceRule
    }

    private fun formatDateTime(dateTime: DateTime): String {
        assert(dateTime.year in 0..9999)

        fun twoDigits(n: Int): String {
            return if (n < 10) "0$n" else "$n"
        }

        fun fourDigits(n: Int): String {
            val absolute = n.absoluteValue
            val sign = if (n < 0) "-" else ""
            if (absolute >= 1000) return "$n"
            if (absolute >= 100) return "${sign}0$absolute"
            if (absolute >= 10) return "${sign}00$absolute"
            return "${sign}000$absolute"
        }

        val year = fourDigits(dateTime.year)
        val month = twoDigits(dateTime.month.plus(1))
        val day = twoDigits(dateTime.dayOfMonth)
        val hour = twoDigits(dateTime.hours)
        val minute = twoDigits(dateTime.minutes)
        val second = twoDigits(dateTime.seconds)
        val utcSuffix = if (dateTime.timeZone == UTC) 'Z' else ""
        return "$year-$month-${day}T$hour:$minute:$second$utcSuffix"
    }

    private fun parseAttendeeRow(calendar: Calendar, cursor: Cursor?): Attendee? {
        if (cursor == null) {
            return null
        }

        val emailAddress = getColumnString(cursor, CalendarContract.Attendees.ATTENDEE_EMAIL, Cst.ATTENDEE_EMAIL_INDEX) ?: return null

        return Attendee(
            emailAddress,
            getColumnString(cursor, CalendarContract.Attendees.ATTENDEE_NAME, Cst.ATTENDEE_NAME_INDEX),
            getColumnInt(cursor, CalendarContract.Attendees.ATTENDEE_TYPE, Cst.ATTENDEE_TYPE_INDEX),
            getColumnInt(cursor, CalendarContract.Attendees.ATTENDEE_STATUS, Cst.ATTENDEE_STATUS_INDEX),
            getColumnInt(cursor, CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, Cst.ATTENDEE_RELATIONSHIP_INDEX) == CalendarContract.Attendees.RELATIONSHIP_ORGANIZER,
            emailAddress == calendar.ownerAccount
        )
    }

    private fun parseReminderRow(cursor: Cursor?): Reminder? {
        if (cursor == null) {
            return null
        }

        return Reminder(getColumnInt(cursor, CalendarContract.Reminders.MINUTES, Cst.REMINDER_MINUTES_INDEX))
    }

    private fun isCalendarReadOnly(accessLevel: Int): Boolean {
        return when (accessLevel) {
            Events.CAL_ACCESS_CONTRIBUTOR,
            Events.CAL_ACCESS_ROOT,
            Events.CAL_ACCESS_OWNER,
            Events.CAL_ACCESS_EDITOR
            -> false
            else -> true
        }
    }

    @SuppressLint("MissingPermission")
    private fun retrieveAttendees(
        calendar: Calendar,
        eventId: String,
        contentResolver: ContentResolver?
    ): MutableList<Attendee> {
        val attendees: MutableList<Attendee> = mutableListOf()
        val attendeesQuery = "(${CalendarContract.Attendees.EVENT_ID} = ${eventId})"
        val attendeesCursor = contentResolver?.query(
            CalendarContract.Attendees.CONTENT_URI,
            Cst.ATTENDEE_PROJECTION,
            attendeesQuery,
            null,
            null
        )
        attendeesCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) {
                do {
                    val attendee = parseAttendeeRow(calendar, attendeesCursor) ?: continue
                    attendees.add(attendee)
                } while (cursor.moveToNext())
            }
        }

        return attendees
    }

    @SuppressLint("MissingPermission")
    private fun retrieveReminders(
        eventId: String,
        contentResolver: ContentResolver?
    ): MutableList<Reminder> {
        val reminders: MutableList<Reminder> = mutableListOf()
        val remindersQuery = "(${CalendarContract.Reminders.EVENT_ID} = ${eventId})"
        val remindersCursor = contentResolver?.query(
            CalendarContract.Reminders.CONTENT_URI,
            Cst.REMINDER_PROJECTION,
            remindersQuery,
            null,
            null
        )
        remindersCursor.use { cursor ->
            if (cursor?.moveToFirst() == true) {
                do {
                    val reminder = parseReminderRow(remindersCursor) ?: continue
                    reminders.add(reminder)
                } while (cursor.moveToNext())
            }
        }

        return reminders
    }

    /**
     * load available event colors for the given account name
     * unable to find official documentation, so logic is based on https://android.googlesource.com/platform/packages/apps/Calendar.git/+/refs/heads/pie-release/src/com/android/calendar/EventInfoFragment.java
     **/
    private fun retrieveColors(accountName: String, colorType: Int): List<Pair<Int, Int>> {
        val contentResolver: ContentResolver? = _context?.contentResolver
        val uri: Uri = Colors.CONTENT_URI
        val colors = mutableListOf<Int>()
        val displayColorKeyMap = SparseArrayCompat<Int>()

        val projection = arrayOf(
            Colors.COLOR,
            Colors.COLOR_KEY,
        )

        // load only event colors for the given account name
        val selection = "${Colors.COLOR_TYPE} = ? AND ${Colors.ACCOUNT_NAME} = ?"
        val selectionArgs = arrayOf(colorType.toString(), accountName)


        val cursor: Cursor? = contentResolver?.query(uri, projection, selection, selectionArgs, null)
        cursor?.use {
            while (it.moveToNext()) {
                val color = it.getInt(it.getColumnIndexOrThrow(Colors.COLOR))
                val colorKey = it.getInt(it.getColumnIndexOrThrow(Colors.COLOR_KEY))
                displayColorKeyMap.put(color, colorKey);
                colors.add(color)
            }
            cursor.close();
            // sort colors by colorValue, since they are loaded unordered
            colors.sortWith(HsvColorComparator())
        }
        return colors.map { Pair(it, displayColorKeyMap[it]!! ) }.toList()
    }

    fun retrieveEventColors(accountName: String): List<Pair<Int, Int>> {
        return  retrieveColors(accountName, Colors.TYPE_EVENT)
    }
    fun retrieveCalendarColors(accountName: String): List<Pair<Int, Int>> {
        return  retrieveColors(accountName, Colors.TYPE_CALENDAR)
    }

    fun updateCalendarColor(calendarId: Long, newColorKey: Int?, newColor: Int?): Boolean {
        val contentResolver: ContentResolver? = _context?.contentResolver
        val uri: Uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.CALENDAR_COLOR_KEY, newColorKey)
            put(CalendarContract.Calendars.CALENDAR_COLOR, newColor)
        }
        val rows = contentResolver?.update(uri, values, null, null)
        return (rows ?: 0) > 0
    }

    /**
     * Compares colors based on their hue values in the HSV color space.
     * https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-compose-integration-release/android-34/com/android/colorpicker/HsvColorComparator.java
     */
    private class HsvColorComparator : Comparator<Int> {
        override fun compare(color1: Int, color2: Int): Int {
            val hsv1 = FloatArray(3)
            val hsv2 = FloatArray(3)
            Color.colorToHSV(color1, hsv1)
            Color.colorToHSV(color2, hsv2)
            return hsv1[0].compareTo(hsv2[0])
        }
    }

    @Synchronized
    private fun generateUniqueRequestCodeAndCacheParameters(parameters: CalendarMethodsParametersCacheModel): Int {
        // TODO we can ran out of Int's at some point so this probably should re-use some of the freed ones
        val uniqueRequestCode: Int = (_cachedParametersMap.keys.maxOrNull() ?: 0) + 1
        parameters.ownCacheKey = uniqueRequestCode
        _cachedParametersMap[uniqueRequestCode] = parameters

        return uniqueRequestCode
    }

    private fun <T> finishWithSuccess(result: T, pendingChannelResult: MethodChannel.Result) {
        pendingChannelResult.success(result)
        clearCachedParameters(pendingChannelResult)
    }

    private fun finishWithError(
        errorCode: String,
        errorMessage: String?,
        pendingChannelResult: MethodChannel.Result
    ) {
        pendingChannelResult.error(errorCode, errorMessage, null)
        clearCachedParameters(pendingChannelResult)
    }

    private fun clearCachedParameters(pendingChannelResult: MethodChannel.Result) {
        val cachedParameters =
            _cachedParametersMap.values.filter { it.pendingChannelResult == pendingChannelResult }
                .toList()
        for (cachedParameter in cachedParameters) {
            if (_cachedParametersMap.containsKey(cachedParameter.ownCacheKey)) {
                _cachedParametersMap.remove(cachedParameter.ownCacheKey)
            }
        }
    }

    private fun atLeastAPI(api: Int): Boolean {
        return api <= Build.VERSION.SDK_INT
    }

    private fun buildRecurrenceRuleParams(recurrenceRule: RecurrenceRule): String? {
        val frequencyParam = when (recurrenceRule.freq) {
            RruleFreq.DAILY -> RruleFreq.DAILY
            RruleFreq.WEEKLY -> RruleFreq.WEEKLY
            RruleFreq.MONTHLY -> RruleFreq.MONTHLY
            RruleFreq.YEARLY -> RruleFreq.YEARLY
            else -> null
        } ?: return null

        val rr = Rrule(frequencyParam)
        if (recurrenceRule.interval != null) {
            rr.interval = recurrenceRule.interval!!
        }

        if (recurrenceRule.count != null) {
            rr.count = recurrenceRule.count!!
        } else if (recurrenceRule.until != null) {
            var untilString: String = recurrenceRule.until!!
            if (!untilString.endsWith("Z")) {
                untilString += "Z"
            }
            rr.until = parseDateTime(untilString)
        }

        if (recurrenceRule.wkst != null) {
            rr.weekStart = Weekday.valueOf(recurrenceRule.wkst!!)
        }

        if (recurrenceRule.byday != null) {
            rr.byDayPart = recurrenceRule.byday?.mapNotNull {
                WeekdayNum.valueOf(it)
            }?.toMutableList()
        }

        if (recurrenceRule.bymonthday != null) {
            rr.setByPart(Rrule.Part.BYMONTHDAY, recurrenceRule.bymonthday!!)
        }

        if (recurrenceRule.byyearday != null) {
            rr.setByPart(Rrule.Part.BYYEARDAY, recurrenceRule.byyearday!!)
        }

        if (recurrenceRule.byweekno != null) {
            rr.setByPart(Rrule.Part.BYWEEKNO, recurrenceRule.byweekno!!)
        }
        // Below adjustment of byMonth ints is necessary as the library somehow gives a wrong int
        // See also [parseRecurrenceRuleString] where +1 is added.
        if (recurrenceRule.bymonth != null) {
            val byMonth = recurrenceRule.bymonth!!
            val newMonth = mutableListOf<Int>()
            byMonth.forEach {
                newMonth.add(it - 1)
            }
            rr.setByPart(Rrule.Part.BYMONTH, newMonth)
        }

        if (recurrenceRule.bysetpos != null) {
            rr.setByPart(Rrule.Part.BYSETPOS, recurrenceRule.bysetpos!!)
        }
        return rr.toString()
    }

    private fun parseDateTime(string: String): DateTime {
        val year = Regex("""(?<year>\d{4})""").pattern
        val month = Regex("""(?<month>\d{2})""").pattern
        val day = Regex("""(?<day>\d{2})""").pattern
        val hour = Regex("""(?<hour>\d{2})""").pattern
        val minute = Regex("""(?<minute>\d{2})""").pattern
        val second = Regex("""(?<second>\d{2})""").pattern

        val regEx = Regex("^$year-$month-${day}T$hour:$minute:${second}Z?\$")

        val match = regEx.matchEntire(string)

        return DateTime(
            UTC,
            match?.groups?.get(1)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(2)?.value?.toIntOrNull()?.minus(1) ?: 0,
            match?.groups?.get(3)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(4)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(5)?.value?.toIntOrNull() ?: 0,
            match?.groups?.get(6)?.value?.toIntOrNull() ?: 0
        )
    }

    private fun parseAvailability(availability: Int): Availability? = when (availability) {
        Events.AVAILABILITY_BUSY -> Availability.BUSY
        Events.AVAILABILITY_FREE -> Availability.FREE
        Events.AVAILABILITY_TENTATIVE -> Availability.TENTATIVE
        else -> null
    }

    private fun parseEventStatus(status: Int): EventStatus? = when(status) {
        Events.STATUS_CONFIRMED -> EventStatus.CONFIRMED
        Events.STATUS_CANCELED -> EventStatus.CANCELED
        Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
        else -> null
    }
}
