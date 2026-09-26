import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  group('Calendar Recurring Event Deletion Test Suite', () {
    late DeviceCalendarPlugin deviceCalendarPlugin;
    String? calendarId;

    setUpAll(() async {
      deviceCalendarPlugin = DeviceCalendarPlugin();

      // Request permissions
      final permissionsGranted = await deviceCalendarPlugin.requestPermissions();
      expect(permissionsGranted.isSuccess, true);
      expect(permissionsGranted.data, true);

      // Create a local calendar
      final createCalendarResult = await deviceCalendarPlugin.createCalendar(
        'Recurring Deletion Test Calendar',
        localAccountName: 'reproduction_account',
      );
      expect(createCalendarResult.isSuccess, true);
      calendarId = createCalendarResult.data;
      print('LOG_REPRO: Created calendar with ID: $calendarId');
    });

    tearDownAll(() async {
      if (calendarId != null) {
        final deleteResult = await deviceCalendarPlugin.deleteCalendar(calendarId!);
        print('LOG_REPRO: Deleted calendar result: ${deleteResult.data}');
      }
    });

    Future<String> createDailyRecurringEvent(
        tz.TZDateTime start, int occurrences) async {
      final eventStart = start;
      final eventEnd = eventStart.add(const Duration(hours: 1));

      final event = Event(calendarId)
        ..title = 'Recurring Event'
        ..start = eventStart
        ..end = eventEnd
        ..recurrenceRule = RecurrenceRule(
          frequency: Frequency.daily,
          count: occurrences,
        );

      final createResult = await deviceCalendarPlugin.createOrUpdateEvent(event);
      expect(createResult?.isSuccess, true);
      final eventId = createResult!.data!;
      return eventId;
    }

    test('1. Delete All', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0);

      final eventId = await createDailyRecurringEvent(eventStart, 5);
      print('LOG_REPRO: [Test 1] Created event: $eventId');

      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 7)),
      );

      // Verify 5 instances initially
      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
      expect(initialEvents.length, 5);

      // Delete all
      print('LOG_REPRO: [Test 1] Deleting all instances');
      final deleteResult = await deviceCalendarPlugin.deleteEvent(calendarId, eventId);
      expect(deleteResult.isSuccess, true);
      expect(deleteResult.data, true);

      // Verify 0 instances remain
      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterDeleteResult.isSuccess, true);
      final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
      expect(afterDeleteEvents.length, 0);
      print('LOG_REPRO: [Test 1] Passed');
    });

    test('2. Delete Only This Instance', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 10));

      final eventId = await createDailyRecurringEvent(eventStart, 5);
      print('LOG_REPRO: [Test 2] Created event: $eventId');

      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 7)),
      );

      // Verify 5 instances initially
      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
      expect(initialEvents.length, 5);

      // Sort by start date ascending
      initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Choose 3rd instance (index 2)
      final targetInstance = initialEvents[2];
      final targetStart = targetInstance.start!.millisecondsSinceEpoch;
      final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

      print('LOG_REPRO: [Test 2] Deleting target instance starting at: ${targetInstance.start}');
      final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
        calendarId,
        eventId,
        targetStart,
        targetEnd,
        false,
      );
      expect(deleteResult.isSuccess, true);
      expect(deleteResult.data, true);

      // Wait for Android Calendar Provider background triggers to process the exception and re-expand instances
      await Future.delayed(const Duration(seconds: 2));

      // Verify instances after deletion
      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterDeleteResult.isSuccess, true);
      final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();

      // Count should be 4
      expect(afterDeleteEvents.length, 4);

      // The deleted instance should not be in the results
      final targetStillExists = afterDeleteEvents.any((e) => e.start!.millisecondsSinceEpoch == targetStart);
      expect(targetStillExists, false);

      print('LOG_REPRO: [Test 2] Passed');
    });

    test('3. Delete This and Future Instances', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 20));

      final eventId = await createDailyRecurringEvent(eventStart, 5);
      print('LOG_REPRO: [Test 3] Created event: $eventId');

      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 7)),
      );

      // Verify 5 instances initially
      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
      expect(initialEvents.length, 5);

      // Sort by start date ascending
      initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Choose 3rd instance (index 2)
      final targetInstance = initialEvents[2];
      final targetStart = targetInstance.start!.millisecondsSinceEpoch;
      final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

      print('LOG_REPRO: [Test 3] Deleting this and future instances starting at: ${targetInstance.start}');
      final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
        calendarId,
        eventId,
        targetStart,
        targetEnd,
        true,
      );
      expect(deleteResult.isSuccess, true);
      expect(deleteResult.data, true);

      // Wait for Android Calendar Provider background triggers to process the rule change and re-expand instances
      await Future.delayed(const Duration(seconds: 2));

      // Verify instances after deletion
      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterDeleteResult.isSuccess, true);
      final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();

      // Only the 1st and 2nd instances should remain (count = 2)
      expect(afterDeleteEvents.length, 2);

      afterDeleteEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Check remaining are indeed the first two instances
      expect(afterDeleteEvents[0].start!.millisecondsSinceEpoch, initialEvents[0].start!.millisecondsSinceEpoch);
      expect(afterDeleteEvents[1].start!.millisecondsSinceEpoch, initialEvents[1].start!.millisecondsSinceEpoch);

      print('LOG_REPRO: [Test 3] Passed');
    });

    test('4. Delete Single Instance with Concurrent Overlapping Event & Occurrence ID (Crash Regression)', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 30));

      final eventId = await createDailyRecurringEvent(eventStart, 5);

      // Create an overlapping standalone event at the exact same time as instance 3
      final targetTime = eventStart.add(const Duration(days: 2));
      final overlap = Event(calendarId)
        ..title = 'Concurrent Overlapping Event'
        ..start = targetTime
        ..end = targetTime.add(const Duration(hours: 1));
      final overlapRes = await deviceCalendarPlugin.createOrUpdateEvent(overlap);
      expect(overlapRes?.isSuccess, true);
      final overlapId = overlapRes!.data!;

      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 7)),
      );

      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
      expect(initialEvents.length, 5);

      initialEvents.sort((a, b) => a.start!.compareTo(b.start!));
      final targetInstance = initialEvents[2];
      final targetStart = targetInstance.start!.millisecondsSinceEpoch;
      final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

      // 1. Safe failure: Non-matching ID during overlapping window must fail gracefully without nil force-unwrap crash
      Result<bool>? invalidDel;
      try {
        invalidDel = await deviceCalendarPlugin.deleteEventInstance(
          calendarId,
          'non_existent_id_for_crash_test',
          targetStart,
          targetEnd,
          false,
        );
      } catch (_) {}
      expect(invalidDel == null || invalidDel.isSuccess == false || invalidDel.data != true, true);

      // 2. Delete using targetInstance.eventId (loaded occurrence ID)
      final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
        calendarId,
        targetInstance.eventId ?? eventId,
        targetStart,
        targetEnd,
        false,
      );
      expect(deleteResult.isSuccess, true);
      expect(deleteResult.data, true);

      await Future.delayed(const Duration(seconds: 2));

      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterDeleteResult.isSuccess, true);
      final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
      expect(afterDeleteEvents.length, 4);
      expect(afterDeleteEvents.any((e) => e.start!.millisecondsSinceEpoch == targetStart), false);

      // Verify the overlapping event was untouched
      final overlapCheck = await deviceCalendarPlugin.retrieveEvents(calendarId, RetrieveEventsParams(
        startDate: targetTime.subtract(const Duration(hours: 1)),
        endDate: targetTime.add(const Duration(hours: 2)),
        eventIds: [overlapId],
      ));
      expect(overlapCheck.data?.isNotEmpty, true);

      await deviceCalendarPlugin.deleteEvent(calendarId, overlapId);
      print('LOG_REPRO: [Test 4] Passed');
    });

    test('5. Delete Detached Occurrence (Crash Regression)', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 40));

      final eventId = await createDailyRecurringEvent(eventStart, 5);

      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 7)),
      );

      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
      expect(initialEvents.length, 5);

      initialEvents.sort((a, b) => a.start!.compareTo(b.start!));
      final targetInstance = initialEvents[2];
      final targetStart = targetInstance.start!.millisecondsSinceEpoch;
      final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

      // Edit only this instance (detached occurrence)
      targetInstance.title = 'Detached Occurrence (Edited)';
      final editRes = await deviceCalendarPlugin.createOrUpdateEvent(
        targetInstance,
        instanceStartDate: targetStart,
        instanceEndDate: targetEnd,
        updateFollowingInstances: false,
      );
      expect(editRes?.isSuccess, true);

      await Future.delayed(const Duration(seconds: 2));

      final afterEditResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      final detachedInstance = afterEditResult.data!.firstWhere((e) => e.start!.millisecondsSinceEpoch == targetStart);

      // Delete the detached occurrence
      final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
        calendarId,
        detachedInstance.eventId ?? eventId,
        targetStart,
        targetEnd,
        false,
      );
      expect(deleteResult.isSuccess, true);
      expect(deleteResult.data, true);

      await Future.delayed(const Duration(seconds: 2));

      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
      expect(afterDeleteEvents.any((e) => e.start!.millisecondsSinceEpoch == targetStart), false);

      print('LOG_REPRO: [Test 5] Passed');
    });
  });
}
