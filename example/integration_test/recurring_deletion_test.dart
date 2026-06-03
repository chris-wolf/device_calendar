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
  });
}
