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
                var repairedCalendarForReset: Pair<Long, String>? = null
                try {
                    while (cursor?.moveToNext() == true) {
                        val calendar = parseCalendarRow(cursor) ?: continue
                        val syncEvents = getColumnInt(
                            cursor,
                            CalendarContract.Calendars.SYNC_EVENTS,
                            Cst.CALENDAR_PROJECTION_SYNC_EVENTS_INDEX,
                            default = 1
                        )
                        val calIdLong = calendar.id.toLongOrNull()
                        if (calIdLong != null && repairLocalCalendarIfNeeded(
                                contentResolver = contentResolver,
                                calendarId = calIdLong,
                                accountName = calendar.accountName,
                                accountType = calendar.accountType,
                                isReadOnly = calendar.isReadOnly,
                                syncEvents = syncEvents,
                                resetInstancesCache = false
                            )
                        ) {
                            repairedCalendarForReset = calIdLong to calendar.accountName
                        }
                        calendars.add(calendar)
                    }

                    if (repairedCalendarForReset != null && contentResolver != null) {
                        triggerInstancesCacheReset(
                            contentResolver,
                            repairedCalendarForReset.first,
                            repairedCalendarForReset.second
                        )
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
        isInternalCall: Boolean = false,
        repairIfNeeded: Boolean = true
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
                    if (calendar != null && repairIfNeeded) {
                        val syncEvents = getColumnInt(
                            cursor,
                            CalendarContract.Calendars.SYNC_EVENTS,
                            Cst.CALENDAR_PROJECTION_SYNC_EVENTS_INDEX,
                            default = 1
                        )
                        repairLocalCalendarIfNeeded(
                            contentResolver = contentResolver,
                            calendarId = calendarIdNumber,
                            accountName = calendar.accountName,
                            accountType = calendar.accountType,
                            isReadOnly = calendar.isReadOnly,
                            syncEvents = syncEvents,
                            resetInstancesCache = true
                        )
                    }
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

    private fun buildLocalSyncAdapterUri(uri: Uri, accountName: String, accountType: String): Uri {
        return uri.buildUpon()
            .appendQueryParameter(CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType)
            .build()
    }

    private fun buildUriForCalendar(uri: Uri, calendar: Calendar): Uri {
        return if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
            buildLocalSyncAdapterUri(uri, calendar.accountName, calendar.accountType)
        } else {
            uri
        }
    }

    private fun formatDuration(durationMillis: Long, allDay: Boolean): String {
        if (allDay) {
            val days = maxOf(1L, (durationMillis + DateUtils.DAY_IN_MILLIS / 2) / DateUtils.DAY_IN_MILLIS)
            return "P${days}D"
        }
        if (durationMillis <= 0L) {
            return "P0S"
        }
        var duration = ""
        durationMillis.toDuration(DurationUnit.MILLISECONDS).toComponents { days, hours, minutes, seconds, _ ->
            if (days > 0 || hours > 0 || minutes > 0 || seconds > 0) {
                val sb = StringBuilder("P")
                if (days > 0) sb.append("${days}D")
                if (hours > 0 || minutes > 0 || seconds > 0) sb.append("T")
                if (hours > 0) sb.append("${hours}H")
                if (minutes > 0) sb.append("${minutes}M")
                if (seconds > 0) sb.append("${seconds}S")
                duration = sb.toString()
            }
        }
        return if (duration.isNotEmpty()) duration else "P0S"
    }

    private fun parseDurationToMillis(durationStr: String?, allDay: Boolean): Long {
        if (durationStr.isNullOrBlank()) {
            return if (allDay) DateUtils.DAY_IN_MILLIS else 0L
        }
        val match = Regex(
            """^[+-]?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$"""
        ).matchEntire(durationStr.trim()) ?: return if (allDay) DateUtils.DAY_IN_MILLIS else 0L
        val weeks = match.groupValues[1].toLongOrNull() ?: 0L
        val days = match.groupValues[2].toLongOrNull() ?: 0L
        val hours = match.groupValues[3].toLongOrNull() ?: 0L
        val minutes = match.groupValues[4].toLongOrNull() ?: 0L
        val seconds = match.groupValues[5].toLongOrNull() ?: 0L
        val millis = ((weeks * 7L + days) * 86_400L + hours * 3_600L + minutes * 60L + seconds) * 1_000L
        return if (durationStr.trim().startsWith("-")) -millis else millis
    }

    private fun queryInstancesForEvent(
        contentResolver: ContentResolver?,
        projection: Array<String>,
        begin: Long,
        end: Long,
        eventId: Long
    ): Cursor? {
        if (contentResolver == null || end < begin) return null
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        return contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.EVENT_ID} = ? AND ${Events.DELETED} != 1",
            arrayOf(eventId.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )
    }

    /**
     * Repairs pre-existing local (ACCOUNT_TYPE_LOCAL) calendars that were created with SYNC_EVENTS = 0.
     * On Android CalendarProvider, SYNC_EVENTS = 0 calendars are excluded from CalendarInstancesHelper.getEntries(),
     * causing all events to vanish whenever CalendarMetaData is cleared (e.g. deleting any recurring event/exception
     * anywhere on the device, timezone changes, or querying outside the cached window).
     */
    private fun repairLocalCalendarIfNeeded(
        contentResolver: ContentResolver?,
        calendarId: Long,
        accountName: String,
        accountType: String,
        isReadOnly: Boolean,
        syncEvents: Int,
        resetInstancesCache: Boolean
    ): Boolean {
        if (contentResolver == null) return false
        if (accountType != CalendarContract.ACCOUNT_TYPE_LOCAL || isReadOnly || syncEvents != 0) {
            return false
        }
        return try {
            val calUri = buildLocalSyncAdapterUri(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                accountName,
                accountType
            )
            val calValues = ContentValues().apply {
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            }
            contentResolver.update(calUri, calValues, null, null)
            migrateLegacyLocalRecurringEvents(contentResolver, calendarId, accountName)
            if (resetInstancesCache) {
                triggerInstancesCacheReset(contentResolver, calendarId, accountName)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Migrates pre-existing local recurring events that still have DTEND instead of DURATION
     * (which causes AOSP calculateLastDate to set LAST_DATE = first occurrence end, hiding future instances,
     * and causes exception creation to crash) or are missing a synthetic _SYNC_ID needed by CalendarInstancesHelper.
     */
    private fun migrateLegacyLocalRecurringEvents(
        contentResolver: ContentResolver,
        calendarId: Long,
        accountName: String
    ) {
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} != 1 " +
            "AND ((${Events.RRULE} IS NOT NULL AND ${Events.RRULE} != '') OR (${Events.RDATE} IS NOT NULL AND ${Events.RDATE} != '')) " +
            "AND (${Events.DURATION} IS NULL OR ${Events.DURATION} = '' OR ${Events.DTEND} IS NOT NULL OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID} = '')"
        val projection = arrayOf(
            Events._ID,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.EVENT_TIMEZONE,
            Events.RRULE,
            Events.RDATE,
            Events.ALL_DAY,
            Events._SYNC_ID
        )
        contentResolver.query(
            Events.CONTENT_URI,
            projection,
            selection,
            arrayOf(calendarId.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val dtStart = cursor.getLong(1)
                val dtEnd = if (cursor.isNull(2)) null else cursor.getLong(2)
                val existingDuration = cursor.getString(3)
                val existingTz = cursor.getString(4)
                val rrule = cursor.getString(5)
                val allDay = cursor.getInt(7) != 0
                val existingSyncId = cursor.getString(8)

                val duration = if (!existingDuration.isNullOrEmpty()) {
                    existingDuration
                } else {
                    val diff = if (dtEnd != null && dtEnd >= dtStart) dtEnd - dtStart else 0L
                    formatDuration(diff, allDay)
                }
                val newSyncId = if (existingSyncId.isNullOrEmpty()) UUID.randomUUID().toString() else existingSyncId
                val effectiveTz = if (allDay) "UTC" else (existingTz?.takeIf { it.isNotEmpty() } ?: TimeZone.getDefault().id)

                val updateValues = ContentValues().apply {
                    put(Events.DTSTART, dtStart)
                    putNull(Events.DTEND)
                    put(Events.DURATION, duration)
                    put(Events.EVENT_TIMEZONE, effectiveTz)
                    put(Events.ALL_DAY, if (allDay) 1 else 0)
                    if (!rrule.isNullOrEmpty()) put(Events.RRULE, rrule)
                    put(Events._SYNC_ID, newSyncId)
                }
                val eventUri = buildLocalSyncAdapterUri(
                    ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                    accountName,
                    CalendarContract.ACCOUNT_TYPE_LOCAL
                )
                contentResolver.update(eventUri, updateValues, null, null)

                // Backfill ORIGINAL_SYNC_ID on any existing exceptions linked only via ORIGINAL_ID
                if (existingSyncId.isNullOrEmpty()) {
                    val exBackfill = ContentValues().apply {
                        put(Events.ORIGINAL_SYNC_ID, newSyncId)
                    }
                    val exUri = buildLocalSyncAdapterUri(
                        Events.CONTENT_URI,
                        accountName,
                        CalendarContract.ACCOUNT_TYPE_LOCAL
                    )
                    contentResolver.update(
                        exUri,
                        exBackfill,
                        "${Events.ORIGINAL_ID} = ? AND (${Events.ORIGINAL_SYNC_ID} IS NULL OR ${Events.ORIGINAL_SYNC_ID} = '')",
                        arrayOf(eventId.toString())
                    )
                }
            }
        }
    }

    /**
     * Forces AOSP CalendarProvider to clear CalendarMetaData (maxInstance = 0) by inserting and immediately
     * deleting a 1-second temporary recurring event. Updating Calendars.SYNC_EVENTS = 1 alone does NOT
     * trigger re-expansion of events that already vanished inside the cached [minInstance, maxInstance] range.
     */
    private fun triggerInstancesCacheReset(
        contentResolver: ContentResolver,
        calendarId: Long,
        accountName: String
    ) {
        var insertedUri: Uri? = null
        try {
            val now = System.currentTimeMillis()
            val tempValues = ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                put(Events.TITLE, "")
                put(Events.DTSTART, now)
                put(Events.DURATION, "PT1S")
                put(Events.RRULE, "FREQ=DAILY;COUNT=1")
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.ALL_DAY, 0)
            }
            val insertUri = buildLocalSyncAdapterUri(
                Events.CONTENT_URI,
                accountName,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            insertedUri = contentResolver.insert(insertUri, tempValues)
        } catch (_: Exception) {
        } finally {
            if (insertedUri != null) {
                try {
                    val tempId = ContentUris.parseId(insertedUri)
                    val deleteUri = buildLocalSyncAdapterUri(
                        ContentUris.withAppendedId(Events.CONTENT_URI, tempId),
                        accountName,
                        CalendarContract.ACCOUNT_TYPE_LOCAL
                    )
                    contentResolver.delete(deleteUri, null, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun countExceptionsBeforeCutoff(
        contentResolver: ContentResolver?,
        masterId: Long,
        masterSyncId: String?,
        cutoffStart: Long
    ): Pair<Int, Int> {
        // Returns (totalExceptionsBeforeCutoff, nonCanceledExceptionsBeforeCutoff)
        if (contentResolver == null) return 0 to 0
        val baseWhere = if (!masterSyncId.isNullOrEmpty()) {
            "(${Events.ORIGINAL_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)"
        } else {
            "${Events.ORIGINAL_ID} = ?"
        }
        val where = "$baseWhere AND ${Events.DELETED} != 1 AND ${Events.ORIGINAL_INSTANCE_TIME} < ?"
        val args = if (!masterSyncId.isNullOrEmpty()) {
            arrayOf(masterId.toString(), masterSyncId, cutoffStart.toString())
        } else {
            arrayOf(masterId.toString(), cutoffStart.toString())
        }
        var total = 0
        var nonCanceled = 0
        contentResolver.query(
            Events.CONTENT_URI,
            arrayOf(Events.STATUS),
            where,
            args,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                total++
                val status = if (cursor.isNull(0)) Events.STATUS_TENTATIVE else cursor.getInt(0)
                if (status != Events.STATUS_CANCELED) {
                    nonCanceled++
                }
            }
        }
        return total to nonCanceled
    }

    private fun deleteExceptionsForMaster(
        contentResolver: ContentResolver?,
        masterId: Long,
        masterSyncId: String?,
        calendar: Calendar,
        fromInstanceTimeInclusive: Long? = null
    ) {
        if (contentResolver == null) return
        val baseWhere = if (!masterSyncId.isNullOrEmpty()) {
            "(${Events.ORIGINAL_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)"
        } else {
            "${Events.ORIGINAL_ID} = ?"
        }
        val where = if (fromInstanceTimeInclusive != null) {
            "$baseWhere AND ${Events.ORIGINAL_INSTANCE_TIME} >= ?"
        } else {
            baseWhere
        }
        val argsList = mutableListOf(masterId.toString())
        if (!masterSyncId.isNullOrEmpty()) {
            argsList.add(masterSyncId)
        }
        if (fromInstanceTimeInclusive != null) {
            argsList.add(fromInstanceTimeInclusive.toString())
        }
        contentResolver.delete(
            buildUriForCalendar(Events.CONTENT_URI, calendar),
            where,
            argsList.toTypedArray()
        )
    }

    private fun ensureMasterEventReadyForRecurrenceOp(
        contentResolver: ContentResolver?,
        eventId: Long,
        calendar: Calendar,
        dtStart: Long,
        dtEnd: Long?,
        existingDuration: String?,
        existingTimezone: String?,
        existingRrule: String?,
        existingSyncId: String?,
        allDay: Boolean
    ): Pair<String, String?> {
        val needsDurationFix = existingDuration.isNullOrEmpty() || dtEnd != null
        val needsSyncId = existingSyncId.isNullOrEmpty() && calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
        val duration = if (!existingDuration.isNullOrEmpty()) {
            existingDuration
        } else {
            val diff = if (dtEnd != null && dtEnd >= dtStart) dtEnd - dtStart else 0L
            formatDuration(diff, allDay)
        }
        val syncId = if (needsSyncId) UUID.randomUUID().toString() else existingSyncId

        if ((needsDurationFix || needsSyncId) && contentResolver != null) {
            val masterEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
            val updateValues = ContentValues().apply {
                if (needsDurationFix) {
                    put(Events.DTSTART, dtStart)
                    putNull(Events.DTEND)
                    put(Events.DURATION, duration)
                    put(Events.ALL_DAY, if (allDay) 1 else 0)
                    put(
                        Events.EVENT_TIMEZONE,
                        if (allDay) "UTC" else (existingTimezone?.takeIf { it.isNotEmpty() } ?: TimeZone.getDefault().id)
                    )
                    if (!existingRrule.isNullOrEmpty()) put(Events.RRULE, existingRrule)
                }
                if (needsSyncId && !syncId.isNullOrEmpty()) {
                    put(Events._SYNC_ID, syncId)
                }
            }
            contentResolver.update(buildUriForCalendar(masterEventUri, calendar), updateValues, null, null)

            if (needsSyncId && !syncId.isNullOrEmpty()) {
                val exBackfill = ContentValues().apply {
                    put(Events.ORIGINAL_SYNC_ID, syncId)
                }
                contentResolver.update(
                    buildUriForCalendar(Events.CONTENT_URI, calendar),
                    exBackfill,
                    "${Events.ORIGINAL_ID} = ? AND (${Events.ORIGINAL_SYNC_ID} IS NULL OR ${Events.ORIGINAL_SYNC_ID} = '')",
                    arrayOf(eventId.toString())
                )
            }
        }
        return duration to syncId
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

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, isInternalCall = true, repairIfNeeded = false)
            if (calendar != null) {
                val calenderUriWithId = buildUriForCalendar(
                    ContentUris.withAppendedId(
                        CalendarContract.Calendars.CONTENT_URI,
                        calendarIdNumber
                    ),
                    calendar
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
            val calendar = retrieveCalendar(calendarId, pendingChannelResult, isInternalCall = true)
            if (calendar == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "Couldn't retrieve the Calendar with ID $calendarId",
                    pendingChannelResult
                )
                return
            }

            val validEventIds = eventIds.mapNotNull { it.toLongOrNull() }
            if (eventIds.isNotEmpty() && validEventIds.isEmpty()) {
                finishWithSuccess(_gson?.toJson(emptyList<Event>()), pendingChannelResult)
                return
            }

            val contentResolver: ContentResolver? = _context?.contentResolver
            val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                uiThreadHandler.post {
                    finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                }
            }

            GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                val events: MutableList<Event> = mutableListOf()
                val queryEventsTableDirectly = startDate == null && endDate == null && validEventIds.isNotEmpty()

                if (queryEventsTableDirectly) {
                    // Query Events table directly instead of expanding Instances across 1970..2100,
                    // which would poison CalendarMetaData, hit RecurrenceProcessor's 2000-iteration cap,
                    // and return 2001 duplicate rows for a recurring event.
                    val selection = "(${Events.CALENDAR_ID} = ?) AND (${Events.DELETED} != 1) AND (${Events._ID} IN (${validEventIds.joinToString(",")}))"
                    contentResolver?.query(
                        Events.CONTENT_URI,
                        Cst.EVENT_TABLE_PROJECTION,
                        selection,
                        arrayOf(calendarId),
                        "${Events.DTSTART} DESC"
                    )?.use { eventsCursor ->
                        while (eventsCursor.moveToNext()) {
                            val event = parseEventFromEventsTable(calendarId, eventsCursor) ?: continue
                            events.add(event)
                        }
                    }
                } else {
                    val effectiveStart = startDate ?: (endDate!! - DateUtils.YEAR_IN_MILLIS)
                    val effectiveEnd = endDate ?: (startDate!! + DateUtils.YEAR_IN_MILLIS)
                    val eventsUriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                    ContentUris.appendId(eventsUriBuilder, effectiveStart)
                    ContentUris.appendId(eventsUriBuilder, effectiveEnd)

                    var eventsSelectionQuery = "(${Events.CALENDAR_ID} = ?) AND (${Events.DELETED} != 1)"
                    if (validEventIds.isNotEmpty()) {
                        eventsSelectionQuery += " AND (${CalendarContract.Instances.EVENT_ID} IN (${validEventIds.joinToString(",")}))"
                    }
                    contentResolver?.query(
                        eventsUriBuilder.build(),
                        Cst.EVENT_PROJECTION,
                        eventsSelectionQuery,
                        arrayOf(calendarId),
                        "${Events.DTSTART} DESC"
                    )?.use { eventsCursor ->
                        while (eventsCursor.moveToNext()) {
                            val event = parseEvent(calendarId, eventsCursor) ?: continue
                            events.add(event)
                        }
                    }
                }

                val attendeesCache = mutableMapOf<String, MutableList<Attendee>>()
                val remindersCache = mutableMapOf<String, MutableList<Reminder>>()
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

                val json = _gson?.toJson(events)
                uiThreadHandler.post {
                    finishWithSuccess(json, pendingChannelResult)
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

            val calendar = retrieveCalendar(calendarId, pendingChannelResult, isInternalCall = true)
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

            if (eventId != null && instanceStartDate != null && instanceEndDate != null && updateFollowingInstances != null) {
                val exceptionHandler = CoroutineExceptionHandler { _, exception ->
                    uiThreadHandler.post {
                        finishWithError(EC.GENERIC_ERROR, exception.message, pendingChannelResult)
                    }
                }

                if (!updateFollowingInstances) {
                    // --- Case 2: Edit Only This Instance ---
                    GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        val targetEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
                        var masterStart: Long? = null
                        var masterEnd: Long? = null
                        var masterDuration: String? = null
                        var masterTimezone: String? = null
                        var masterRrule: String? = null
                        var masterSyncId: String? = null
                        var masterAllDay = false
                        var existingOriginalId: Long? = null
                        var existingOriginalSyncId: String? = null
                        var existingCalendarId: String? = null
                        var foundEvent = false

                        contentResolver?.query(
                            targetEventUri,
                            arrayOf(
                                Events.DTSTART,
                                Events.DTEND,
                                Events.DURATION,
                                Events.EVENT_TIMEZONE,
                                Events.RRULE,
                                Events._SYNC_ID,
                                Events.ALL_DAY,
                                Events.ORIGINAL_ID,
                                Events.ORIGINAL_SYNC_ID,
                                Events.CALENDAR_ID
                            ),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                foundEvent = true
                                masterStart = cursor.getLong(0)
                                masterEnd = if (cursor.isNull(1)) null else cursor.getLong(1)
                                masterDuration = cursor.getString(2)
                                masterTimezone = cursor.getString(3)
                                masterRrule = cursor.getString(4)
                                masterSyncId = cursor.getString(5)
                                masterAllDay = cursor.getInt(6) != 0
                                existingOriginalId = if (cursor.isNull(7)) null else cursor.getLong(7)
                                existingOriginalSyncId = cursor.getString(8)
                                existingCalendarId = cursor.getString(9)
                            }
                        }

                        if (!foundEvent) {
                            uiThreadHandler.post {
                                finishWithError(EC.NOT_FOUND, "Event with ID $eventId could not be found", pendingChannelResult)
                            }
                            return@launch
                        }

                        val sourceCalendar = if (!existingCalendarId.isNullOrEmpty() && existingCalendarId != calendarId) {
                            retrieveCalendar(existingCalendarId!!, pendingChannelResult, isInternalCall = true) ?: calendar
                        } else {
                            calendar
                        }

                        // If this event is ALREADY a detached exception (or non-recurring), update it directly
                        // instead of calling CONTENT_EXCEPTION_URI (which AOSP rejects for exceptions/non-recurring events).
                        if (existingOriginalId != null || !existingOriginalSyncId.isNullOrEmpty() || masterRrule.isNullOrEmpty()) {
                            val directUpdateValues = ContentValues().apply {
                                putAll(values)
                                remove(Events.CALENDAR_ID)
                                putNull(Events.RRULE)
                                putNull(Events.RDATE)
                                putNull(Events.EXRULE)
                                putNull(Events.EXDATE)
                                putNull(Events.DURATION)
                                put(Events.DTSTART, event.eventStartDate!!)
                                put(Events.DTEND, event.eventEndDate!!)
                            }
                            contentResolver?.update(
                                buildUriForCalendar(targetEventUri, sourceCalendar),
                                directUpdateValues,
                                null,
                                null
                            )
                            if (contentResolver != null) {
                                syncAttendeesAndReminders(contentResolver, sourceCalendar, eventId, event)
                            }
                            uiThreadHandler.post {
                                finishWithSuccess(eventId.toString(), pendingChannelResult)
                            }
                            return@launch
                        }

                        val (ensuredDuration, ensuredSyncId) = ensureMasterEventReadyForRecurrenceOp(
                            contentResolver = contentResolver,
                            eventId = eventId,
                            calendar = sourceCalendar,
                            dtStart = masterStart ?: event.eventStartDate!!,
                            dtEnd = masterEnd,
                            existingDuration = masterDuration,
                            existingTimezone = masterTimezone,
                            existingRrule = masterRrule,
                            existingSyncId = masterSyncId,
                            allDay = masterAllDay
                        )
                        masterDuration = ensuredDuration
                        masterSyncId = ensuredSyncId

                        val exceptionValues = ContentValues().apply {
                            putAll(values)
                            remove(Events.CALENDAR_ID)
                            remove(Events.RRULE)
                            remove(Events.RDATE)
                            remove(Events.EXRULE)
                            remove(Events.EXDATE)
                            // DTEND is forbidden in ALLOWED_IN_EXCEPTION (throws "Exceptions can't overwrite dtend")
                            remove(Events.DTEND)
                            // EVENT_COLOR_KEY triggers an AOSP cursor.moveToFirst() bug in handleInsertException; apply after insert
                            remove(Events.EVENT_COLOR_KEY)
                        }

                        exceptionValues.put(Events.ORIGINAL_INSTANCE_TIME, instanceStartDate)
                        if (!masterSyncId.isNullOrEmpty()) {
                            exceptionValues.put(Events.ORIGINAL_SYNC_ID, masterSyncId)
                        }
                        val diff = (event.eventEndDate ?: event.eventStartDate!!) - event.eventStartDate!!
                        exceptionValues.put(Events.DURATION, formatDuration(diff, event.eventAllDay))

                        val exceptionUriWithId = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventId)
                        val insertedUri = contentResolver?.insert(
                            buildUriForCalendar(exceptionUriWithId, sourceCalendar),
                            exceptionValues
                        )
                        val exceptionEventId = insertedUri?.lastPathSegment?.toLongOrNull()

                        if (exceptionEventId != null) {
                            if (event.eventColorKey != null) {
                                val colorValues = ContentValues().apply {
                                    put(Events.EVENT_COLOR_KEY, event.eventColorKey)
                                }
                                val exceptionEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, exceptionEventId)
                                contentResolver?.update(
                                    buildUriForCalendar(exceptionEventUri, sourceCalendar),
                                    colorValues,
                                    null,
                                    null
                                )
                            }

                            // CalendarProvider2.handleInsertException already clones attendees and reminders via
                            // copyEventRelatedTables(), so replace/sync instead of blindly inserting duplicates.
                            if (contentResolver != null) {
                                syncAttendeesAndReminders(contentResolver, sourceCalendar, exceptionEventId, event)
                            }

                            // When masterSyncId is null (e.g. unsynced remote event), AOSP CalendarInstancesHelper:810
                            // fails to re-expand master instances after exception insert; touch master to restore them.
                            if (masterSyncId.isNullOrEmpty()) {
                                val touchValues = ContentValues().apply {
                                    if (masterStart != null) put(Events.DTSTART, masterStart)
                                    if (masterDuration != null) put(Events.DURATION, masterDuration)
                                    if (masterTimezone != null) put(Events.EVENT_TIMEZONE, masterTimezone)
                                    if (masterRrule != null) put(Events.RRULE, masterRrule)
                                    put(Events.ALL_DAY, if (masterAllDay) 1 else 0)
                                }
                                if (touchValues.size() > 0) {
                                    contentResolver?.update(
                                        buildUriForCalendar(targetEventUri, sourceCalendar),
                                        touchValues,
                                        null,
                                        null
                                    )
                                }
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
                        var targetMasterId: Long = eventId
                        var splitStart: Long = instanceStartDate

                        // If triggered on a detached exception row, resolve the underlying master series
                        contentResolver?.query(
                            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                            arrayOf(Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME),
                            null, null, null
                        )?.use { c ->
                            if (c.moveToFirst() && !c.isNull(0)) {
                                targetMasterId = c.getLong(0)
                                if (!c.isNull(1)) {
                                    splitStart = c.getLong(1)
                                }
                            }
                        }

                        val originalEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, targetMasterId)
                        var originalStart: Long? = null
                        var originalEnd: Long? = null
                        var originalDuration: String? = null
                        var originalTimezone: String? = null
                        var originalRrule: String? = null
                        var originalSyncId: String? = null
                        var originalAllDay = false
                        var originalCalendarId: String? = null

                        contentResolver?.query(
                            originalEventUri,
                            arrayOf(
                                Events.DTSTART,
                                Events.DTEND,
                                Events.DURATION,
                                Events.EVENT_TIMEZONE,
                                Events.RRULE,
                                Events._SYNC_ID,
                                Events.ALL_DAY,
                                Events.CALENDAR_ID
                            ),
                            null, null, null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                originalStart = cursor.getLong(0)
                                originalEnd = if (cursor.isNull(1)) null else cursor.getLong(1)
                                originalDuration = cursor.getString(2)
                                originalTimezone = cursor.getString(3)
                                originalRrule = cursor.getString(4)
                                originalSyncId = cursor.getString(5)
                                originalAllDay = cursor.getInt(6) != 0
                                originalCalendarId = cursor.getString(7)
                            }
                        }

                        if (originalRrule.isNullOrEmpty()) {
                            uiThreadHandler.post {
                                finishWithError(EC.GENERIC_ERROR, "Cannot split a non-recurring event", pendingChannelResult)
                            }
                            return@launch
                        }

                        val sourceCalendar = if (!originalCalendarId.isNullOrEmpty() && originalCalendarId != calendarId) {
                            retrieveCalendar(originalCalendarId!!, pendingChannelResult, isInternalCall = true) ?: calendar
                        } else {
                            calendar
                        }

                        // Query instances without VISIBLE = 1 filter before modifying the original RRULE
                        val newRule = Rrule(originalRrule)
                        var masterInstancesBeforeSplit = 0
                        queryInstancesForEvent(
                            contentResolver = contentResolver,
                            projection = Cst.EVENT_INSTANCE_DELETION,
                            begin = originalStart ?: 0L,
                            end = splitStart - 1L,
                            eventId = targetMasterId
                        )?.use { instancesCursor ->
                            while (instancesCursor.moveToNext()) {
                                val begin = instancesCursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                                if (begin < splitStart) {
                                    masterInstancesBeforeSplit++
                                }
                            }
                        }
                        val (totalExBeforeSplit, nonCanceledExBeforeSplit) = countExceptionsBeforeCutoff(
                            contentResolver, targetMasterId, originalSyncId, splitStart
                        )
                        // RFC 5545 COUNT counts all RRULE occurrences including overridden/canceled ones
                        val rruleOccurrencesBeforeSplit = masterInstancesBeforeSplit + totalExBeforeSplit
                        val visibleOccurrencesBeforeSplit = masterInstancesBeforeSplit + nonCanceledExBeforeSplit

                        if (visibleOccurrencesBeforeSplit == 0) {
                            // Delete original series AND all its exceptions using sourceCalendar's account URI
                            contentResolver?.delete(buildUriForCalendar(originalEventUri, sourceCalendar), null, null)
                            deleteExceptionsForMaster(contentResolver, targetMasterId, originalSyncId, sourceCalendar)
                        } else {
                            // Clean up any exceptions at or after splitStart so they are not left orphaned
                            deleteExceptionsForMaster(
                                contentResolver,
                                targetMasterId,
                                originalSyncId,
                                sourceCalendar,
                                fromInstanceTimeInclusive = splitStart
                            )

                            val (ensuredDuration, _) = ensureMasterEventReadyForRecurrenceOp(
                                contentResolver = contentResolver,
                                eventId = targetMasterId,
                                calendar = sourceCalendar,
                                dtStart = originalStart ?: 0L,
                                dtEnd = originalEnd,
                                existingDuration = originalDuration,
                                existingTimezone = originalTimezone,
                                existingRrule = originalRrule,
                                existingSyncId = originalSyncId,
                                allDay = originalAllDay
                            )

                            // Truncate the original event's RRULE
                            if (newRule.count != null && newRule.count > 0) {
                                newRule.count = rruleOccurrencesBeforeSplit
                            } else {
                                newRule.until = DateTime(splitStart - 1000L)
                            }

                            val truncateValues = ContentValues().apply {
                                put(Events.RRULE, newRule.toString())
                                putNull(Events.DTEND)
                                put(Events.DURATION, ensuredDuration)
                                put(Events.ALL_DAY, if (originalAllDay) 1 else 0)
                                if (originalStart != null) {
                                    put(Events.DTSTART, originalStart)
                                }
                                put(
                                    Events.EVENT_TIMEZONE,
                                    if (originalAllDay) "UTC" else (originalTimezone ?: TimeZone.getDefault().id)
                                )
                            }
                            contentResolver?.update(
                                buildUriForCalendar(originalEventUri, sourceCalendar),
                                truncateValues,
                                null,
                                null
                            )
                        }

                        // Adjust the new split event's COUNT using the pre-computed rruleOccurrencesBeforeSplit
                        if (event.recurrenceRule != null) {
                            val origRfcRule = Rrule(originalRrule)
                            if (origRfcRule.count != null && origRfcRule.count > 0) {
                                val remainingCount = origRfcRule.count - rruleOccurrencesBeforeSplit
                                if (remainingCount > 0) {
                                    event.recurrenceRule!!.count = remainingCount
                                }
                            }
                        }

                        val newValues = buildEventContentValues(event, calendarId)
                        if (calendar.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                            newValues.put(Events._SYNC_ID, UUID.randomUUID().toString())
                        }
                        val targetUri = buildUriForCalendar(Events.CONTENT_URI, calendar)
                        val insertedUri = contentResolver?.insert(targetUri, newValues)
                        val newEventId = insertedUri?.lastPathSegment?.toLongOrNull()

                        if (newEventId != null) {
                            insertAttendees(event.attendees, newEventId, contentResolver)
                            if (contentResolver != null) {
                                insertReminders(event.reminders, newEventId, contentResolver)
                            }

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
                // --- Case 1: Create New Event or Edit All Instances ---
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
                    val targetUri = buildUriForCalendar(Events.CONTENT_URI, calendar)
                    val uri = contentResolver?.insert(targetUri, values)
                    eventIdNumber = java.lang.Long.parseLong(uri?.lastPathSegment!!)
                    job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        insertAttendees(event.attendees, eventIdNumber, contentResolver)
                        if (contentResolver != null) {
                            insertReminders(event.reminders, eventIdNumber, contentResolver)
                        }
                    }
                } else {
                    job = GlobalScope.launch(Dispatchers.IO + exceptionHandler) {
                        val masterUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                        var existingDtStart: Long? = null
                        var existingRrule: String? = null
                        var existingCalendarId: String? = null
                        var existingSyncId: String? = null
                        contentResolver?.query(
                            masterUri,
                            arrayOf(Events.DTSTART, Events.RRULE, Events.CALENDAR_ID, Events._SYNC_ID),
                            null, null, null
                        )?.use { masterCursor ->
                            if (masterCursor.moveToFirst()) {
                                existingDtStart = masterCursor.getLong(0)
                                existingRrule = masterCursor.getString(1)
                                existingCalendarId = masterCursor.getString(2)
                                existingSyncId = masterCursor.getString(3)
                            }
                        }

                        val sourceCalendar = if (!existingCalendarId.isNullOrEmpty() && existingCalendarId != calendarId) {
                            retrieveCalendar(existingCalendarId!!, pendingChannelResult, isInternalCall = true) ?: calendar
                        } else {
                            calendar
                        }

                        // For already-recurring events being updated as recurring, preserve the series'
                        // original start DATE so editing an instance (e.g., Feb 4) doesn't drop earlier dates.
                        if (!existingRrule.isNullOrEmpty() && event.recurrenceRule != null && existingDtStart != null) {
                            if (event.eventAllDay) {
                                values.put(Events.DTSTART, existingDtStart)
                            } else {
                                val tz = getTimeZone(event.eventStartTimeZone)
                                val originalCal = java.util.Calendar.getInstance(tz).apply {
                                    timeInMillis = existingDtStart!!
                                }
                                val userCal = java.util.Calendar.getInstance(tz).apply {
                                    timeInMillis = event.eventStartDate!!
                                }
                                originalCal.set(java.util.Calendar.HOUR_OF_DAY, userCal.get(java.util.Calendar.HOUR_OF_DAY))
                                originalCal.set(java.util.Calendar.MINUTE, userCal.get(java.util.Calendar.MINUTE))
                                originalCal.set(java.util.Calendar.SECOND, userCal.get(java.util.Calendar.SECOND))
                                originalCal.set(java.util.Calendar.MILLISECOND, userCal.get(java.util.Calendar.MILLISECOND))
                                values.put(Events.DTSTART, originalCal.timeInMillis)
                            }
                        }

                        // If removing recurrence from a previously recurring event, delete orphaned exceptions
                        if (!existingRrule.isNullOrEmpty() && event.recurrenceRule == null) {
                            deleteExceptionsForMaster(contentResolver, eventIdNumber, existingSyncId, sourceCalendar)
                        }

                        // Update event using sourceCalendar when source is non-local (so DIRTY=1 is set for sync),
                        // or destination calendar when local.
                        val updateCal = if (sourceCalendar.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL) {
                            sourceCalendar
                        } else {
                            calendar
                        }
                        val targetUri = buildUriForCalendar(masterUri, updateCal)
                        contentResolver?.update(
                            targetUri,
                            values,
                            null,
                            null
                        )

                        // If moved across calendars, move any associated exception rows too so they stay linked
                        if (!existingCalendarId.isNullOrEmpty() && existingCalendarId != calendarId && !existingRrule.isNullOrEmpty()) {
                            val moveExValues = ContentValues().apply {
                                put(Events.CALENDAR_ID, calendarId)
                            }
                            val exWhere = if (!existingSyncId.isNullOrEmpty()) {
                                "(${Events.ORIGINAL_ID} = ? OR ${Events.ORIGINAL_SYNC_ID} = ?)"
                            } else {
                                "${Events.ORIGINAL_ID} = ?"
                            }
                            val exArgs = if (!existingSyncId.isNullOrEmpty()) {
                                arrayOf(eventIdNumber.toString(), existingSyncId!!)
                            } else {
                                arrayOf(eventIdNumber.toString())
                            }
                            contentResolver?.update(
                                buildUriForCalendar(Events.CONTENT_URI, updateCal),
                                moveExValues,
                                exWhere,
                                exArgs
                            )
                        }

                        if (contentResolver != null) {
                            syncAttendeesAndReminders(contentResolver, calendar, eventIdNumber, event)
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

    private fun syncAttendeesAndReminders(
        contentResolver: ContentResolver,
        calendar: Calendar,
        eventIdNumber: Long,
        event: Event
    ) {
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
        insertReminders(event.reminders, eventIdNumber, contentResolver)

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
        val effectiveStartTz = if (event.eventAllDay) "UTC" else getTimeZone(event.eventStartTimeZone).id
        values.put(Events.EVENT_TIMEZONE, effectiveStartTz)
        values.put(Events.TITLE, event.eventTitle)
        values.put(Events.DESCRIPTION, event.eventDescription)
        values.put(Events.EVENT_LOCATION, event.eventLocation)
        values.put(Events.CUSTOM_APP_URI, event.eventURL)
        values.put(Events.CALENDAR_ID, calendarId)
        val availability = getAvailability(event.availability)
        if (availability != null) {
            values.put(Events.AVAILABILITY, availability)
        }
        val status: Int? = getEventStatus(event.eventStatus)
        if (status != null) {
            values.put(Events.STATUS, status)
        }

        var duration: String? = null
        var end: Long? = null
        var endTimeZone: String? = null

        val recurrenceRuleParams = event.recurrenceRule?.let { buildRecurrenceRuleParams(it) }
        if (recurrenceRuleParams != null) {
            values.put(Events.RRULE, recurrenceRuleParams)
            val difference = (event.eventEndDate ?: event.eventStartDate!!) - event.eventStartDate!!
            duration = formatDuration(difference, event.eventAllDay)
        } else {
            // Explicitly clear recurrence columns so updating a recurring event to non-recurring
            // does not leave RRULE behind and cause CalendarProvider2.scrubEventData() to wipe DTEND.
            values.putNull(Events.RRULE)
            values.putNull(Events.RDATE)
            values.putNull(Events.EXRULE)
            values.putNull(Events.EXDATE)
            end = event.eventEndDate!!
            endTimeZone = if (event.eventAllDay) "UTC" else getTimeZone(event.eventEndTimeZone).id
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
        if (arePermissionsGranted()) {
            val existingCal = retrieveCalendar(calendarId, pendingChannelResult, isInternalCall = true)
            if (existingCal == null) {
                finishWithError(
                    EC.NOT_FOUND,
                    "The calendar with the ID $calendarId could not be found",
                    pendingChannelResult
                )
                return
            }

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

            if (startDate == null && endDate == null && followingInstances == null) {
                // --- Delete all instances ---
                val eventsUriWithId = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                var masterSyncId: String? = null
                contentResolver?.query(
                    eventsUriWithId,
                    arrayOf(Events._SYNC_ID),
                    null, null, null
                )?.use { masterEventCursor ->
                    if (masterEventCursor.moveToFirst()) {
                        masterSyncId = masterEventCursor.getString(0)
                    }
                }

                val ops = ArrayList<ContentProviderOperation>()
                ops.add(
                    ContentProviderOperation.newDelete(buildUriForCalendar(eventsUriWithId, existingCal))
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
                    ContentProviderOperation.newDelete(buildUriForCalendar(Events.CONTENT_URI, existingCal))
                        .withSelection(exceptionWhere, exceptionArgs)
                        .build()
                )

                try {
                    val results = contentResolver?.applyBatch(CalendarContract.AUTHORITY, ops)
                    val deleteSucceeded = (results?.get(0)?.count ?: 0) > 0
                    finishWithSuccess(deleteSucceeded, pendingChannelResult)
                } catch (e: Exception) {
                    finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
                }
            } else {
                if (!followingInstances!!) {
                    // --- Only this instance ---
                    val targetEventUri = ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber)
                    var masterStart: Long? = null
                    var masterEnd: Long? = null
                    var masterDuration: String? = null
                    var masterTimezone: String? = null
                    var masterRrule: String? = null
                    var masterSyncId: String? = null
                    var masterAllDay = false
                    var originalId: Long? = null
                    var originalSyncId: String? = null
                    var foundEvent = false

                    contentResolver?.query(
                        targetEventUri,
                        arrayOf(
                            Events.DTSTART,
                            Events.DTEND,
                            Events.DURATION,
                            Events.EVENT_TIMEZONE,
                            Events.RRULE,
                            Events._SYNC_ID,
                            Events.ALL_DAY,
                            Events.ORIGINAL_ID,
                            Events.ORIGINAL_SYNC_ID
                        ),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            foundEvent = true
                            masterStart = cursor.getLong(0)
                            masterEnd = if (cursor.isNull(1)) null else cursor.getLong(1)
                            masterDuration = cursor.getString(2)
                            masterTimezone = cursor.getString(3)
                            masterRrule = cursor.getString(4)
                            masterSyncId = cursor.getString(5)
                            masterAllDay = cursor.getInt(6) != 0
                            originalId = if (cursor.isNull(7)) null else cursor.getLong(7)
                            originalSyncId = cursor.getString(8)
                        }
                    }

                    if (!foundEvent) {
                        finishWithSuccess(false, pendingChannelResult)
                        return
                    }

                    // Case A: Deleting an ALREADY-DETACHED exception occurrence.
                    // Mark STATUS = STATUS_CANCELED (never hard-delete or insert nested exception),
                    // so CalendarInstancesHelper suppresses the original master occurrence even across full rebuilds.
                    if (originalId != null || !originalSyncId.isNullOrEmpty()) {
                        var ensuredOrigSyncId = originalSyncId
                        if (ensuredOrigSyncId.isNullOrEmpty() && originalId != null && existingCal.accountType == CalendarContract.ACCOUNT_TYPE_LOCAL) {
                            // Ensure parent master has a _SYNC_ID so AOSP suppresses the original occurrence
                            contentResolver?.query(
                                ContentUris.withAppendedId(Events.CONTENT_URI, originalId!!),
                                arrayOf(Events._SYNC_ID),
                                null, null, null
                            )?.use { mc ->
                                if (mc.moveToFirst()) {
                                    ensuredOrigSyncId = mc.getString(0)
                                }
                            }
                            if (ensuredOrigSyncId.isNullOrEmpty()) {
                                ensuredOrigSyncId = UUID.randomUUID().toString()
                                val masterSyncValues = ContentValues().apply {
                                    put(Events._SYNC_ID, ensuredOrigSyncId)
                                }
                                contentResolver?.update(
                                    buildUriForCalendar(ContentUris.withAppendedId(Events.CONTENT_URI, originalId!!), existingCal),
                                    masterSyncValues,
                                    null,
                                    null
                                )
                            }
                        }

                        val cancelValues = ContentValues().apply {
                            put(Events.STATUS, Events.STATUS_CANCELED)
                            if (masterStart != null) put(Events.DTSTART, masterStart)
                            if (!ensuredOrigSyncId.isNullOrEmpty() && originalSyncId.isNullOrEmpty()) {
                                put(Events.ORIGINAL_SYNC_ID, ensuredOrigSyncId)
                            }
                        }
                        val updated = contentResolver?.update(
                            buildUriForCalendar(targetEventUri, existingCal),
                            cancelValues,
                            null,
                            null
                        ) ?: 0
                        finishWithSuccess(updated > 0, pendingChannelResult)
                        return
                    }

                    // Case B: Non-recurring event -> delete directly
                    if (masterRrule.isNullOrEmpty()) {
                        val deleted = contentResolver?.delete(
                            buildUriForCalendar(targetEventUri, existingCal),
                            null,
                            null
                        ) ?: 0
                        finishWithSuccess(deleted > 0, pendingChannelResult)
                        return
                    }

                    // Case C: Master recurring event -> ensure DURATION and local _SYNC_ID, then insert CANCELED exception
                    val (ensuredDuration, ensuredSyncId) = ensureMasterEventReadyForRecurrenceOp(
                        contentResolver = contentResolver,
                        eventId = eventIdNumber,
                        calendar = existingCal,
                        dtStart = masterStart ?: startDate!!,
                        dtEnd = masterEnd,
                        existingDuration = masterDuration,
                        existingTimezone = masterTimezone,
                        existingRrule = masterRrule,
                        existingSyncId = masterSyncId,
                        allDay = masterAllDay
                    )
                    masterDuration = ensuredDuration
                    masterSyncId = ensuredSyncId

                    // Find the exact target instance without VISIBLE = 1 filter
                    var targetInstanceBegin: Long? = null
                    queryInstancesForEvent(
                        contentResolver = contentResolver,
                        projection = Cst.EVENT_INSTANCE_DELETION,
                        begin = startDate!!,
                        end = endDate!!,
                        eventId = eventIdNumber
                    )?.use { instanceCursor ->
                        while (instanceCursor.moveToNext()) {
                            val instanceBegin = instanceCursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                            if (instanceBegin == startDate) {
                                targetInstanceBegin = instanceBegin
                                break
                            }
                            if (targetInstanceBegin == null) {
                                targetInstanceBegin = instanceBegin
                            }
                        }
                    }

                    val effectiveInstanceBegin = targetInstanceBegin ?: startDate!!
                    val values = ContentValues().apply {
                        put(Events.ORIGINAL_INSTANCE_TIME, effectiveInstanceBegin)
                        put(Events.STATUS, Events.STATUS_CANCELED)
                        put(Events.DTSTART, effectiveInstanceBegin)
                        // Never put Events.DTEND into CONTENT_EXCEPTION_URI (forbidden by ALLOWED_IN_EXCEPTION)
                        put(Events.DURATION, masterDuration)
                        put(Events.ALL_DAY, if (masterAllDay) 1 else 0)
                        put(Events.EVENT_TIMEZONE, if (masterAllDay) "UTC" else (masterTimezone ?: "UTC"))
                        if (!masterSyncId.isNullOrEmpty()) {
                            put(Events.ORIGINAL_SYNC_ID, masterSyncId)
                        }
                    }

                    val exceptionUriWithId = ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, eventIdNumber)
                    val targetUri = buildUriForCalendar(exceptionUriWithId, existingCal)
                    try {
                        val deleteSucceeded = contentResolver?.insert(targetUri, values)
                        if (deleteSucceeded != null && masterSyncId.isNullOrEmpty()) {
                            // Workaround for AOSP CalendarInstancesHelper:810 when remote event has no _sync_id yet
                            val touchValues = ContentValues().apply {
                                if (masterStart != null) put(Events.DTSTART, masterStart)
                                put(Events.DURATION, masterDuration)
                                if (masterTimezone != null) put(Events.EVENT_TIMEZONE, masterTimezone)
                                if (masterRrule != null) put(Events.RRULE, masterRrule)
                                put(Events.ALL_DAY, if (masterAllDay) 1 else 0)
                            }
                            contentResolver?.update(
                                buildUriForCalendar(targetEventUri, existingCal),
                                touchValues,
                                null,
                                null
                            )
                        }
                        finishWithSuccess(deleteSucceeded != null, pendingChannelResult)
                    } catch (e: Exception) {
                        finishWithError(EC.GENERIC_ERROR, e.message, pendingChannelResult)
                    }
                } else {
                    // --- This and following instances ---
                    var targetMasterId: Long = eventIdNumber
                    var cutoffStart: Long = startDate!!

                    // If triggered on a detached exception row, resolve the parent master series
                    contentResolver?.query(
                        ContentUris.withAppendedId(Events.CONTENT_URI, eventIdNumber),
                        arrayOf(Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME),
                        null, null, null
                    )?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) {
                            targetMasterId = c.getLong(0)
                            if (!c.isNull(1)) {
                                cutoffStart = c.getLong(1)
                            }
                        }
                    }

                    val eventsUriWithId = ContentUris.withAppendedId(Events.CONTENT_URI, targetMasterId)
                    var masterStart: Long? = null
                    var masterEnd: Long? = null
                    var masterDuration: String? = null
                    var masterTimezone: String? = null
                    var masterRrule: String? = null
                    var masterSyncId: String? = null
                    var masterAllDay = false
                    var foundEvent = false

                    contentResolver?.query(
                        eventsUriWithId,
                        arrayOf(
                            Events.DTSTART,
                            Events.DTEND,
                            Events.DURATION,
                            Events.EVENT_TIMEZONE,
                            Events.RRULE,
                            Events._SYNC_ID,
                            Events.ALL_DAY
                        ),
                        null, null, null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            foundEvent = true
                            masterStart = cursor.getLong(0)
                            masterEnd = if (cursor.isNull(1)) null else cursor.getLong(1)
                            masterDuration = cursor.getString(2)
                            masterTimezone = cursor.getString(3)
                            masterRrule = cursor.getString(4)
                            masterSyncId = cursor.getString(5)
                            masterAllDay = cursor.getInt(6) != 0
                        }
                    }

                    if (!foundEvent) {
                        finishWithSuccess(false, pendingChannelResult)
                        return
                    }

                    if (masterRrule.isNullOrEmpty()) {
                        contentResolver?.delete(buildUriForCalendar(eventsUriWithId, existingCal), null, null)
                        finishWithSuccess(true, pendingChannelResult)
                    } else {
                        val newRule = Rrule(masterRrule)
                        val queryStart = masterStart ?: (cutoffStart - DateUtils.YEAR_IN_MILLIS)
                        var masterInstancesBeforeCutoff = 0
                        queryInstancesForEvent(
                            contentResolver = contentResolver,
                            projection = Cst.EVENT_INSTANCE_DELETION,
                            begin = queryStart,
                            end = cutoffStart - 1L,
                            eventId = targetMasterId
                        )?.use { cursor ->
                            while (cursor.moveToNext()) {
                                val begin = cursor.getLong(Cst.EVENT_INSTANCE_DELETION_BEGIN_INDEX)
                                if (begin < cutoffStart) {
                                    masterInstancesBeforeCutoff++
                                }
                            }
                        }
                        val (totalExBeforeCutoff, nonCanceledExBeforeCutoff) = countExceptionsBeforeCutoff(
                            contentResolver, targetMasterId, masterSyncId, cutoffStart
                        )
                        val rruleOccurrencesBeforeCutoff = masterInstancesBeforeCutoff + totalExBeforeCutoff
                        val visibleOccurrencesBeforeCutoff = masterInstancesBeforeCutoff + nonCanceledExBeforeCutoff

                        if (visibleOccurrencesBeforeCutoff > 0) {
                            // Remove any edited/canceled exceptions at or after cutoffStart
                            deleteExceptionsForMaster(
                                contentResolver,
                                targetMasterId,
                                masterSyncId,
                                existingCal,
                                fromInstanceTimeInclusive = cutoffStart
                            )

                            val (ensuredDuration, _) = ensureMasterEventReadyForRecurrenceOp(
                                contentResolver = contentResolver,
                                eventId = targetMasterId,
                                calendar = existingCal,
                                dtStart = masterStart ?: cutoffStart,
                                dtEnd = masterEnd,
                                existingDuration = masterDuration,
                                existingTimezone = masterTimezone,
                                existingRrule = masterRrule,
                                existingSyncId = masterSyncId,
                                allDay = masterAllDay
                            )

                            if (newRule.count != null && newRule.count > 0) {
                                // RFC 5545 COUNT counts all RRULE occurrences including prior canceled/edited exceptions
                                newRule.count = rruleOccurrencesBeforeCutoff
                            } else {
                                // Use cutoffStart - 1 second (never previous END, which for all-day/back-to-back
                                // events equals cutoffStart and inclusively keeps the deleted occurrence!)
                                newRule.until = DateTime(cutoffStart - 1000L)
                            }

                            val values = ContentValues().apply {
                                put(Events.RRULE, newRule.toString())
                                putNull(Events.DTEND)
                                put(Events.DURATION, ensuredDuration)
                                put(Events.ALL_DAY, if (masterAllDay) 1 else 0)
                                if (masterStart != null) put(Events.DTSTART, masterStart)
                                put(
                                    Events.EVENT_TIMEZONE,
                                    if (masterAllDay) "UTC" else (masterTimezone ?: TimeZone.getDefault().id)
                                )
                            }
                            contentResolver?.update(
                                buildUriForCalendar(eventsUriWithId, existingCal),
                                values,
                                null,
                                null
                            )
                            finishWithSuccess(true, pendingChannelResult)
                        } else {
                            // 0 instances remain prior to cutoffStart: delete master AND all its exceptions
                            contentResolver?.delete(buildUriForCalendar(eventsUriWithId, existingCal), null, null)
                            deleteExceptionsForMaster(contentResolver, targetMasterId, masterSyncId, existingCal)
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

    private fun parseEventFromEventsTable(calendarId: String, cursor: Cursor?): Event? {
        if (cursor == null) {
            return null
        }
        val eventId = getColumnLong(cursor, Events._ID, Cst.EVENT_PROJECTION_ID_INDEX)
        val title = getColumnString(cursor, Events.TITLE, Cst.EVENT_PROJECTION_TITLE_INDEX)
        val description = getColumnString(cursor, Events.DESCRIPTION, Cst.EVENT_PROJECTION_DESCRIPTION_INDEX)
        val begin = getColumnLong(cursor, Events.DTSTART, Cst.EVENT_PROJECTION_BEGIN_INDEX)
        val rawEnd = getColumnLong(cursor, Events.DTEND, Cst.EVENT_PROJECTION_END_INDEX, default = -1L)
        val durationStr = getColumnString(cursor, Events.DURATION, Cst.EVENT_PROJECTION_DURATION_INDEX)
        val recurringRule = getColumnString(cursor, Events.RRULE, Cst.EVENT_PROJECTION_RECURRING_RULE_INDEX)
        val allDay = getColumnInt(cursor, Events.ALL_DAY, Cst.EVENT_PROJECTION_ALL_DAY_INDEX) > 0
        val end = if (rawEnd >= 0L) rawEnd else begin + parseDurationToMillis(durationStr, allDay)
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
        if (dateTime.isAllDay) {
            return "$year-$month-${day}T00:00:00Z"
        }
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
        var uri: Uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        contentResolver?.query(
            uri,
            arrayOf(CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val accountName = cursor.getString(0) ?: ""
                val accountType = cursor.getString(1) ?: ""
                if (accountType == CalendarContract.ACCOUNT_TYPE_LOCAL && accountName.isNotEmpty()) {
                    uri = buildLocalSyncAdapterUri(uri, accountName, accountType)
                }
            }
        }
        val values = ContentValues().apply {
            if (newColorKey != null) {
                put(CalendarContract.Calendars.CALENDAR_COLOR_KEY, newColorKey)
            }
            if (newColor != null) {
                put(CalendarContract.Calendars.CALENDAR_COLOR, newColor)
            }
        }
        if (values.size() == 0) return false
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
