import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  group('Calendar Bug Reproduction Test', () {
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
        'Reproduction Local Calendar',
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

    test('verify local calendar recurring event deletion fix', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0);
      final eventEnd = eventStart.add(const Duration(hours: 1));

      // 1. Create a normal event (status is null, to verify null status update crash is also resolved)
      final normalEvent = Event(calendarId)
        ..title = 'Normal Event'
        ..start = eventStart
        ..end = eventEnd;
      
      final createNormalResult = await deviceCalendarPlugin.createOrUpdateEvent(normalEvent);
      expect(createNormalResult?.isSuccess, true);
      final normalEventId = createNormalResult?.data;
      print('LOG_REPRO: Created normal event with ID: $normalEventId');

      // 2. Create a recurring event (repeating daily for 5 occurrences)
      final recurringEvent = Event(calendarId)
        ..title = 'Recurring Event'
        ..start = eventStart.add(const Duration(hours: 2))
        ..end = eventEnd.add(const Duration(hours: 2))
        ..recurrenceRule = RecurrenceRule(
          RecurrenceFrequency.Daily,
          totalOccurrences: 5,
        );

      final createRecurringResult = await deviceCalendarPlugin.createOrUpdateEvent(recurringEvent);
      expect(createRecurringResult?.isSuccess, true);
      final recurringEventId = createRecurringResult?.data;
      print('LOG_REPRO: Created recurring event with ID: $recurringEventId');

      // 3. Query initial instances
      final retrieveParams = RetrieveEventsParams(
        startDate: eventStart.subtract(const Duration(days: 1)),
        endDate: eventStart.add(const Duration(days: 10)),
      );

      final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(initialEventsResult.isSuccess, true);
      final initialEvents = initialEventsResult.data!;
      print('LOG_REPRO: Initial retrieved instances count: ${initialEvents.length}');
      
      final hasNormal = initialEvents.any((e) => e.eventId == normalEventId);
      final hasRecurring = initialEvents.any((e) => e.eventId == recurringEventId);
      expect(hasNormal, true, reason: 'Initial list should contain the normal event');
      expect(hasRecurring, true, reason: 'Initial list should contain the recurring event');

      // 4. Delete the recurring event (which will now use hard delete)
      print('LOG_REPRO: Deleting recurring event $recurringEventId');
      final deleteRecurringResult = await deviceCalendarPlugin.deleteEvent(calendarId, recurringEventId);
      expect(deleteRecurringResult.isSuccess, true);
      expect(deleteRecurringResult.data, true);

      // 5. Query instances again to check that the normal event is still there
      final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterDeleteResult.isSuccess, true);
      final afterDeleteEvents = afterDeleteResult.data!;
      print('LOG_REPRO: Instances remaining after delete: ${afterDeleteEvents.map((e) => '${e.title} (${e.eventId})').toList()}');

      final hasNormalAfterDelete = afterDeleteEvents.any((e) => e.eventId == normalEventId);
      expect(hasNormalAfterDelete, true, reason: 'BUG DETECTED: Normal event instance disappeared after deleting recurring event!');
      
      final hasRecurringAfterDelete = afterDeleteEvents.any((e) => e.eventId == recurringEventId);
      expect(hasRecurringAfterDelete, false, reason: 'Deleted recurring event is still in the instances table!');

      // 6. Perform a touch update on the normal event (with status null) to ensure it works and doesn't crash
      print('LOG_REPRO: Performing touch update on normal event to verify null status updates work...');
      normalEvent.eventId = normalEventId;
      normalEvent.title = 'Normal Event (Touched)';
      final touchResult = await deviceCalendarPlugin.createOrUpdateEvent(normalEvent);
      expect(touchResult?.isSuccess, true);
      print('LOG_REPRO: Touch update succeeded without crashing!');
    });
  });
}
