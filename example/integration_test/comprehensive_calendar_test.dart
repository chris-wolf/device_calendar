import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:flutter_timezone/flutter_timezone.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  group('Device Calendar Comprehensive Integration Test Suite', () {
    late DeviceCalendarPlugin deviceCalendarPlugin;
    String? calendarIdA;
    String? calendarIdB;
    EventColor? androidEventColorA;
    EventColor? androidEventColorB;

    setUpAll(() async {
      deviceCalendarPlugin = DeviceCalendarPlugin();

      try {
        final tzInfo = await FlutterTimezone.getLocalTimezone();
        final loc = tz.timeZoneDatabase.locations[tzInfo.identifier];
        if (loc != null) {
          tz.setLocalLocation(loc);
        }
      } catch (e) {
        print('Could not set local timezone: $e');
      }

      // 1. Request calendar permissions
      final permissionsResult = await deviceCalendarPlugin.requestPermissions();
      expect(permissionsResult.isSuccess, true,
          reason: 'requestPermissions should succeed');
      expect(permissionsResult.data, true,
          reason: 'Calendar permissions must be granted for integration tests');

      final hasPermResult = await deviceCalendarPlugin.hasPermissions();
      expect(hasPermResult.isSuccess, true);
      expect(hasPermResult.data, true);

      // 2. Create primary test calendar A
      final createCalAResult = await deviceCalendarPlugin.createCalendar(
        'Comprehensive Test Calendar A',
        calendarColor: Colors.blue,
        localAccountName: 'integration_test_account_a',
      );
      expect(createCalAResult.isSuccess, true,
          reason: 'Should successfully create Calendar A: ${createCalAResult.errors.map((e) => e.errorMessage).toList()}');
      calendarIdA = createCalAResult.data;
      expect(calendarIdA, isNotNull);
      print('LOG_TEST: Created primary test calendar A: $calendarIdA');

      // 3. Create secondary test calendar B (for move/split tests across calendars)
      final createCalBResult = await deviceCalendarPlugin.createCalendar(
        'Comprehensive Test Calendar B',
        calendarColor: Colors.green,
        localAccountName: 'integration_test_account_b',
      );
      expect(createCalBResult.isSuccess, true,
          reason: 'Should successfully create Calendar B: ${createCalBResult.errors.map((e) => e.errorMessage).toList()}');
      calendarIdB = createCalBResult.data;
      expect(calendarIdB, isNotNull);
      print('LOG_TEST: Created secondary test calendar B: $calendarIdB');

      // 4. Retrieve event colors if on Android
      if (Platform.isAndroid) {
        final calendarsResult = await deviceCalendarPlugin.retrieveCalendars();
        expect(calendarsResult.isSuccess, true);
        final calA = calendarsResult.data?.firstWhere((c) => c.id == calendarIdA);
        final calB = calendarsResult.data?.firstWhere((c) => c.id == calendarIdB);

        if (calA != null) {
          final colorsA = await deviceCalendarPlugin.retrieveEventColors(calA);
          if (colorsA != null && colorsA.isNotEmpty) {
            androidEventColorA = colorsA.first;
            print('LOG_TEST: Android Event Color A: ${androidEventColorA?.color}');
          }
        }
        if (calB != null) {
          final colorsB = await deviceCalendarPlugin.retrieveEventColors(calB);
          if (colorsB != null && colorsB.isNotEmpty) {
            androidEventColorB = colorsB.first;
            print('LOG_TEST: Android Event Color B: ${androidEventColorB?.color}');
          }
        }
      }
    });

    tearDownAll(() async {
      print('LOG_TEST: Cleaning up test calendars...');
      if (calendarIdA != null) {
        final deleteResultA = await deviceCalendarPlugin.deleteCalendar(calendarIdA!);
        print('LOG_TEST: Deleted Calendar A result: ${deleteResultA.data}');
      }
      if (calendarIdB != null) {
        final deleteResultB = await deviceCalendarPlugin.deleteCalendar(calendarIdB!);
        print('LOG_TEST: Deleted Calendar B result: ${deleteResultB.data}');
      }
    });

    // Helper to safely delete an event without failing if it was already deleted/split
    Future<void> safeDeleteEvent(String? calId, String? eventId) async {
      if (calId == null || eventId == null) return;
      try {
        await deviceCalendarPlugin.deleteEvent(calId, eventId);
      } catch (e) {
        print('LOG_TEST: safeDeleteEvent notice: $e');
      }
    }

    // Helper to retrieve an event by ID from a calendar
    Future<Event?> loadEventFromDevice(String? calId, String? eventId) async {
      final now = tz.TZDateTime.now(tz.local);
      final retrieveParams = RetrieveEventsParams(
        startDate: now.subtract(const Duration(days: 60)),
        endDate: now.add(const Duration(days: 365)),
        eventIds: eventId != null ? [eventId] : null,
      );

      final result = await deviceCalendarPlugin.retrieveEvents(calId, retrieveParams);
      if (!result.isSuccess || result.data == null) {
        return null;
      }
      final matches = result.data!.where((e) => e.eventId == eventId).toList();
      return matches.isNotEmpty ? matches.first : null;
    }

    // Helper to retrieve all instances in a given date range
    Future<List<Event>> loadInstancesFromDevice(
      String? calId, {
      required tz.TZDateTime startDate,
      required tz.TZDateTime endDate,
      String? eventId,
    }) async {
      final retrieveParams = RetrieveEventsParams(
        startDate: startDate,
        endDate: endDate,
        eventIds: eventId != null ? [eventId] : null,
      );

      final result = await deviceCalendarPlugin.retrieveEvents(calId, retrieveParams);
      expect(result.isSuccess, true,
          reason: 'Failed to retrieve events: ${result.errors.map((e) => e.errorMessage).toList()}');
      final list = result.data?.toList() ?? [];
      if (eventId != null) {
        return list.where((e) => e.eventId == eventId).toList();
      }
      return list;
    }

    // =========================================================================
    // 1. CALENDAR VERIFICATION AND COLOR UPDATES
    // =========================================================================
    group('1. Calendar Verification and Management', () {
      test('Verify created calendars exist and are writable', () async {
        final calendarsResult = await deviceCalendarPlugin.retrieveCalendars();
        expect(calendarsResult.isSuccess, true);
        expect(calendarsResult.data, isNotNull);

        final calA = calendarsResult.data!.firstWhere((c) => c.id == calendarIdA);
        expect(calA.name, 'Comprehensive Test Calendar A');
        expect(calA.isReadOnly, false);

        final calB = calendarsResult.data!.firstWhere((c) => c.id == calendarIdB);
        expect(calB.name, 'Comprehensive Test Calendar B');
        expect(calB.isReadOnly, false);
      });

      test('Update calendar color and verify change', () async {
        final calendarsResult = await deviceCalendarPlugin.retrieveCalendars();
        expect(calendarsResult.isSuccess, true);
        final calA = calendarsResult.data!.firstWhere((c) => c.id == calendarIdA);

        final updated = await deviceCalendarPlugin.updateCalendarColor(calA, color: Colors.purple);
        print('LOG_TEST: Update calendar color result: $updated');
        if (updated) {
          final refreshedResult = await deviceCalendarPlugin.retrieveCalendars();
          final refreshedCalA = refreshedResult.data!.firstWhere((c) => c.id == calendarIdA);
          expect(refreshedCalA.color, isNotNull);
        }
      });
    });

    // =========================================================================
    // 2. NORMAL (NON-RECURRING) EVENT - STEP-BY-STEP FIELD UPDATES & RELOAD
    // =========================================================================
    group('2. Normal Event: Comprehensive Field-by-Field Update & Reload Checks', () {
      String? normalEventId;
      final localLocation = tz.local;
      final baseDate = tz.TZDateTime(localLocation, 2026, 9, 1, 10, 0, 0);

      test('2.1 Create normal event with initial fields and verify reload', () async {
        final initialEvent = Event(calendarIdA)
          ..title = 'Normal Event Initial Title'
          ..description = 'Initial Description for normal event'
          ..start = baseDate
          ..end = baseDate.add(const Duration(hours: 1))
          ..allDay = false
          ..location = 'Conference Room 101'
          ..url = Uri.parse('https://example.com/calendar/event1')
          ..availability = Availability.Busy
          ..status = EventStatus.Confirmed
          ..reminders = [Reminder(minutes: 15)];

        if (Platform.isAndroid) {
          initialEvent.attendees = [
            Attendee(
              name: 'Alice Johnson',
              emailAddress: 'alice@example.com',
              role: AttendeeRole.Required,
              isOrganiser: false,
            ),
          ];
          if (androidEventColorA != null) {
            initialEvent.updateEventColor(androidEventColorA);
          }
        }

        final createResult = await deviceCalendarPlugin.createOrUpdateEvent(initialEvent);
        expect(createResult?.isSuccess, true,
            reason: 'Failed to create normal event: ${createResult?.errors.map((e) => e.errorMessage).toList()}');
        normalEventId = createResult!.data!;
        expect(normalEventId, isNotEmpty);
        print('LOG_TEST: Created normal event with ID: $normalEventId');

        await Future.delayed(const Duration(milliseconds: 500));

        // Load back from device and verify all fields
        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded, isNotNull);
        expect(loaded!.eventId, normalEventId);
        expect(loaded.title, 'Normal Event Initial Title');
        expect(loaded.description, 'Initial Description for normal event');
        expect(loaded.location, 'Conference Room 101');
        expect(loaded.url?.toString(), 'https://example.com/calendar/event1');
        expect(loaded.start?.millisecondsSinceEpoch, baseDate.millisecondsSinceEpoch);
        expect(loaded.end?.millisecondsSinceEpoch, baseDate.add(const Duration(hours: 1)).millisecondsSinceEpoch);
        expect(loaded.allDay, false);
        expect(loaded.availability, Availability.Busy);
        if (Platform.isAndroid && loaded.status != null) {
          expect(loaded.status, EventStatus.Confirmed);
        }
        if (loaded.reminders != null && loaded.reminders!.isNotEmpty) {
          expect(loaded.reminders!.any((r) => r.minutes == 15), true);
        }
        print('LOG_TEST: Initial event verification passed');
      });

      test('2.2 Update Title and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.title = 'Normal Event Updated Title v2';
        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.title, 'Normal Event Updated Title v2');
        // Other fields preserved
        expect(loaded?.description, 'Initial Description for normal event');
        expect(loaded?.location, 'Conference Room 101');
        print('LOG_TEST: Title update verification passed');
      });

      test('2.3 Update Description and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.description = 'Updated Description v2 - full details added.';
        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.description, 'Updated Description v2 - full details added.');
        expect(loaded?.title, 'Normal Event Updated Title v2');
        print('LOG_TEST: Description update verification passed');
      });

      test('2.4 Update Location and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.location = 'Building B, Room 404, Cupertino, CA';
        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.location, 'Building B, Room 404, Cupertino, CA');
        print('LOG_TEST: Location update verification passed');
      });

      test('2.5 Update URL and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.url = Uri.parse('https://example.com/calendar/updated_meeting_link');
        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.url?.toString(), 'https://example.com/calendar/updated_meeting_link');
        print('LOG_TEST: URL update verification passed');
      });

      test('2.6 Update Start & End Times and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        final newStart = baseDate.add(const Duration(hours: 4)); // 14:00
        final newEnd = baseDate.add(const Duration(hours: 5, minutes: 30)); // 15:30
        event!.start = newStart;
        event.end = newEnd;

        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.start?.millisecondsSinceEpoch, newStart.millisecondsSinceEpoch);
        expect(loaded?.end?.millisecondsSinceEpoch, newEnd.millisecondsSinceEpoch);
        expect(loaded?.start?.hour, 14);
        expect(loaded?.end?.hour, 15);
        expect(loaded?.end?.minute, 30);
        print('LOG_TEST: Time update verification passed');
      });

      test('2.6.1 Move Date Forward (+5 Days) and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Move from Sep 1 14:00 to Sep 6 14:00
        final shiftedStart = event!.start!.add(const Duration(days: 5));
        final shiftedEnd = event.end!.add(const Duration(days: 5));
        event.start = shiftedStart;
        event.end = shiftedEnd;

        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.start?.year, 2026);
        expect(loaded?.start?.month, 9);
        expect(loaded?.start?.day, 6);
        expect(loaded?.start?.hour, 14);
        expect(loaded?.end?.day, 6);
        expect(loaded?.end?.hour, 15);
        expect(loaded?.end?.minute, 30);
        print('LOG_TEST: Forward date shift (+5 days) verification passed');
      });

      test('2.6.2 Move Date Backward Across Month Boundary (-12 Days to Aug 25) and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Move from Sep 6 to Aug 25 (12 days backward)
        final shiftedStart = tz.TZDateTime(localLocation, 2026, 8, 25, 11, 0, 0);
        final shiftedEnd = tz.TZDateTime(localLocation, 2026, 8, 25, 12, 30, 0);
        event!.start = shiftedStart;
        event.end = shiftedEnd;

        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.start?.year, 2026);
        expect(loaded?.start?.month, 8);
        expect(loaded?.start?.day, 25);
        expect(loaded?.start?.hour, 11);
        expect(loaded?.end?.month, 8);
        expect(loaded?.end?.day, 25);
        expect(loaded?.end?.hour, 12);
        expect(loaded?.end?.minute, 30);
        print('LOG_TEST: Cross-month backward date shift verification passed');
      });

      test('2.6.3 Move Date Across Year Boundary (Aug 2026 to Jan 2027) and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Move to Jan 5, 2027 10:00 - 11:30
        final shiftedStart = tz.TZDateTime(localLocation, 2027, 1, 5, 10, 0, 0);
        final shiftedEnd = tz.TZDateTime(localLocation, 2027, 1, 5, 11, 30, 0);
        event!.start = shiftedStart;
        event.end = shiftedEnd;

        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.start?.year, 2027);
        expect(loaded?.start?.month, 1);
        expect(loaded?.start?.day, 5);
        expect(loaded?.start?.hour, 10);
        expect(loaded?.end?.year, 2027);
        expect(loaded?.end?.month, 1);
        expect(loaded?.end?.day, 5);
        expect(loaded?.end?.hour, 11);
        expect(loaded?.end?.minute, 30);
        print('LOG_TEST: Cross-year date shift verification passed');
      });

      test('2.6.4 Update Duration only (extend to 4 hours) and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Extend duration: Keep start at Jan 5 10:00, extend end to 14:00 (4 hrs)
        final extendedEnd = event!.start!.add(const Duration(hours: 4));
        event.end = extendedEnd;

        final updateResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.start?.day, 5);
        expect(loaded?.start?.hour, 10);
        expect(loaded?.end?.hour, 14);
        expect(loaded?.end?.minute, 0);
        print('LOG_TEST: Duration update verification passed');
      });

      test('2.7 Update AllDay flag to true and back to false', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Turn allDay on
        event!.allDay = true;
        final updateResult1 = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(updateResult1?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded1 = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded1?.allDay, true);

        // Turn allDay off with specific start/end
        loaded1!.allDay = false;
        loaded1.start = baseDate.add(const Duration(hours: 3));
        loaded1.end = baseDate.add(const Duration(hours: 4));
        final updateResult2 = await deviceCalendarPlugin.createOrUpdateEvent(loaded1);
        expect(updateResult2?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded2 = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded2?.allDay, false);
        expect(loaded2?.start?.hour, 13);
        print('LOG_TEST: AllDay update verification passed');
      });

      test('2.7.1 All-Day Event Date Shift and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Set as allDay on Jan 10, 2027
        final allDayStart = tz.TZDateTime(localLocation, 2027, 1, 10, 0, 0, 0);
        final allDayEnd = tz.TZDateTime(localLocation, 2027, 1, 10, 23, 59, 59);
        event!.allDay = true;
        event.start = allDayStart;
        event.end = allDayEnd;

        final res1 = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res1?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded1 = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded1?.allDay, true);
        expect(loaded1?.start?.day, 10);
        expect(loaded1?.start?.month, 1);
        expect(loaded1?.start?.year, 2027);

        // Shift allDay to Jan 15, 2027
        final shiftedAllDayStart = tz.TZDateTime(localLocation, 2027, 1, 15, 0, 0, 0);
        final shiftedAllDayEnd = tz.TZDateTime(localLocation, 2027, 1, 15, 23, 59, 59);
        loaded1!.start = shiftedAllDayStart;
        loaded1.end = shiftedAllDayEnd;

        final res2 = await deviceCalendarPlugin.createOrUpdateEvent(loaded1);
        expect(res2?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded2 = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded2?.allDay, true);
        expect(loaded2?.start?.day, 15);
        expect(loaded2?.start?.month, 1);
        expect(loaded2?.start?.year, 2027);
        print('LOG_TEST: All-Day date shift verification passed');
      });

      test('2.7.2 Multi-Day All-Day Date Range Modification and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Multi-day allDay: Jan 20 to Jan 23
        final multiStart = tz.TZDateTime(localLocation, 2027, 1, 20, 0, 0, 0);
        final multiEnd = tz.TZDateTime(localLocation, 2027, 1, 23, 23, 59, 59);
        event!.allDay = true;
        event.start = multiStart;
        event.end = multiEnd;

        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.allDay, true);
        expect(loaded?.start?.day, 20);
        expect(loaded?.end?.day, 23);
        print('LOG_TEST: Multi-day all-day date modification verification passed');
      });

      test('2.7.3 Convert All-Day Event to Timed Event with new Date and Time', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Convert back to timed event on Jan 25, 2027 from 09:15 to 11:45
        final timedStart = tz.TZDateTime(localLocation, 2027, 1, 25, 9, 15, 0);
        final timedEnd = tz.TZDateTime(localLocation, 2027, 1, 25, 11, 45, 0);
        event!.allDay = false;
        event.start = timedStart;
        event.end = timedEnd;

        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));

        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.allDay, false);
        expect(loaded?.start?.year, 2027);
        expect(loaded?.start?.month, 1);
        expect(loaded?.start?.day, 25);
        expect(loaded?.start?.hour, 9);
        expect(loaded?.start?.minute, 15);
        expect(loaded?.end?.hour, 11);
        expect(loaded?.end?.minute, 45);
        print('LOG_TEST: All-day to timed conversion with new date/time verification passed');
      });

      test('2.8 Update Availability and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Free
        event!.availability = Availability.Free;
        var res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        var loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.availability, Availability.Free);

        // Tentative
        loaded!.availability = Availability.Tentative;
        res = await deviceCalendarPlugin.createOrUpdateEvent(loaded);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.availability, Availability.Tentative);

        // Busy
        loaded!.availability = Availability.Busy;
        res = await deviceCalendarPlugin.createOrUpdateEvent(loaded);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.availability, Availability.Busy);
        print('LOG_TEST: Availability update verification passed');
      });

      test('2.9 Update Status and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.status = EventStatus.Tentative;
        var res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        var loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        if (Platform.isAndroid && loaded?.status != null) {
          expect(loaded?.status, EventStatus.Tentative);
        }

        loaded!.status = EventStatus.Confirmed;
        res = await deviceCalendarPlugin.createOrUpdateEvent(loaded);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        if (Platform.isAndroid && loaded?.status != null) {
          expect(loaded?.status, EventStatus.Confirmed);
        }
        print('LOG_TEST: Status update verification passed');
      });

      test('2.10 Update Reminders and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Set multiple reminders: 30 mins and 60 mins
        event!.reminders = [Reminder(minutes: 30), Reminder(minutes: 60)];
        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        if (loaded?.reminders != null && loaded!.reminders!.isNotEmpty) {
          expect(loaded.reminders!.any((r) => r.minutes == 30), true);
          expect(loaded.reminders!.any((r) => r.minutes == 60), true);
        }
        print('LOG_TEST: Reminders update verification passed');
      });

      test('2.11 Update Attendees (Android)', () async {
        if (!Platform.isAndroid) {
          print('LOG_TEST: Skipping attendee mutation test on iOS/macOS local calendar');
          return;
        }

        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        // Add a second attendee
        event!.attendees = [
          Attendee(
            name: 'Alice Johnson',
            emailAddress: 'alice@example.com',
            role: AttendeeRole.Required,
          ),
          Attendee(
            name: 'Bob Smith',
            emailAddress: 'bob@example.com',
            role: AttendeeRole.Optional,
          ),
        ];

        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        if (loaded?.attendees != null && loaded!.attendees!.isNotEmpty) {
          expect(loaded.attendees!.any((a) => a?.emailAddress == 'alice@example.com'), true);
        }
        print('LOG_TEST: Attendees update verification passed');
      });

      test('2.12 Update Event Color on Android (if supported) and verify reload', () async {
        if (!Platform.isAndroid || androidEventColorA == null) {
          print('LOG_TEST: Skipping event color test (not Android or no colors available)');
          return;
        }

        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        event!.updateEventColor(androidEventColorA);
        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);

        await Future.delayed(const Duration(milliseconds: 500));
        final loaded = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(loaded?.color, androidEventColorA?.color);
        print('LOG_TEST: Event color update verification passed');
      });

      test('2.13 Move Normal Event to Calendar B and verify reload', () async {
        expect(normalEventId, isNotNull);
        final event = await loadEventFromDevice(calendarIdA, normalEventId);
        expect(event, isNotNull);

        final currentStart = event!.start ?? baseDate;
        final currentEnd = event.end ?? currentStart.add(const Duration(hours: 1));
        event.calendarId = calendarIdB;
        final res = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(res?.isSuccess, true);
        final movedEventId = res!.data!;

        await Future.delayed(const Duration(seconds: 1));

        // Verify gone from Calendar A
        final listA = await loadInstancesFromDevice(
          calendarIdA,
          startDate: currentStart.subtract(const Duration(days: 5)),
          endDate: currentEnd.add(const Duration(days: 5)),
        );
        expect(listA.any((e) => e.eventId == normalEventId || e.eventId == movedEventId), false,
            reason: 'Moved event should no longer be in Calendar A');

        // Verify present in Calendar B
        final listB = await loadInstancesFromDevice(
          calendarIdB,
          startDate: currentStart.subtract(const Duration(days: 5)),
          endDate: currentEnd.add(const Duration(days: 5)),
        );
        expect(listB.any((e) => e.eventId == movedEventId), true,
            reason: 'Moved event must exist in Calendar B');

        final loadedB = listB.firstWhere((e) => e.eventId == movedEventId);
        expect(loadedB.title, 'Normal Event Updated Title v2');
        expect(loadedB.description, 'Updated Description v2 - full details added.');

        // Delete from Calendar B
        final delRes = await deviceCalendarPlugin.deleteEvent(calendarIdB, movedEventId);
        expect(delRes.isSuccess, true);
        expect(delRes.data, true);

        await Future.delayed(const Duration(seconds: 1));
        final listBAfter = await loadInstancesFromDevice(
          calendarIdB,
          startDate: currentStart.subtract(const Duration(days: 5)),
          endDate: currentEnd.add(const Duration(days: 5)),
        );
        expect(listBAfter.any((e) => e.eventId == movedEventId), false,
            reason: 'Event should be deleted from Calendar B');
        print('LOG_TEST: Normal event move and delete verification passed');
      });
    });

    // =========================================================================
    // 3. RECURRING EVENT TYPES AND RULES
    // =========================================================================
    group('3. Recurring Event Rule Types and Instance Expansion', () {
      final localLocation = tz.local;
      final startDate = tz.TZDateTime(localLocation, 2026, 10, 1, 9, 0, 0);

      test('3.1 Daily Recurring Event with Count', () async {
        final event = Event(calendarIdA)
          ..title = 'Daily Recurring 5x'
          ..start = startDate
          ..end = startDate.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: startDate.subtract(const Duration(days: 1)),
          endDate: startDate.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(instances.length, 5, reason: 'Daily recurrence with count=5 must produce 5 instances');

        instances.sort((a, b) => a.start!.compareTo(b.start!));
        for (int i = 0; i < 5; i++) {
          expect(instances[i].start!.day, startDate.day + i);
          expect(instances[i].start!.hour, 9);
        }

        // Clean up
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('3.2 Daily Recurring Event with Interval = 2 (Every 2 days)', () async {
        final start = startDate.add(const Duration(days: 15));
        final event = Event(calendarIdA)
          ..title = 'Every 2 Days 4x'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            interval: 2,
            count: 4,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 15)),
          eventId: eventId,
        );
        expect(instances.length, 4);

        instances.sort((a, b) => a.start!.compareTo(b.start!));
        for (int i = 0; i < 4; i++) {
          expect(instances[i].start!.day, start.day + (i * 2));
        }

        // Clean up
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('3.3 Weekly Recurring Event with Count', () async {
        final start = startDate.add(const Duration(days: 30));
        final event = Event(calendarIdA)
          ..title = 'Weekly Event 4x'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.weekly,
            count: 4,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 35)),
          eventId: eventId,
        );
        expect(instances.length, 4, reason: 'Weekly recurrence with count=4 must produce 4 instances');

        // Clean up
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('3.4 Monthly Recurring Event with Count', () async {
        final start = startDate.add(const Duration(days: 60));
        final event = Event(calendarIdA)
          ..title = 'Monthly Event 3x'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.monthly,
            count: 3,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 120)),
          eventId: eventId,
        );
        expect(instances.length, 3, reason: 'Monthly recurrence with count=3 must produce 3 instances');

        // Clean up
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('3.5 Recurring Event with Until Date', () async {
        final start = startDate.add(const Duration(days: 90));
        final untilDate = start.add(const Duration(days: 3, hours: 2));
        final event = Event(calendarIdA)
          ..title = 'Daily with Until Date'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            until: untilDate,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: eventId,
        );
        // Start day 0, 1, 2, 3 = 4 instances
        expect(instances.length, greaterThanOrEqualTo(3));
        expect(instances.length, lessThanOrEqualTo(4));

        // Clean up
        await safeDeleteEvent(calendarIdA, eventId);
      });
    });

    // =========================================================================
    // 4. RECURRING EVENT: EDIT ALL INSTANCES (MASTER EVENT UPDATE & RELOAD)
    // =========================================================================
    group('4. Recurring Event: Edit All Instances (Master Series)', () {
      String? recurringEventId;
      final localLocation = tz.local;
      final seriesStart = tz.TZDateTime(localLocation, 2026, 11, 1, 10, 0, 0);

      setUp(() async {
        final event = Event(calendarIdA)
          ..title = 'Master Series Original Title'
          ..description = 'Original Description for master series'
          ..location = 'Conference Room A'
          ..url = Uri.parse('https://example.com/master_original')
          ..start = seriesStart
          ..end = seriesStart.add(const Duration(hours: 1))
          ..availability = Availability.Busy
          ..status = EventStatus.Confirmed
          ..reminders = [Reminder(minutes: 10)]
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        recurringEventId = createRes!.data!;
        await Future.delayed(const Duration(seconds: 1));
      });

      tearDown(() async {
        if (recurringEventId != null) {
          await safeDeleteEvent(calendarIdA, recurringEventId);
          await safeDeleteEvent(calendarIdB, recurringEventId);
        }
      });

      test('4.1 Edit Title for all instances and verify reload', () async {
        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(instances.length, 5);

        final firstInstance = instances.first;
        firstInstance.title = 'Master Series Updated Title (All)';

        final updateRes = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
        expect(updateRes?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(afterInstances.length, 5);
        for (final inst in afterInstances) {
          expect(inst.title, 'Master Series Updated Title (All)');
          expect(inst.location, 'Conference Room A');
        }
        print('LOG_TEST: Edit all instances title verified');
      });

      test('4.2 Edit Description & Location for all instances and verify reload', () async {
        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(instances.length, 5);

        final firstInstance = instances.first;
        firstInstance.description = 'Updated Description for all 5 instances.';
        firstInstance.location = 'Auditorium Main Stage';

        final updateRes = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
        expect(updateRes?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(afterInstances.length, 5);
        for (final inst in afterInstances) {
          expect(inst.description, 'Updated Description for all 5 instances.');
          expect(inst.location, 'Auditorium Main Stage');
        }
        print('LOG_TEST: Edit all instances description & location verified');
      });

      test('4.3 Edit Time (Shift by +2 hours) for all instances and verify reload', () async {
        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(instances.length, 5);

        final firstInstance = instances.first;
        firstInstance.start = firstInstance.start!.add(const Duration(hours: 2)); // 12:00
        firstInstance.end = firstInstance.end!.add(const Duration(hours: 2)); // 13:00

        final updateRes = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
        expect(updateRes?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(afterInstances.length, 5);
        for (final inst in afterInstances) {
          expect(inst.start!.hour, 12);
          expect(inst.end!.hour, 13);
        }
        print('LOG_TEST: Edit all instances time shift verified');
      });

      test('4.4 Move entire recurring series to Calendar B and verify reload', () async {
        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: recurringEventId,
        );
        expect(instances.length, 5);

        final firstInstance = instances.first;
        firstInstance.calendarId = calendarIdB;
        firstInstance.title = 'Moved Recurring Series in Cal B';

        final updateRes = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
        expect(updateRes?.isSuccess, true);
        final movedEventId = updateRes!.data!;

        await Future.delayed(const Duration(seconds: 2));

        // Verify 0 instances in Calendar A
        final afterA = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
        );
        expect(afterA.any((e) => e.eventId == recurringEventId || e.eventId == movedEventId), false);

        // Verify 5 instances in Calendar B
        final afterB = await loadInstancesFromDevice(
          calendarIdB,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: movedEventId,
        );
        expect(afterB.length, 5);
        for (final inst in afterB) {
          expect(inst.title, 'Moved Recurring Series in Cal B');
        }
        print('LOG_TEST: Move entire recurring series verified');

        // Delete from Calendar B
        await safeDeleteEvent(calendarIdB, movedEventId);
        recurringEventId = null;
      });
    });

    // =========================================================================
    // 5. RECURRING EVENT: "EDIT ONLY THIS EVENT" (SINGLE EXCEPTION)
    // =========================================================================
    group('5. Recurring Event: "Edit Only This Event" (Exception)', () {
      final localLocation = tz.local;
      final seriesStart = tz.TZDateTime(localLocation, 2026, 12, 1, 10, 0, 0);

      test('5.1 Edit single instance (title, time, location, color) and verify reload', () async {
        final event = Event(calendarIdA)
          ..title = 'Recurring Base Event'
          ..description = 'Base Description'
          ..location = 'Room 1'
          ..start = seriesStart
          ..end = seriesStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(instances.length, 5);
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (index 2)
        final targetInstance = instances[2];
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;
        final originalDay = targetInstance.start!.day;

        // Modify only this instance
        final updatedInstance = targetInstance;
        updatedInstance.title = 'EXCEPTION - Only This Instance';
        updatedInstance.description = 'Exception specific notes';
        updatedInstance.location = 'Special Room VIP';
        // Shift time by +2 hours on the same day
        updatedInstance.start = targetInstance.start!.add(const Duration(hours: 2));
        updatedInstance.end = targetInstance.end!.add(const Duration(hours: 2));

        if (Platform.isAndroid && androidEventColorA != null) {
          updatedInstance.updateEventColor(androidEventColorA);
        }

        final editRes = await deviceCalendarPlugin.createOrUpdateEvent(
          updatedInstance,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: false,
        );
        expect(editRes?.isSuccess, true,
            reason: 'Edit only this instance should succeed: ${editRes?.errors.map((e) => e.errorMessage).toList()}');

        await Future.delayed(const Duration(seconds: 2));

        // Reload all events in the window
        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
        );

        final seriesAndException = afterInstances.where(
          (e) => e.eventId == eventId || e.title == 'EXCEPTION - Only This Instance',
        ).toList();
        expect(seriesAndException.length, 5, reason: 'Total count must remain 5 instances');

        final baseList = seriesAndException.where((e) => e.title == 'Recurring Base Event').toList();
        final exceptionList = seriesAndException.where((e) => e.title == 'EXCEPTION - Only This Instance').toList();

        expect(baseList.length, 4, reason: '4 instances must retain base title');
        expect(exceptionList.length, 1, reason: 'Exactly 1 exception instance must exist');

        final exception = exceptionList.first;
        expect(exception.start!.hour, 12);
        expect(exception.end!.hour, 13);
        expect(exception.start!.day, originalDay);
        expect(exception.description, 'Exception specific notes');
        expect(exception.location, 'Special Room VIP');

        print('LOG_TEST: Edit only this instance verified successfully');

        // Cleanup
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('5.2 Move single instance to a different DAY (Shift Day 3 to Day 4) and verify reload', () async {
        final event = Event(calendarIdA)
          ..title = 'Recurring Day Shift Base'
          ..start = seriesStart
          ..end = seriesStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(instances.length, 5);
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (originally on Dec 3 at 10:00)
        final targetInstance = instances[2];
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;

        // Shift this instance to Dec 4 at 15:00 (+1 day, +5 hours)
        final updatedInstance = targetInstance;
        updatedInstance.title = 'EXCEPTION - Shifted to Next Day';
        updatedInstance.start = tz.TZDateTime(localLocation, 2026, 12, 4, 15, 0, 0);
        updatedInstance.end = tz.TZDateTime(localLocation, 2026, 12, 4, 16, 0, 0);

        final editRes = await deviceCalendarPlugin.createOrUpdateEvent(
          updatedInstance,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: false,
        );
        expect(editRes?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
        );

        final seriesAndException = afterInstances.where(
          (e) => e.eventId == eventId || e.title == 'EXCEPTION - Shifted to Next Day',
        ).toList();
        expect(seriesAndException.length, 5, reason: 'Total count of instances must still be 5');

        final exceptionList = seriesAndException.where((e) => e.title == 'EXCEPTION - Shifted to Next Day').toList();
        expect(exceptionList.length, 1);
        final exception = exceptionList.first;
        expect(exception.start!.day, 4);
        expect(exception.start!.hour, 15);
        expect(exception.end!.hour, 16);

        // Verify there is no instance left at Dec 3 10:00
        final dec3Instances = seriesAndException.where((e) => e.start!.day == 3 && e.start!.hour == 10).toList();
        expect(dec3Instances.isEmpty, true, reason: 'Original Dec 3 instance must be replaced/moved');

        print('LOG_TEST: Single instance day shift verified successfully');
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('5.3 Move single instance across month boundary (Dec 1 to Nov 28) and verify reload', () async {
        final event = Event(calendarIdA)
          ..title = 'Recurring Month Shift Base'
          ..start = seriesStart
          ..end = seriesStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 5)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(instances.length, 5);
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 1st instance (originally on Dec 1)
        final targetInstance = instances.first;
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;

        // Shift backwards to Nov 28 at 14:00
        final updatedInstance = targetInstance;
        updatedInstance.title = 'EXCEPTION - Shifted to November';
        updatedInstance.start = tz.TZDateTime(localLocation, 2026, 11, 28, 14, 0, 0);
        updatedInstance.end = tz.TZDateTime(localLocation, 2026, 11, 28, 15, 0, 0);

        final editRes = await deviceCalendarPlugin.createOrUpdateEvent(
          updatedInstance,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: false,
        );
        expect(editRes?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        final afterInstances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 5)),
          endDate: seriesStart.add(const Duration(days: 10)),
        );

        final seriesAndException = afterInstances.where(
          (e) => e.eventId == eventId || e.title == 'EXCEPTION - Shifted to November',
        ).toList();
        expect(seriesAndException.length, 5);

        final exceptionList = seriesAndException.where((e) => e.title == 'EXCEPTION - Shifted to November').toList();
        expect(exceptionList.length, 1);
        final exception = exceptionList.first;
        expect(exception.start!.month, 11);
        expect(exception.start!.day, 28);
        expect(exception.start!.hour, 14);

        print('LOG_TEST: Single instance cross-month shift verified successfully');
        await safeDeleteEvent(calendarIdA, eventId);
      });
    });

    // =========================================================================
    // 6. RECURRING EVENT: "EDIT THIS AND FUTURE" (SERIES SPLITTING)
    // =========================================================================
    group('6. Recurring Event: "Edit This and Future Instances" (Series Splitting)', () {
      final localLocation = tz.local;
      final seriesStart = tz.TZDateTime(localLocation, 2027, 1, 1, 10, 0, 0);

      test('6.1 Split series from 3rd instance, update fields & calendar, verify reload', () async {
        final event = Event(calendarIdA)
          ..title = 'Original Split Series'
          ..description = 'Original Split Description'
          ..start = seriesStart
          ..end = seriesStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final originalEventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final instances = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: originalEventId,
        );
        expect(instances.length, 5);
        instances.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (index 2)
        final targetInstance = instances[2];
        final targetStartMs = targetInstance.start!.millisecondsSinceEpoch;
        final targetEndMs = targetInstance.end!.millisecondsSinceEpoch;
        final targetDay = targetInstance.start!.day;

        // Edit this and future
        final updated = targetInstance;
        updated.title = 'Split Series - This & Future (Updated)';
        updated.description = 'Updated description for future series';
        updated.start = targetInstance.start!.add(const Duration(hours: 3)); // 13:00
        updated.end = targetInstance.end!.add(const Duration(hours: 3)); // 14:00
        updated.calendarId = calendarIdB; // Move the future series to Calendar B

        final splitRes = await deviceCalendarPlugin.createOrUpdateEvent(
          updated,
          instanceStartDate: targetStartMs,
          instanceEndDate: targetEndMs,
          updateFollowingInstances: true,
        );
        expect(splitRes?.isSuccess, true);
        final newSeriesEventId = splitRes!.data!;
        print('LOG_TEST: Split series new event ID: $newSeriesEventId');

        await Future.delayed(const Duration(seconds: 2));

        // Verify Calendar A: Only the first 2 instances remain
        final afterA = await loadInstancesFromDevice(
          calendarIdA,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
        );
        final remainingInA = afterA.where((e) => e.eventId == originalEventId).toList();
        expect(remainingInA.length, 2, reason: 'First 2 instances remain in original series');
        for (final inst in remainingInA) {
          expect(inst.title, 'Original Split Series');
          expect(inst.start!.hour, 10);
        }

        // Verify Calendar B: The 3 future instances are now in Calendar B
        final afterB = await loadInstancesFromDevice(
          calendarIdB,
          startDate: seriesStart.subtract(const Duration(days: 1)),
          endDate: seriesStart.add(const Duration(days: 10)),
          eventId: newSeriesEventId,
        );
        expect(afterB.length, 3, reason: 'Future 3 instances must be in Calendar B');
        afterB.sort((a, b) => a.start!.compareTo(b.start!));

        expect(afterB[0].start!.day, targetDay);
        for (final inst in afterB) {
          expect(inst.title, 'Split Series - This & Future (Updated)');
          expect(inst.description, 'Updated description for future series');
          expect(inst.start!.hour, 13);
          expect(inst.end!.hour, 14);
        }

        print('LOG_TEST: Edit this and future verified successfully');

        // Cleanup
        await safeDeleteEvent(calendarIdA, originalEventId);
        await safeDeleteEvent(calendarIdB, newSeriesEventId);
      });

      test('6.2 Edit this and future twice (cascade splits)', () async {
        final start = seriesStart.add(const Duration(days: 20));
        final event = Event(calendarIdA)
          ..title = 'Multi-Split Base'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 6,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final id1 = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        // First split at index 2 (day 3)
        var list1 = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id1,
        );
        list1.sort((a, b) => a.start!.compareTo(b.start!));
        expect(list1.length, 6);

        final target1 = list1[2];
        target1.title = 'Multi-Split First Pass';
        final splitRes1 = await deviceCalendarPlugin.createOrUpdateEvent(
          target1,
          instanceStartDate: target1.start!.millisecondsSinceEpoch,
          instanceEndDate: target1.end!.millisecondsSinceEpoch,
          updateFollowingInstances: true,
        );
        expect(splitRes1?.isSuccess, true);
        final id2 = splitRes1!.data!;

        await Future.delayed(const Duration(seconds: 2));

        // Verify intermediate state
        final listAfterPass1_A = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id1,
        );
        final listAfterPass1_B = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id2,
        );
        expect(listAfterPass1_A.length, 2);
        expect(listAfterPass1_B.length, 4);

        // Second split on the second series at its index 2 (overall occurrence 5)
        listAfterPass1_B.sort((a, b) => a.start!.compareTo(b.start!));
        final target2 = listAfterPass1_B[2];
        target2.title = 'Multi-Split Second Pass';
        final splitRes2 = await deviceCalendarPlugin.createOrUpdateEvent(
          target2,
          instanceStartDate: target2.start!.millisecondsSinceEpoch,
          instanceEndDate: target2.end!.millisecondsSinceEpoch,
          updateFollowingInstances: true,
        );
        expect(splitRes2?.isSuccess, true);
        final id3 = splitRes2!.data!;

        await Future.delayed(const Duration(seconds: 2));

        // Final verification: 2 in series 1, 2 in series 2, 2 in series 3
        final finalList1 = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id1,
        );
        final finalList2 = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id2,
        );
        final finalList3 = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: id3,
        );

        expect(finalList1.length, 2);
        expect(finalList2.length, 2);
        expect(finalList3.length, 2);

        for (final e in finalList1) expect(e.title, 'Multi-Split Base');
        for (final e in finalList2) expect(e.title, 'Multi-Split First Pass');
        for (final e in finalList3) expect(e.title, 'Multi-Split Second Pass');

        print('LOG_TEST: Multi-split cascade verified');

        // Cleanup
        await safeDeleteEvent(calendarIdA, id1);
        await safeDeleteEvent(calendarIdA, id2);
        await safeDeleteEvent(calendarIdA, id3);
      });
    });

    // =========================================================================
    // 7. DELETION SCENARIOS: SINGLE INSTANCE, THIS & FUTURE, AND FULL SERIES
    // =========================================================================
    group('7. Deletion Scenarios', () {
      final localLocation = tz.local;
      final baseStart = tz.TZDateTime(localLocation, 2027, 2, 1, 10, 0, 0);

      test('7.1 Delete single instance ("Delete this event") and verify reload', () async {
        final event = Event(calendarIdA)
          ..title = 'Deletion Test - Single'
          ..start = baseStart
          ..end = baseStart.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final initial = await loadInstancesFromDevice(
          calendarIdA,
          startDate: baseStart.subtract(const Duration(days: 1)),
          endDate: baseStart.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(initial.length, 5);
        initial.sort((a, b) => a.start!.compareTo(b.start!));

        // Delete 3rd instance (index 2)
        final target = initial[2];
        final targetStartMs = target.start!.millisecondsSinceEpoch;
        final targetEndMs = target.end!.millisecondsSinceEpoch;

        final delRes = await deviceCalendarPlugin.deleteEventInstance(
          calendarIdA,
          eventId,
          targetStartMs,
          targetEndMs,
          false,
        );
        expect(delRes.isSuccess, true);
        expect(delRes.data, true);

        await Future.delayed(const Duration(seconds: 2));

        final after = await loadInstancesFromDevice(
          calendarIdA,
          startDate: baseStart.subtract(const Duration(days: 1)),
          endDate: baseStart.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(after.length, 4, reason: 'Exactly 4 instances must remain after deleting 1');

        final exists = after.any((e) => e.start!.millisecondsSinceEpoch == targetStartMs);
        expect(exists, false, reason: 'Deleted instance must no longer be present');

        print('LOG_TEST: Delete single instance verified');

        // Cleanup
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('7.2 Delete this and future instances and verify reload', () async {
        final start = baseStart.add(const Duration(days: 10));
        final event = Event(calendarIdA)
          ..title = 'Deletion Test - This & Future'
          ..start = start
          ..end = start.add(const Duration(hours: 1))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );

        final createRes = await deviceCalendarPlugin.createOrUpdateEvent(event);
        expect(createRes?.isSuccess, true);
        final eventId = createRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        final initial = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(initial.length, 5);
        initial.sort((a, b) => a.start!.compareTo(b.start!));

        // Delete from 3rd instance (index 2) onward
        final target = initial[2];
        final targetStartMs = target.start!.millisecondsSinceEpoch;
        final targetEndMs = target.end!.millisecondsSinceEpoch;

        final delRes = await deviceCalendarPlugin.deleteEventInstance(
          calendarIdA,
          eventId,
          targetStartMs,
          targetEndMs,
          true,
        );
        expect(delRes.isSuccess, true);
        expect(delRes.data, true);

        await Future.delayed(const Duration(seconds: 2));

        final after = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
          eventId: eventId,
        );
        expect(after.length, 2, reason: 'Only the first 2 instances should remain');
        after.sort((a, b) => a.start!.compareTo(b.start!));

        expect(after[0].start!.millisecondsSinceEpoch, initial[0].start!.millisecondsSinceEpoch);
        expect(after[1].start!.millisecondsSinceEpoch, initial[1].start!.millisecondsSinceEpoch);

        print('LOG_TEST: Delete this and future instances verified');

        // Cleanup
        await safeDeleteEvent(calendarIdA, eventId);
      });

      test('7.3 Delete all instances and ensure neighboring events are unaffected', () async {
        final start = baseStart.add(const Duration(days: 20));

        // 1. Create a neighbor normal event
        final normalEvent = Event(calendarIdA)
          ..title = 'Neighbor Normal Event'
          ..start = start
          ..end = start.add(const Duration(hours: 1));
        final normalRes = await deviceCalendarPlugin.createOrUpdateEvent(normalEvent);
        expect(normalRes?.isSuccess, true);
        final normalId = normalRes!.data!;

        // 2. Create a recurring event
        final recurringEvent = Event(calendarIdA)
          ..title = 'Recurring Event to Delete All'
          ..start = start.add(const Duration(hours: 2))
          ..end = start.add(const Duration(hours: 3))
          ..recurrenceRule = RecurrenceRule(
            frequency: Frequency.daily,
            count: 5,
          );
        final recRes = await deviceCalendarPlugin.createOrUpdateEvent(recurringEvent);
        expect(recRes?.isSuccess, true);
        final recId = recRes!.data!;

        await Future.delayed(const Duration(seconds: 1));

        // Verify both exist
        final initialEvents = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
        );
        expect(initialEvents.any((e) => e.eventId == normalId), true);
        expect(initialEvents.where((e) => e.eventId == recId).length, 5);

        // 3. Delete all instances of the recurring event
        final delRecRes = await deviceCalendarPlugin.deleteEvent(calendarIdA, recId);
        expect(delRecRes.isSuccess, true);
        expect(delRecRes.data, true);

        await Future.delayed(const Duration(seconds: 2));

        // Verify recurring event is completely gone, and normal event remains intact
        final afterRecDelete = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
        );
        expect(afterRecDelete.any((e) => e.eventId == recId), false,
            reason: 'Deleted recurring event must be completely gone');
        expect(afterRecDelete.any((e) => e.eventId == normalId), true,
            reason: 'Neighboring normal event must remain unaffected');

        // 4. Delete the normal event
        final delNormalRes = await deviceCalendarPlugin.deleteEvent(calendarIdA, normalId);
        expect(delNormalRes.isSuccess, true);
        expect(delNormalRes.data, true);

        await Future.delayed(const Duration(seconds: 1));

        final finalEvents = await loadInstancesFromDevice(
          calendarIdA,
          startDate: start.subtract(const Duration(days: 1)),
          endDate: start.add(const Duration(days: 10)),
        );
        expect(finalEvents.any((e) => e.eventId == normalId), false);
        print('LOG_TEST: Delete all and neighbor safety verified');
      });
    });

    // =========================================================================
    // 8. FINAL CLEANUP & CALENDAR DELETION
    // =========================================================================
    group('8. Final Verification & Calendar Deletion', () {
      test('8.1 Verify all events deleted, then delete test calendars', () async {
        final now = tz.TZDateTime.now(tz.local);
        final eventsA = await loadInstancesFromDevice(
          calendarIdA,
          startDate: now.subtract(const Duration(days: 100)),
          endDate: now.add(const Duration(days: 400)),
        );
        expect(eventsA.isEmpty, true,
            reason: 'All events in Calendar A should have been deleted: ${eventsA.map((e) => e.title).toList()}');

        final eventsB = await loadInstancesFromDevice(
          calendarIdB,
          startDate: now.subtract(const Duration(days: 100)),
          endDate: now.add(const Duration(days: 400)),
        );
        expect(eventsB.isEmpty, true,
            reason: 'All events in Calendar B should have been deleted: ${eventsB.map((e) => e.title).toList()}');

        // Delete Calendar A
        final delCalA = await deviceCalendarPlugin.deleteCalendar(calendarIdA!);
        expect(delCalA.isSuccess, true);
        expect(delCalA.data, true);
        calendarIdA = null;

        // Delete Calendar B
        final delCalB = await deviceCalendarPlugin.deleteCalendar(calendarIdB!);
        expect(delCalB.isSuccess, true);
        expect(delCalB.data, true);
        calendarIdB = null;

        await Future.delayed(const Duration(seconds: 1));

        // Verify calendars no longer exist
        final calendarsResult = await deviceCalendarPlugin.retrieveCalendars();
        expect(calendarsResult.isSuccess, true);
        expect(
          calendarsResult.data?.any((c) => c.name == 'Comprehensive Test Calendar A'),
          false,
        );
        expect(
          calendarsResult.data?.any((c) => c.name == 'Comprehensive Test Calendar B'),
          false,
        );
        print('LOG_TEST: Final cleanup and calendar deletion verified successfully');
      });
    });
  });
}
