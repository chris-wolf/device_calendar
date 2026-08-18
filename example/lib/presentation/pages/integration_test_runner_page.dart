import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

class TestStepResult {
  final String suiteName;
  final String stepName;
  final bool isSuccess;
  final String message;
  final String? errorDetails;

  TestStepResult({
    required this.suiteName,
    required this.stepName,
    required this.isSuccess,
    required this.message,
    this.errorDetails,
  });
}

class IntegrationTestRunnerPage extends StatefulWidget {
  const IntegrationTestRunnerPage({super.key});

  @override
  State<IntegrationTestRunnerPage> createState() => _IntegrationTestRunnerPageState();
}

class _IntegrationTestRunnerPageState extends State<IntegrationTestRunnerPage> {
  final DeviceCalendarPlugin _plugin = DeviceCalendarPlugin();
  final ScrollController _scrollController = ScrollController();
  final StringBuffer _logBuffer = StringBuffer();

  bool _isRunning = false;
  String _currentStepName = '';
  final List<TestStepResult> _results = [];
  int _passedCount = 0;
  int _failedCount = 0;

  @override
  void initState() {
    super.initState();
    tz.initializeTimeZones();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _log(String line) {
    final timestamp = DateTime.now().toIso8601String().substring(11, 19);
    final formatted = '[$timestamp] $line';
    _logBuffer.writeln(formatted);
    debugPrint(formatted);
  }

  void _recordResult(String suite, String step, bool success, String message, [String? details]) {
    final statusTag = success ? '✅ PASS' : '❌ FAIL';
    _log('$statusTag [$suite] $step: $message');
    if (details != null && details.isNotEmpty) {
      _log('   Error details: $details');
    }

    final result = TestStepResult(
      suiteName: suite,
      stepName: step,
      isSuccess: success,
      message: message,
      errorDetails: details,
    );
    setState(() {
      _results.add(result);
      if (success) {
        _passedCount++;
      } else {
        _failedCount++;
      }
    });
    _scrollToBottom();
  }

  void _assert(bool condition, String errorMessage) {
    if (!condition) {
      throw Exception(errorMessage);
    }
  }

  void _copyLogsToClipboard() {
    final report = StringBuffer();
    report.writeln('========================================');
    report.writeln('DEVICE_CALENDAR INTEGRATION TEST REPORT');
    report.writeln('Date: ${DateTime.now().toIso8601String()}');
    report.writeln('Platform: ${Platform.operatingSystem} ${Platform.operatingSystemVersion}');
    report.writeln('Summary: $_passedCount Passed, $_failedCount Failed, ${_results.length} Total Steps');
    report.writeln('========================================\n');

    report.writeln('--- STEP RESULTS ---');
    for (final res in _results) {
      final tag = res.isSuccess ? 'PASS' : 'FAIL';
      report.writeln('[$tag] [${res.suiteName}] ${res.stepName}: ${res.message}');
      if (res.errorDetails != null) {
        report.writeln('       Error: ${res.errorDetails}');
      }
    }

    report.writeln('\n--- DETAILED EXECUTION LOGS ---');
    report.writeln(_logBuffer.toString());

    Clipboard.setData(ClipboardData(text: report.toString()));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Test report and detailed logs copied to clipboard! 📋'),
        duration: Duration(seconds: 3),
      ),
    );
  }

  Future<Event?> _loadEvent(String? calId, String? eventId) async {
    final now = tz.TZDateTime.now(tz.local);
    final params = RetrieveEventsParams(
      startDate: now.subtract(const Duration(days: 60)),
      endDate: now.add(const Duration(days: 365)),
      eventIds: eventId != null ? [eventId] : null,
    );
    final res = await _plugin.retrieveEvents(calId, params);
    if (!res.isSuccess || res.data == null) return null;
    final matches = res.data!.where((e) => e.eventId == eventId).toList();
    return matches.isNotEmpty ? matches.first : null;
  }

  Future<List<Event>> _loadInstances(
    String? calId, {
    required tz.TZDateTime startDate,
    required tz.TZDateTime endDate,
    String? eventId,
  }) async {
    final params = RetrieveEventsParams(
      startDate: startDate,
      endDate: endDate,
      eventIds: eventId != null ? [eventId] : null,
    );
    final res = await _plugin.retrieveEvents(calId, params);
    if (!res.isSuccess || res.data == null) {
      throw Exception('Failed to retrieve events: ${res.errors.map((e) => e.errorMessage).toList()}');
    }
    final list = res.data!.toList();
    if (eventId != null) {
      return list.where((e) => e.eventId == eventId).toList();
    }
    return list;
  }

  Future<void> _safeDeleteEvent(String? calId, String? eventId) async {
    if (calId == null || eventId == null) return;
    try {
      _log('Cleanup: deleting event $eventId from calendar $calId');
      await _plugin.deleteEvent(calId, eventId);
    } catch (e) {
      _log('Cleanup notice (ignoring): $e');
    }
  }

  Future<void> _runAllTests() async {
    if (_isRunning) return;

    setState(() {
      _isRunning = true;
      _results.clear();
      _logBuffer.clear();
      _passedCount = 0;
      _failedCount = 0;
      _currentStepName = 'Starting comprehensive test suite...';
    });

    _log('=== STARTING COMPREHENSIVE INTEGRATION TEST SUITE ===');
    _log('OS: ${Platform.operatingSystem} | Version: ${Platform.operatingSystemVersion}');

    String? calendarIdA;
    String? calendarIdB;
    EventColor? eventColorA;

    try {
      // =======================================================================
      // SUITE 1: CALENDAR CREATION & PERMISSIONS
      // =======================================================================
      const suite1 = '1. Calendar Creation & Management';

      // 1.1 Request permissions
      setState(() => _currentStepName = '1.1 Requesting calendar permissions...');
      _log('\n--- SUITE 1: Calendar Creation & Management ---');
      _log('Step 1.1: Requesting calendar permissions via _plugin.requestPermissions()...');
      try {
        final permRes = await _plugin.requestPermissions();
        _log('requestPermissions result: success=${permRes.isSuccess}, data=${permRes.data}');
        _assert(permRes.isSuccess && permRes.data == true, 'Calendar permission denied');
        _recordResult(suite1, '1.1 Calendar Permissions', true, 'Permissions granted successfully');
      } catch (e) {
        _recordResult(suite1, '1.1 Calendar Permissions', false, 'Permission error', e.toString());
        throw Exception('Cannot continue without calendar permissions');
      }

      // 1.2 Create test calendar A and B
      setState(() => _currentStepName = '1.2 Creating test calendars A and B...');
      _log('Step 1.2: Cleaning up any old leftover test calendars and creating new ones...');
      try {
        final existingCals = await _plugin.retrieveCalendars();
        if (existingCals.isSuccess && existingCals.data != null) {
          for (final c in existingCals.data!) {
            if ((c.name == 'Comprehensive Test Cal A' || c.name == 'Comprehensive Test Cal B') && c.id != null) {
              _log('Initial cleanup: removing old leftover test calendar ${c.name} (ID: ${c.id})...');
              await _plugin.deleteCalendar(c.id!);
            }
          }
        }

        final calARes = await _plugin.createCalendar(
          'Comprehensive Test Cal A',
          calendarColor: Colors.blue,
          localAccountName: 'test_account_a',
        );
        _assert(calARes.isSuccess && calARes.data != null, 'Failed to create Calendar A: ${calARes.errors.map((e) => e.errorMessage)}');
        calendarIdA = calARes.data;
        _log('Created Calendar A with ID: $calendarIdA');

        final calBRes = await _plugin.createCalendar(
          'Comprehensive Test Cal B',
          calendarColor: Colors.green,
          localAccountName: 'test_account_b',
        );
        _assert(calBRes.isSuccess && calBRes.data != null, 'Failed to create Calendar B: ${calBRes.errors.map((e) => e.errorMessage)}');
        calendarIdB = calBRes.data;
        _log('Created Calendar B with ID: $calendarIdB');

        _recordResult(suite1, '1.2 Create Test Calendars', true, 'Created Cal A ($calendarIdA) and Cal B ($calendarIdB)');
      } catch (e) {
        _recordResult(suite1, '1.2 Create Test Calendars', false, 'Failed to create calendars', e.toString());
        throw Exception('Calendar creation failed');
      }

      // 1.3 Verify calendars retrieved and writable
      setState(() => _currentStepName = '1.3 Verifying created calendars...');
      _log('Step 1.3: Calling retrieveCalendars() to verify both calendars exist and are writable...');
      try {
        final allCals = await _plugin.retrieveCalendars();
        _assert(allCals.isSuccess && allCals.data != null, 'Failed to retrieve calendars');
        final foundA = allCals.data!.firstWhere((c) => c.id == calendarIdA);
        final foundB = allCals.data!.firstWhere((c) => c.id == calendarIdB);
        _log('Found Cal A: name="${foundA.name}", isReadOnly=${foundA.isReadOnly}, color=${foundA.color}');
        _log('Found Cal B: name="${foundB.name}", isReadOnly=${foundB.isReadOnly}, color=${foundB.color}');
        _assert(foundA.name == 'Comprehensive Test Cal A' && foundA.isReadOnly == false, 'Cal A mismatch or read-only');
        _assert(foundB.name == 'Comprehensive Test Cal B' && foundB.isReadOnly == false, 'Cal B mismatch or read-only');

        if (Platform.isAndroid) {
          final colors = await _plugin.retrieveEventColors(foundA);
          if (colors != null && colors.isNotEmpty) {
            eventColorA = colors.first;
            _log('Android Event Color retrieved: ${eventColorA.color}');
          }
        }
        _recordResult(suite1, '1.3 Retrieve & Verify Calendars', true, 'Both calendars verified and writable');
      } catch (e) {
        _recordResult(suite1, '1.3 Retrieve & Verify Calendars', false, 'Calendar verification failed', e.toString());
      }

      // 1.4 Update calendar color
      setState(() => _currentStepName = '1.4 Updating calendar color...');
      _log('Step 1.4: Updating calendar color to purple...');
      try {
        final allCals = await _plugin.retrieveCalendars();
        final foundA = allCals.data!.firstWhere((c) => c.id == calendarIdA);
        final updated = await _plugin.updateCalendarColor(foundA, color: Colors.purple);
        _log('updateCalendarColor returned: $updated');
        _recordResult(suite1, '1.4 Update Calendar Color', true, 'Calendar color update result: $updated');
      } catch (e) {
        _recordResult(suite1, '1.4 Update Calendar Color', false, 'Update calendar color failed', e.toString());
      }

      // =======================================================================
      // SUITE 2: NORMAL EVENT FIELD-BY-FIELD UPDATES & RELOAD
      // =======================================================================
      const suite2 = '2. Normal Event: Field Updates & Reload Checks';
      String? normalEventId;
      final localLoc = tz.local;
      final baseDate = tz.TZDateTime(localLoc, 2026, 9, 1, 10, 0, 0);

      _log('\n--- SUITE 2: Normal Event Field-by-Field Updates & Reload ---');

      // 2.1 Create normal event
      setState(() => _currentStepName = '2.1 Creating normal event with initial fields...');
      _log('Step 2.1: Creating normal event in Calendar A (Title: "Normal Event Initial Title", Time: 10:00-11:00)...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Normal Event Initial Title'
          ..description = 'Initial Description'
          ..start = baseDate
          ..end = baseDate.add(const Duration(hours: 1))
          ..allDay = false
          ..location = 'Room 101'
          ..url = Uri.parse('https://example.com/e1')
          ..availability = Availability.Busy
          ..status = EventStatus.Confirmed
          ..reminders = [Reminder(minutes: 15)];

        if (Platform.isAndroid) {
          event.attendees = [
            Attendee(
              name: 'Alice',
              emailAddress: 'alice@example.com',
              role: AttendeeRole.Required,
            )
          ];
          if (eventColorA != null) {
            event.updateEventColor(eventColorA);
          }
        }

        final createRes = await _plugin.createOrUpdateEvent(event);
        _assert(createRes?.isSuccess == true && createRes?.data != null, 'Failed to create normal event: ${createRes?.errors.map((e) => e.errorMessage)}');
        normalEventId = createRes!.data!;
        _log('Event created with ID: $normalEventId. Reloading from device to verify initial values...');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _assert(loaded != null, 'Could not reload normal event');
        _log('Loaded back: title="${loaded!.title}", desc="${loaded.description}", loc="${loaded.location}", start=${loaded.start}, end=${loaded.end}');
        _assert(loaded.title == 'Normal Event Initial Title', 'Title mismatch: ${loaded.title}');
        _assert(loaded.description == 'Initial Description', 'Description mismatch: ${loaded.description}');
        _assert(loaded.location == 'Room 101', 'Location mismatch: ${loaded.location}');
        _assert(loaded.start?.millisecondsSinceEpoch == baseDate.millisecondsSinceEpoch, 'Start time mismatch');
        _recordResult(suite2, '2.1 Initial Creation & Reload', true, 'Event created ($normalEventId) and all fields verified');
      } catch (e) {
        _recordResult(suite2, '2.1 Initial Creation & Reload', false, 'Creation failed', e.toString());
      }

      // 2.2 Update Title
      setState(() => _currentStepName = '2.2 Updating Title and reloading...');
      _log('Step 2.2: Updating title to "Updated Title v2" and reloading from device...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.title = 'Updated Title v2';
        final updateRes = await _plugin.createOrUpdateEvent(event);
        _assert(updateRes?.isSuccess == true, 'Update title failed');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded after title update: title="${loaded?.title}", description="${loaded?.description}"');
        _assert(loaded?.title == 'Updated Title v2', 'Title did not update: ${loaded?.title}');
        _assert(loaded?.description == 'Initial Description', 'Description was corrupted');
        _recordResult(suite2, '2.2 Update Title', true, 'Title updated to "Updated Title v2" while preserving other fields');
      } catch (e) {
        _recordResult(suite2, '2.2 Update Title', false, 'Title update failed', e.toString());
      }

      // 2.3 Update Description
      setState(() => _currentStepName = '2.3 Updating Description and reloading...');
      _log('Step 2.3: Updating description to "Updated Description v2 - detailed notes." and reloading...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.description = 'Updated Description v2 - detailed notes.';
        final updateRes = await _plugin.createOrUpdateEvent(event);
        _assert(updateRes?.isSuccess == true, 'Update description failed');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded after description update: description="${loaded?.description}", title="${loaded?.title}"');
        _assert(loaded?.description == 'Updated Description v2 - detailed notes.', 'Description did not update');
        _assert(loaded?.title == 'Updated Title v2', 'Title was corrupted');
        _recordResult(suite2, '2.3 Update Description', true, 'Description updated and verified');
      } catch (e) {
        _recordResult(suite2, '2.3 Update Description', false, 'Description update failed', e.toString());
      }

      // 2.4 Update Location
      setState(() => _currentStepName = '2.4 Updating Location and reloading...');
      _log('Step 2.4: Updating location to "Building B, Room 404" and reloading...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.location = 'Building B, Room 404';
        final updateRes = await _plugin.createOrUpdateEvent(event);
        _assert(updateRes?.isSuccess == true, 'Update location failed');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded after location update: location="${loaded?.location}"');
        _assert(loaded?.location == 'Building B, Room 404', 'Location did not update');
        _recordResult(suite2, '2.4 Update Location', true, 'Location updated to "Building B, Room 404"');
      } catch (e) {
        _recordResult(suite2, '2.4 Update Location', false, 'Location update failed', e.toString());
      }

      // 2.5 Update URL
      setState(() => _currentStepName = '2.5 Updating URL and reloading...');
      _log('Step 2.5: Updating URL to "https://example.com/updated_link" and reloading...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.url = Uri.parse('https://example.com/updated_link');
        final updateRes = await _plugin.createOrUpdateEvent(event);
        _assert(updateRes?.isSuccess == true, 'Update URL failed');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded after URL update: url="${loaded?.url}"');
        _assert(loaded?.url?.toString() == 'https://example.com/updated_link', 'URL did not update');
        _recordResult(suite2, '2.5 Update URL', true, 'URL updated to "https://example.com/updated_link"');
      } catch (e) {
        _recordResult(suite2, '2.5 Update URL', false, 'URL update failed', e.toString());
      }

      // 2.6 Update Times (Shift to 14:00 - 15:30)
      setState(() => _currentStepName = '2.6 Updating Start & End Times...');
      _log('Step 2.6: Shifting event time from 10:00-11:00 to 14:00-15:30 and reloading...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        final newStart = baseDate.add(const Duration(hours: 4)); // 14:00
        final newEnd = baseDate.add(const Duration(hours: 5, minutes: 30)); // 15:30
        event!.start = newStart;
        event.end = newEnd;
        final updateRes = await _plugin.createOrUpdateEvent(event);
        _assert(updateRes?.isSuccess == true, 'Update times failed');

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded after time update: start=${loaded?.start}, end=${loaded?.end}');
        _assert(loaded?.start?.hour == 14 && loaded?.end?.hour == 15 && loaded?.end?.minute == 30, 'Times did not update correctly');
        _recordResult(suite2, '2.6 Update Start & End Times', true, 'Shifted time to 14:00 - 15:30');
      } catch (e) {
        _recordResult(suite2, '2.6 Update Start & End Times', false, 'Time update failed', e.toString());
      }

      // 2.7 Update All-Day Flag
      setState(() => _currentStepName = '2.7 Updating All-Day Flag...');
      _log('Step 2.7: Toggling allDay = true, saving, reloading, then toggling back to allDay = false...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.allDay = true;
        await _plugin.createOrUpdateEvent(event);

        await Future.delayed(const Duration(milliseconds: 400));
        var loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded with allDay=true: allDay=${loaded?.allDay}, start=${loaded?.start}');
        _assert(loaded?.allDay == true, 'allDay was not set to true');

        // Toggle back to false
        loaded!.allDay = false;
        loaded.start = baseDate.add(const Duration(hours: 3));
        loaded.end = baseDate.add(const Duration(hours: 4));
        await _plugin.createOrUpdateEvent(loaded);

        await Future.delayed(const Duration(milliseconds: 400));
        loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded with allDay=false: allDay=${loaded?.allDay}, start=${loaded?.start}');
        _assert(loaded?.allDay == false, 'allDay was not restored to false');
        _recordResult(suite2, '2.7 Update All-Day Flag', true, 'Toggled allDay true and back to false');
      } catch (e) {
        _recordResult(suite2, '2.7 Update All-Day Flag', false, 'All-Day update failed', e.toString());
      }

      // 2.8 Update Availability
      setState(() => _currentStepName = '2.8 Updating Availability...');
      _log('Step 2.8: Updating availability: Busy -> Free -> Busy...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.availability = Availability.Free;
        await _plugin.createOrUpdateEvent(event);

        await Future.delayed(const Duration(milliseconds: 400));
        var loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded with Availability.Free: availability=${loaded?.availability}');
        _assert(loaded?.availability == Availability.Free, 'Availability not Free');

        loaded!.availability = Availability.Busy;
        await _plugin.createOrUpdateEvent(loaded);

        await Future.delayed(const Duration(milliseconds: 400));
        loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded with Availability.Busy: availability=${loaded?.availability}');
        _assert(loaded?.availability == Availability.Busy, 'Availability not Busy');
        _recordResult(suite2, '2.8 Update Availability', true, 'Availability toggled Free -> Busy');
      } catch (e) {
        _recordResult(suite2, '2.8 Update Availability', false, 'Availability update failed', e.toString());
      }

      // 2.9 Update Status
      setState(() => _currentStepName = '2.9 Updating Event Status...');
      _log('Step 2.9: Updating status: Confirmed -> Tentative -> Confirmed...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.status = EventStatus.Tentative;
        await _plugin.createOrUpdateEvent(event);

        await Future.delayed(const Duration(milliseconds: 400));
        var loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded status: status=${loaded?.status}');
        if (loaded?.status != null) {
          _assert(loaded?.status == EventStatus.Tentative, 'Status not Tentative');
        }

        loaded!.status = EventStatus.Confirmed;
        await _plugin.createOrUpdateEvent(loaded);
        _recordResult(suite2, '2.9 Update Event Status', true, 'Status updated Tentative -> Confirmed');
      } catch (e) {
        _recordResult(suite2, '2.9 Update Event Status', false, 'Status update failed', e.toString());
      }

      // 2.10 Update Reminders
      setState(() => _currentStepName = '2.10 Updating Reminders...');
      _log('Step 2.10: Setting reminders: [30m, 60m] and reloading...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.reminders = [Reminder(minutes: 30), Reminder(minutes: 60)];
        await _plugin.createOrUpdateEvent(event);

        await Future.delayed(const Duration(milliseconds: 400));
        final loaded = await _loadEvent(calendarIdA, normalEventId);
        _log('Reloaded reminders: ${loaded?.reminders?.map((r) => '${r.minutes}m').toList()}');
        if (loaded?.reminders != null && loaded!.reminders!.isNotEmpty) {
          _assert(loaded.reminders!.any((r) => r.minutes == 30), 'Missing 30m reminder');
          _assert(loaded.reminders!.any((r) => r.minutes == 60), 'Missing 60m reminder');
        }
        _recordResult(suite2, '2.10 Update Reminders', true, 'Set multiple reminders (30m, 60m)');
      } catch (e) {
        _recordResult(suite2, '2.10 Update Reminders', false, 'Reminders update failed', e.toString());
      }

      // 2.11 Move Normal Event to Calendar B & Delete
      setState(() => _currentStepName = '2.11 Moving event to Calendar B and deleting...');
      _log('Step 2.11: Moving event to Calendar B ($calendarIdB), reloading both calendars, then deleting...');
      try {
        final event = await _loadEvent(calendarIdA, normalEventId);
        _assert(event != null, 'Event not found');
        event!.calendarId = calendarIdB;
        final moveRes = await _plugin.createOrUpdateEvent(event);
        _assert(moveRes?.isSuccess == true && moveRes?.data != null, 'Failed to move event to Cal B');
        final movedEventId = moveRes!.data!;
        _log('Moved event. Returned ID: $movedEventId');

        await Future.delayed(const Duration(seconds: 1));
        final listA = await _loadInstances(calendarIdA, startDate: baseDate.subtract(const Duration(days: 5)), endDate: baseDate.add(const Duration(days: 5)));
        _log('Instances remaining in Cal A: ${listA.map((e) => e.title).toList()}');
        _assert(!listA.any((e) => e.eventId == normalEventId || e.eventId == movedEventId), 'Event still found in Cal A after move');

        final listB = await _loadInstances(calendarIdB, startDate: baseDate.subtract(const Duration(days: 5)), endDate: baseDate.add(const Duration(days: 5)));
        _log('Instances found in Cal B: ${listB.map((e) => '${e.title} (${e.eventId})').toList()}');
        _assert(listB.any((e) => e.eventId == movedEventId), 'Moved event not found in Cal B');

        // Delete from Calendar B
        _log('Deleting moved event from Cal B...');
        final delRes = await _plugin.deleteEvent(calendarIdB, movedEventId);
        _assert(delRes.isSuccess && delRes.data == true, 'Failed to delete moved event from Cal B');

        await Future.delayed(const Duration(seconds: 1));
        final listBAfter = await _loadInstances(calendarIdB, startDate: baseDate.subtract(const Duration(days: 5)), endDate: baseDate.add(const Duration(days: 5)));
        _log('Instances in Cal B after delete: ${listBAfter.length}');
        _assert(!listBAfter.any((e) => e.eventId == movedEventId), 'Event still exists after deletion');

        _recordResult(suite2, '2.11 Move & Delete Event', true, 'Moved event to Cal B and verified full deletion');
      } catch (e) {
        _recordResult(suite2, '2.11 Move & Delete Event', false, 'Move / delete failed', e.toString());
      }

      // =======================================================================
      // SUITE 3: RECURRING EVENT RULE TYPES
      // =======================================================================
      const suite3 = '3. Recurring Event Rule Types';
      final recStartDate = tz.TZDateTime(localLoc, 2026, 10, 1, 9, 0, 0);

      _log('\n--- SUITE 3: Recurring Event Rule Types & Expansion ---');

      // 3.1 Daily Recurring (5x)
      setState(() => _currentStepName = '3.1 Testing Daily Recurring (5x)...');
      _log('Step 3.1: Creating Daily Recurring event (count=5)...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Daily Rec 5x'
          ..start = recStartDate
          ..end = recStartDate.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);
        final res = await _plugin.createOrUpdateEvent(event);
        _assert(res?.isSuccess == true && res?.data != null, 'Create daily recurring failed');
        final id = res!.data!;
        _log('Created daily recurring event with ID: $id. Loading instances...');

        await Future.delayed(const Duration(seconds: 1));
        final instances = await _loadInstances(calendarIdA, startDate: recStartDate.subtract(const Duration(days: 1)), endDate: recStartDate.add(const Duration(days: 10)), eventId: id);
        _log('Retrieved ${instances.length} daily instances: ${instances.map((e) => e.start?.toString()).toList()}');
        _assert(instances.length == 5, 'Expected 5 daily instances, got ${instances.length}');

        await _safeDeleteEvent(calendarIdA, id);
        _recordResult(suite3, '3.1 Daily Recurrence (5x)', true, '5 daily instances generated and verified');
      } catch (e) {
        _recordResult(suite3, '3.1 Daily Recurrence (5x)', false, 'Daily recurring failed', e.toString());
      }

      // 3.2 Weekly Recurring (4x)
      setState(() => _currentStepName = '3.2 Testing Weekly Recurring (4x)...');
      _log('Step 3.2: Creating Weekly Recurring event (count=4)...');
      try {
        final start = recStartDate.add(const Duration(days: 15));
        final event = Event(calendarIdA)
          ..title = 'Weekly Rec 4x'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.weekly, count: 4);
        final res = await _plugin.createOrUpdateEvent(event);
        _assert(res?.isSuccess == true && res?.data != null, 'Create weekly recurring failed');
        final id = res!.data!;

        await Future.delayed(const Duration(seconds: 1));
        final instances = await _loadInstances(calendarIdA, startDate: start.subtract(const Duration(days: 1)), endDate: start.add(const Duration(days: 35)), eventId: id);
        _log('Retrieved ${instances.length} weekly instances: ${instances.map((e) => e.start?.toString()).toList()}');
        _assert(instances.length == 4, 'Expected 4 weekly instances, got ${instances.length}');

        await _safeDeleteEvent(calendarIdA, id);
        _recordResult(suite3, '3.2 Weekly Recurrence (4x)', true, '4 weekly instances generated and verified');
      } catch (e) {
        _recordResult(suite3, '3.2 Weekly Recurrence (4x)', false, 'Weekly recurring failed', e.toString());
      }

      // 3.3 Monthly Recurring (3x)
      setState(() => _currentStepName = '3.3 Testing Monthly Recurring (3x)...');
      _log('Step 3.3: Creating Monthly Recurring event (count=3)...');
      try {
        final start = recStartDate.add(const Duration(days: 45));
        final event = Event(calendarIdA)
          ..title = 'Monthly Rec 3x'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.monthly, count: 3);
        final res = await _plugin.createOrUpdateEvent(event);
        _assert(res?.isSuccess == true && res?.data != null, 'Create monthly recurring failed');
        final id = res!.data!;

        await Future.delayed(const Duration(seconds: 1));
        final instances = await _loadInstances(calendarIdA, startDate: start.subtract(const Duration(days: 1)), endDate: start.add(const Duration(days: 120)), eventId: id);
        _log('Retrieved ${instances.length} monthly instances: ${instances.map((e) => e.start?.toString()).toList()}');
        _assert(instances.length == 3, 'Expected 3 monthly instances, got ${instances.length}');

        await _safeDeleteEvent(calendarIdA, id);
        _recordResult(suite3, '3.3 Monthly Recurrence (3x)', true, '3 monthly instances generated and verified');
      } catch (e) {
        _recordResult(suite3, '3.3 Monthly Recurrence (3x)', false, 'Monthly recurring failed', e.toString());
      }

      // =======================================================================
      // SUITE 4: RECURRING EVENT: "EDIT ALL INSTANCES" (MASTER SERIES)
      // =======================================================================
      const suite4 = '4. Recurring Event: Edit All Instances';
      final masterStart = tz.TZDateTime(localLoc, 2026, 11, 1, 10, 0, 0);

      _log('\n--- SUITE 4: Recurring Event: Edit All Instances (Master Series) ---');
      setState(() => _currentStepName = '4.1 Testing Edit All Instances (Master Series)...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Master Series Orig'
          ..description = 'Orig Description'
          ..location = 'Room A'
          ..start = masterStart
          ..end = masterStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);

        final createRes = await _plugin.createOrUpdateEvent(event);
        _assert(createRes?.isSuccess == true, 'Create master series failed');
        final seriesId = createRes!.data!;
        _log('Created master recurring series ID: $seriesId');

        await Future.delayed(const Duration(seconds: 1));
        var instances = await _loadInstances(calendarIdA, startDate: masterStart.subtract(const Duration(days: 1)), endDate: masterStart.add(const Duration(days: 10)), eventId: seriesId);
        _assert(instances.length == 5, 'Initial series count mismatch');

        // Edit all instances title, description, location, and shift time
        _log('Updating master series fields: title="Master Series Updated Title", location="Auditorium", time=12:00...');
        final firstInstance = instances.first;
        firstInstance.title = 'Master Series Updated Title';
        firstInstance.description = 'Updated desc for all';
        firstInstance.location = 'Auditorium';
        firstInstance.start = firstInstance.start!.add(const Duration(hours: 2));
        firstInstance.end = firstInstance.end!.add(const Duration(hours: 2));

        final editRes = await _plugin.createOrUpdateEvent(firstInstance);
        _assert(editRes?.isSuccess == true, 'Edit all instances failed');

        await Future.delayed(const Duration(seconds: 2));
        final afterInstances = await _loadInstances(calendarIdA, startDate: masterStart.subtract(const Duration(days: 1)), endDate: masterStart.add(const Duration(days: 10)), eventId: seriesId);
        _log('Reloaded ${afterInstances.length} instances after master edit:');
        for (final inst in afterInstances) {
          _log('   -> title="${inst.title}", loc="${inst.location}", hour=${inst.start!.hour}');
          _assert(inst.title == 'Master Series Updated Title', 'Instance title mismatch');
          _assert(inst.location == 'Auditorium', 'Instance location mismatch');
          _assert(inst.start!.hour == 12, 'Instance time mismatch');
        }

        await _safeDeleteEvent(calendarIdA, seriesId);
        _recordResult(suite4, '4.1 Edit All Instances', true, 'Title, description, location, and time updated across all 5 instances');
      } catch (e) {
        _recordResult(suite4, '4.1 Edit All Instances', false, 'Edit all instances failed', e.toString());
      }

      // =======================================================================
      // SUITE 5: RECURRING EVENT: "EDIT ONLY THIS EVENT" (EXCEPTION)
      // =======================================================================
      const suite5 = '5. Recurring Event: Edit Only This Event (Exception)';
      final excStart = tz.TZDateTime(localLoc, 2026, 12, 1, 10, 0, 0);

      _log('\n--- SUITE 5: Recurring Event: Edit Only This Event (Single Exception) ---');
      setState(() => _currentStepName = '5.1 Testing Edit Only This Event (Exception)...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Recurring Base Event'
          ..description = 'Base Description'
          ..location = 'Room 1'
          ..start = excStart
          ..end = excStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);

        final createRes = await _plugin.createOrUpdateEvent(event);
        _assert(createRes?.isSuccess == true, 'Create base recurring failed');
        final eventId = createRes!.data!;
        _log('Created recurring event ID: $eventId');

        await Future.delayed(const Duration(seconds: 1));
        final instances = await _loadInstances(calendarIdA, startDate: excStart.subtract(const Duration(days: 1)), endDate: excStart.add(const Duration(days: 10)), eventId: eventId);
        _assert(instances.length == 5, 'Expected 5 initial instances');
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (index 2)
        final targetInstance = instances[2];
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;
        final originalDay = targetInstance.start!.day;

        _log('Detaching 3rd instance (day=$originalDay, start=${targetInstance.start}) with updateFollowingInstances=false...');
        targetInstance.title = 'EXCEPTION - Only This';
        targetInstance.description = 'Exception description';
        targetInstance.location = 'VIP Lounge';
        targetInstance.start = targetInstance.start!.add(const Duration(hours: 2)); // 12:00
        targetInstance.end = targetInstance.end!.add(const Duration(hours: 2)); // 13:00

        final editRes = await _plugin.createOrUpdateEvent(
          targetInstance,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: false,
        );
        _assert(editRes?.isSuccess == true, 'Edit only this instance failed: ${editRes?.errors.map((e) => e.errorMessage)}');

        await Future.delayed(const Duration(seconds: 2));
        final afterInstances = await _loadInstances(calendarIdA, startDate: excStart.subtract(const Duration(days: 1)), endDate: excStart.add(const Duration(days: 10)));
        final seriesList = afterInstances.where((e) => e.eventId == eventId || e.title == 'EXCEPTION - Only This').toList();
        _log('Reloaded ${seriesList.length} total occurrences (series + exception):');
        for (final e in seriesList) {
          _log('   -> title="${e.title}", start=${e.start}, loc="${e.location}"');
        }
        _assert(seriesList.length == 5, 'Total instances count must remain 5');

        final baseList = seriesList.where((e) => e.title == 'Recurring Base Event').toList();
        final excList = seriesList.where((e) => e.title == 'EXCEPTION - Only This').toList();
        _assert(baseList.length == 4, 'Expected 4 base instances');
        _assert(excList.length == 1, 'Expected exactly 1 exception instance');

        final excEvent = excList.first;
        _assert(excEvent.start!.hour == 12 && excEvent.start!.day == originalDay, 'Exception time/day mismatch');
        _assert(excEvent.location == 'VIP Lounge', 'Exception location mismatch');

        await _safeDeleteEvent(calendarIdA, eventId);
        if (excEvent.eventId != null && excEvent.eventId != eventId) {
          await _safeDeleteEvent(calendarIdA, excEvent.eventId);
        }
        _recordResult(suite5, '5.1 Edit Only This Instance', true, 'Single occurrence detached and modified without altering remaining 4 occurrences');
      } catch (e) {
        _recordResult(suite5, '5.1 Edit Only This Instance', false, 'Exception edit failed', e.toString());
      }

      // =======================================================================
      // SUITE 6: RECURRING EVENT: "EDIT THIS AND FUTURE" (SERIES SPLITTING)
      // =======================================================================
      const suite6 = '6. Recurring Event: Edit This & Future (Splitting)';
      final splitStart = tz.TZDateTime(localLoc, 2027, 1, 1, 10, 0, 0);

      _log('\n--- SUITE 6: Recurring Event: Edit This and Future (Series Splitting) ---');
      setState(() => _currentStepName = '6.1 Testing Edit This and Future Instances...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Split Series Orig'
          ..start = splitStart
          ..end = splitStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);

        final createRes = await _plugin.createOrUpdateEvent(event);
        _assert(createRes?.isSuccess == true, 'Create split series failed');
        final origEventId = createRes!.data!;
        _log('Created recurring series ID: $origEventId');

        await Future.delayed(const Duration(seconds: 1));
        final instances = await _loadInstances(calendarIdA, startDate: splitStart.subtract(const Duration(days: 1)), endDate: splitStart.add(const Duration(days: 10)), eventId: origEventId);
        _assert(instances.length == 5, 'Expected 5 initial instances');
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Split at 3rd instance (index 2)
        final targetInstance = instances[2];
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;

        _log('Splitting series from 3rd instance (start=${targetInstance.start}) with updateFollowingInstances=true...');
        targetInstance.title = 'Split Series - Future (Updated)';
        targetInstance.start = targetInstance.start!.add(const Duration(hours: 3)); // 13:00
        targetInstance.end = targetInstance.end!.add(const Duration(hours: 3)); // 14:00

        final splitRes = await _plugin.createOrUpdateEvent(
          targetInstance,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: true,
        );
        _assert(splitRes?.isSuccess == true && splitRes?.data != null, 'Split this & future failed: ${splitRes?.errors.map((e) => e.errorMessage)}');
        final newSeriesId = splitRes!.data!;
        _log('New split series event ID: $newSeriesId');

        await Future.delayed(const Duration(seconds: 2));
        final afterA = await _loadInstances(calendarIdA, startDate: splitStart.subtract(const Duration(days: 1)), endDate: splitStart.add(const Duration(days: 10)));
        final origRemaining = afterA.where((e) => e.eventId == origEventId).toList();
        final splitRemaining = afterA.where((e) => e.eventId == newSeriesId).toList();

        _log('Original series instances remaining (${origRemaining.length}): ${origRemaining.map((e) => '${e.title} at ${e.start}').toList()}');
        _log('New split series instances (${splitRemaining.length}): ${splitRemaining.map((e) => '${e.title} at ${e.start}').toList()}');

        _assert(origRemaining.length == 2, 'Expected 2 instances in original series, got ${origRemaining.length}');
        _assert(splitRemaining.length == 3, 'Expected 3 instances in split series, got ${splitRemaining.length}');
        for (final inst in splitRemaining) {
          _assert(inst.title == 'Split Series - Future (Updated)', 'Split instance title mismatch');
          _assert(inst.start!.hour == 13, 'Split instance hour mismatch');
        }

        await _safeDeleteEvent(calendarIdA, origEventId);
        await _safeDeleteEvent(calendarIdA, newSeriesId);
        _recordResult(suite6, '6.1 Edit This & Future', true, 'Split series into 2 original and 3 future updated occurrences');
      } catch (e) {
        _recordResult(suite6, '6.1 Edit This & Future', false, 'Series split failed', e.toString());
      }

      // =======================================================================
      // SUITE 7: DELETION SCENARIOS
      // =======================================================================
      const suite7 = '7. Deletion Scenarios';
      final delStart = tz.TZDateTime(localLoc, 2027, 2, 1, 10, 0, 0);

      _log('\n--- SUITE 7: Deletion Scenarios ---');

      // 7.1 Delete single instance
      setState(() => _currentStepName = '7.1 Testing Delete Single Instance...');
      _log('Step 7.1: Testing "Delete Only This Instance" on 3rd instance...');
      try {
        final event = Event(calendarIdA)
          ..title = 'Delete Single'
          ..start = delStart
          ..end = delStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);
        final res = await _plugin.createOrUpdateEvent(event);
        final eventId = res!.data!;

        await Future.delayed(const Duration(seconds: 1));
        final list = await _loadInstances(calendarIdA, startDate: delStart.subtract(const Duration(days: 1)), endDate: delStart.add(const Duration(days: 10)), eventId: eventId);
        list.sort((a, b) => a.start!.compareTo(b.start!));
        final target = list[2];
        _log('Deleting instance at ${target.start} with deleteFollowingInstances=false...');

        final delRes = await _plugin.deleteEventInstance(
          calendarIdA,
          eventId,
          target.start!.millisecondsSinceEpoch,
          target.end!.millisecondsSinceEpoch,
          false,
        );
        _assert(delRes.isSuccess && delRes.data == true, 'Delete single instance failed');

        await Future.delayed(const Duration(seconds: 2));
        final after = await _loadInstances(calendarIdA, startDate: delStart.subtract(const Duration(days: 1)), endDate: delStart.add(const Duration(days: 10)), eventId: eventId);
        _log('Instances remaining after deleting 1 occurrence (${after.length}): ${after.map((e) => e.start.toString()).toList()}');
        _assert(after.length == 4, 'Expected 4 remaining instances after deleting 1');
        _assert(!after.any((e) => e.start!.millisecondsSinceEpoch == target.start!.millisecondsSinceEpoch), 'Deleted instance still exists');

        await _safeDeleteEvent(calendarIdA, eventId);
        _recordResult(suite7, '7.1 Delete Single Instance', true, 'Deleted 3rd instance; remaining 4 intact');
      } catch (e) {
        _recordResult(suite7, '7.1 Delete Single Instance', false, 'Delete single instance failed', e.toString());
      }

      // 7.2 Delete this and future
      setState(() => _currentStepName = '7.2 Testing Delete This and Future...');
      _log('Step 7.2: Testing "Delete This and Future Instances" starting from 3rd instance...');
      try {
        final start = delStart.add(const Duration(days: 10));
        final event = Event(calendarIdA)
          ..title = 'Delete Future'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);
        final res = await _plugin.createOrUpdateEvent(event);
        final eventId = res!.data!;

        await Future.delayed(const Duration(seconds: 1));
        final list = await _loadInstances(calendarIdA, startDate: start.subtract(const Duration(days: 1)), endDate: start.add(const Duration(days: 10)), eventId: eventId);
        list.sort((a, b) => a.start!.compareTo(b.start!));
        final target = list[2];
        _log('Deleting from instance at ${target.start} onward with deleteFollowingInstances=true...');

        final delRes = await _plugin.deleteEventInstance(
          calendarIdA,
          eventId,
          target.start!.millisecondsSinceEpoch,
          target.end!.millisecondsSinceEpoch,
          true,
        );
        _assert(delRes.isSuccess && delRes.data == true, 'Delete this and future failed');

        await Future.delayed(const Duration(seconds: 2));
        final after = await _loadInstances(calendarIdA, startDate: start.subtract(const Duration(days: 1)), endDate: start.add(const Duration(days: 10)), eventId: eventId);
        _log('Instances remaining after deleting future occurrences (${after.length}): ${after.map((e) => e.start.toString()).toList()}');
        _assert(after.length == 2, 'Expected exactly 2 remaining instances, got ${after.length}');

        await _safeDeleteEvent(calendarIdA, eventId);
        _recordResult(suite7, '7.2 Delete This and Future', true, 'Deleted from 3rd instance onward; first 2 intact');
      } catch (e) {
        _recordResult(suite7, '7.2 Delete This and Future', false, 'Delete this and future failed', e.toString());
      }

      // 7.3 Delete all & neighbor safety
      setState(() => _currentStepName = '7.3 Testing Delete All & Neighbor Safety...');
      _log('Step 7.3: Creating neighbor normal event + recurring series, deleting recurring series, verifying neighbor remains...');
      try {
        final start = delStart.add(const Duration(days: 20));
        final normal = Event(calendarIdA)
          ..title = 'Neighbor Normal'
          ..start = start
          ..end = start.add(const Duration(hours: 1));
        final normRes = await _plugin.createOrUpdateEvent(normal);
        final normId = normRes!.data!;

        final recurring = Event(calendarIdA)
          ..title = 'Recurring To Delete'
          ..start = start.add(const Duration(hours: 2))
          ..end = start.add(const Duration(hours: 3))
          ..recurrenceRule = RecurrenceRule(frequency: Frequency.daily, count: 5);
        final recRes = await _plugin.createOrUpdateEvent(recurring);
        final recId = recRes!.data!;

        await Future.delayed(const Duration(seconds: 1));
        // Delete all instances of recurring event
        _log('Calling deleteEvent(calendarId, $recId)...');
        final delRec = await _plugin.deleteEvent(calendarIdA, recId);
        _assert(delRec.isSuccess && delRec.data == true, 'Delete all recurring failed');

        await Future.delayed(const Duration(seconds: 2));
        final after = await _loadInstances(calendarIdA, startDate: start.subtract(const Duration(days: 1)), endDate: start.add(const Duration(days: 10)));
        _log('Remaining events in calendar after deleting recurring series: ${after.map((e) => e.title).toList()}');
        _assert(!after.any((e) => e.eventId == recId), 'Recurring event not fully deleted');
        _assert(after.any((e) => e.eventId == normId), 'Neighboring normal event was deleted!');

        // Delete normal event
        await _safeDeleteEvent(calendarIdA, normId);
        _recordResult(suite7, '7.3 Delete All & Neighbor Safety', true, 'Deleted recurring series without affecting neighbor normal event');
      } catch (e) {
        _recordResult(suite7, '7.3 Delete All & Neighbor Safety', false, 'Delete all failed', e.toString());
      }

      // =======================================================================
      // SUITE 8: FINAL VERIFICATION & CALENDAR DELETION
      // =======================================================================
      const suite8 = '8. Final Cleanup & Calendar Deletion';
      _log('\n--- SUITE 8: Final Verification & Calendar Deletion ---');
      setState(() => _currentStepName = '8.1 Verifying all events deleted and deleting calendars...');
      try {
        final now = tz.TZDateTime.now(tz.local);
        final eventsA = await _loadInstances(calendarIdA, startDate: now.subtract(const Duration(days: 100)), endDate: now.add(const Duration(days: 400)));
        final eventsB = await _loadInstances(calendarIdB, startDate: now.subtract(const Duration(days: 100)), endDate: now.add(const Duration(days: 400)));

        _log('Remaining events in Cal A: ${eventsA.map((e) => e.title).toList()}');
        _log('Remaining events in Cal B: ${eventsB.map((e) => e.title).toList()}');

        _assert(eventsA.isEmpty, 'Calendar A has remaining events: ${eventsA.map((e) => e.title)}');
        _assert(eventsB.isEmpty, 'Calendar B has remaining events: ${eventsB.map((e) => e.title)}');

        // Delete Calendar A and Calendar B
        final targetIdA = calendarIdA;
        final targetIdB = calendarIdB;

        _log('Deleting Calendar A ($targetIdA)...');
        final delA = await _plugin.deleteCalendar(targetIdA!);
        _assert(delA.isSuccess && delA.data == true, 'Failed to delete Calendar A');
        calendarIdA = null;

        _log('Deleting Calendar B ($targetIdB)...');
        final delB = await _plugin.deleteCalendar(targetIdB!);
        _assert(delB.isSuccess && delB.data == true, 'Failed to delete Calendar B');
        calendarIdB = null;

        await Future.delayed(const Duration(seconds: 1));
        final cals = await _plugin.retrieveCalendars();
        _log('Calendars remaining on device: ${cals.data?.map((c) => "${c.name} (${c.id})").toList()}');
        _assert(!cals.data!.any((c) => c.id == targetIdA), 'Cal A ($targetIdA) still exists');
        _assert(!cals.data!.any((c) => c.id == targetIdB), 'Cal B ($targetIdB) still exists');

        // Also clean up any lingering test calendars by name if any exist from older interrupted runs
        for (final c in cals.data!) {
          if ((c.name == 'Comprehensive Test Cal A' || c.name == 'Comprehensive Test Cal B') && c.id != null) {
            _log('Cleanup lingering test calendar from previous runs: ${c.name} (${c.id})');
            await _plugin.deleteCalendar(c.id!);
          }
        }

        _recordResult(suite8, '8.1 Calendar Deletion', true, 'All events and test calendars cleanly removed from device');
      } catch (e) {
        _recordResult(suite8, '8.1 Calendar Deletion', false, 'Final cleanup failed', e.toString());
      }

    } catch (e) {
      _log('Fatal error in test runner: $e');
    } finally {
      // Ensure test calendars are removed
      if (calendarIdA != null) {
        await _plugin.deleteCalendar(calendarIdA);
      }
      if (calendarIdB != null) {
        await _plugin.deleteCalendar(calendarIdB);
      }
      setState(() {
        _isRunning = false;
        _currentStepName = _failedCount == 0 ? 'All tests passed successfully! 🎉' : 'Completed with $_failedCount failures';
      });
      _log('=== TEST RUN FINISHED: $_passedCount PASSED, $_failedCount FAILED ===\n');
      _scrollToBottom();
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Integration Test Runner'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_all),
            tooltip: 'Copy All Logs to Clipboard',
            onPressed: _results.isEmpty ? null : _copyLogsToClipboard,
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Clear Results',
            onPressed: _isRunning
                ? null
                : () {
                    setState(() {
                      _results.clear();
                      _logBuffer.clear();
                      _passedCount = 0;
                      _failedCount = 0;
                      _currentStepName = '';
                    });
                  },
          ),
        ],
      ),
      body: Column(
        children: [
          // Summary Header Card
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(16),
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              border: Border(
                bottom: BorderSide(
                  color: Theme.of(context).dividerColor,
                ),
              ),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'Comprehensive Platform Test',
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                    ),
                    Row(
                      children: [
                        Chip(
                          avatar: const Icon(Icons.check_circle, color: Colors.green, size: 18),
                          label: Text('$_passedCount Passed'),
                          backgroundColor: Colors.green.withValues(alpha: 0.15),
                        ),
                        const SizedBox(width: 8),
                        Chip(
                          avatar: const Icon(Icons.error, color: Colors.red, size: 18),
                          label: Text('$_failedCount Failed'),
                          backgroundColor: Colors.red.withValues(alpha: 0.15),
                        ),
                      ],
                    ),
                  ],
                ),
                const SizedBox(height: 8),
                if (_currentStepName.isNotEmpty)
                  Row(
                    children: [
                      if (_isRunning)
                        const Padding(
                          padding: EdgeInsets.only(right: 8.0),
                          child: SizedBox(
                            width: 16,
                            height: 16,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        ),
                      Expanded(
                        child: Text(
                          _currentStepName,
                          style: TextStyle(
                            color: _isRunning
                                ? Theme.of(context).colorScheme.primary
                                : (_failedCount == 0 ? Colors.green : Colors.red),
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                const SizedBox(height: 12),
                Row(
                  children: [
                    Expanded(
                      flex: 3,
                      child: ElevatedButton.icon(
                        onPressed: _isRunning ? null : _runAllTests,
                        icon: _isRunning
                            ? const SizedBox(
                                width: 18,
                                height: 18,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : const Icon(Icons.play_arrow),
                        label: Text(
                          _isRunning ? 'Running Tests...' : 'Run All Integration Tests',
                          style: const TextStyle(fontWeight: FontWeight.bold),
                        ),
                        style: ElevatedButton.styleFrom(
                          padding: const EdgeInsets.symmetric(vertical: 12),
                          backgroundColor: Theme.of(context).colorScheme.primary,
                          foregroundColor: Colors.white,
                        ),
                      ),
                    ),
                    if (_results.isNotEmpty) ...[
                      const SizedBox(width: 8),
                      Expanded(
                        flex: 2,
                        child: OutlinedButton.icon(
                          onPressed: _copyLogsToClipboard,
                          icon: const Icon(Icons.copy, size: 18),
                          label: const Text('Copy Logs'),
                          style: OutlinedButton.styleFrom(
                            padding: const EdgeInsets.symmetric(vertical: 12),
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ],
            ),
          ),

          // Test Steps List
          Expanded(
            child: _results.isEmpty
                ? Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          Icons.science_outlined,
                          size: 64,
                          color: Theme.of(context).disabledColor,
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Tap "Run All Integration Tests" to start',
                          style: TextStyle(color: Theme.of(context).disabledColor, fontSize: 16),
                        ),
                        const SizedBox(height: 8),
                        Text(
                          'Tests calendar CRUD, events, recurrence rules,\nfield-by-field updates, splits, and deletions.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: Theme.of(context).disabledColor, fontSize: 12),
                        ),
                      ],
                    ),
                  )
                : ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.all(12),
                    itemCount: _results.length,
                    itemBuilder: (context, index) {
                      final item = _results[index];
                      return Card(
                        margin: const EdgeInsets.only(bottom: 8),
                        elevation: 1,
                        shape: RoundedRectangleBorder(
                          borderRadius: BorderRadius.circular(8),
                          side: BorderSide(
                            color: item.isSuccess ? Colors.green.withValues(alpha: 0.3) : Colors.red.withValues(alpha: 0.5),
                          ),
                        ),
                        child: Padding(
                          padding: const EdgeInsets.all(12),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Icon(
                                    item.isSuccess ? Icons.check_circle : Icons.cancel,
                                    color: item.isSuccess ? Colors.green : Colors.red,
                                    size: 20,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      item.stepName,
                                      style: const TextStyle(fontWeight: FontWeight.bold),
                                    ),
                                  ),
                                  Container(
                                    padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                                    decoration: BoxDecoration(
                                      color: item.isSuccess ? Colors.green.withValues(alpha: 0.1) : Colors.red.withValues(alpha: 0.1),
                                      borderRadius: BorderRadius.circular(4),
                                    ),
                                    child: Text(
                                      item.isSuccess ? 'PASS' : 'FAIL',
                                      style: TextStyle(
                                        color: item.isSuccess ? Colors.green : Colors.red,
                                        fontWeight: FontWeight.bold,
                                        fontSize: 11,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 6),
                              Text(
                                item.message,
                                style: TextStyle(
                                  color: Theme.of(context).textTheme.bodyMedium?.color,
                                  fontSize: 13,
                                ),
                              ),
                              if (item.errorDetails != null && item.errorDetails!.isNotEmpty) ...[
                                const SizedBox(height: 6),
                                Container(
                                  width: double.infinity,
                                  padding: const EdgeInsets.all(8),
                                  decoration: BoxDecoration(
                                    color: Colors.red.withValues(alpha: 0.08),
                                    borderRadius: BorderRadius.circular(4),
                                  ),
                                  child: Text(
                                    item.errorDetails!,
                                    style: const TextStyle(
                                      color: Colors.red,
                                      fontSize: 12,
                                      fontFamily: 'monospace',
                                    ),
                                  ),
                                ),
                              ],
                            ],
                          ),
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
