import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  group('Calendar Recurring Event Editing Test Suite', () {
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
        'Recurring Edit Test Calendar',
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
        ..title = 'Original Recurring Event'
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

    test('1. Edit All Instances', () async {
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
      for (final instance in initialEvents) {
        expect(instance.title, 'Original Recurring Event');
      }

      // Modify title for all instances
      final firstInstance = initialEvents.first;
      firstInstance.title = 'Updated Title - All';

      print('LOG_REPRO: [Test 1] Editing all instances');
      final editResult = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
      expect(editResult?.isSuccess, true);

      // Wait for Android Calendar Provider updates to settle
      await Future.delayed(const Duration(seconds: 2));

      // Verify all instances reflect the updated title
      final afterEditResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterEditResult.isSuccess, true);
      final afterEditEvents = afterEditResult.data!.where((e) => e.eventId == eventId).toList();
      expect(afterEditEvents.length, 5);
      for (final instance in afterEditEvents) {
        expect(instance.title, 'Updated Title - All');
      }
      print('LOG_REPRO: [Test 1] Passed');
    });

    test('2. Edit Only This Instance', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      // Offset by 10 days to keep events separated
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

      print('LOG_REPRO: [Test 2] Target instance start: ${targetInstance.start}');

      // Create updated event object for target instance
      final updatedEvent = targetInstance;
      updatedEvent.title = 'Updated Title - Only This Instance';

      print('LOG_REPRO: [Test 2] Editing only this instance');
      final editResult = await deviceCalendarPlugin.createOrUpdateEvent(
        updatedEvent,
        instanceStartDate: targetStart,
        instanceEndDate: targetEnd,
        updateFollowingInstances: false,
      );
      expect(editResult?.isSuccess, true);

      // Wait for Android Calendar Provider background triggers to process the exception and re-expand instances
      await Future.delayed(const Duration(seconds: 2));

      // Verify instances after edit
      final afterEditResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterEditResult.isSuccess, true);

      // We should still have 5 instances of this series/exception in total
      final afterEditEvents = afterEditResult.data!.where((e) => e.eventId == eventId || e.title == 'Updated Title - Only This Instance').toList();
      expect(afterEditEvents.length, 5);

      afterEditEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Verify only the 3rd instance has the modified title
      expect(afterEditEvents[0].title, 'Original Recurring Event');
      expect(afterEditEvents[1].title, 'Original Recurring Event');
      expect(afterEditEvents[2].title, 'Updated Title - Only This Instance');
      expect(afterEditEvents[3].title, 'Original Recurring Event');
      expect(afterEditEvents[4].title, 'Original Recurring Event');

      print('LOG_REPRO: [Test 2] Passed');
    });

    test('3. Edit This and Future Instances', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      // Offset by 20 days to keep events separated
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

      print('LOG_REPRO: [Test 3] Target instance start: ${targetInstance.start}');

      // Create updated event object for target instance
      final updatedEvent = targetInstance;
      updatedEvent.title = 'Updated Title - This and Future Instances';

      print('LOG_REPRO: [Test 3] Editing this and future instances');
      final editResult = await deviceCalendarPlugin.createOrUpdateEvent(
        updatedEvent,
        instanceStartDate: targetStart,
        instanceEndDate: targetEnd,
        updateFollowingInstances: true,
      );
      expect(editResult?.isSuccess, true);
      final newSeriesEventId = editResult!.data!;
      print('LOG_REPRO: [Test 3] Created new split series with ID: $newSeriesEventId');

      // Wait for Android Calendar Provider background triggers to process the split and re-expand instances
      await Future.delayed(const Duration(seconds: 2));

      // Verify instances after edit
      final afterEditResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterEditResult.isSuccess, true);

      // Separate instances by event ID
      final originalSeriesEvents = afterEditResult.data!.where((e) => e.eventId == eventId).toList();
      final newSeriesEvents = afterEditResult.data!.where((e) => e.eventId == newSeriesEventId).toList();

      expect(originalSeriesEvents.length, 2);
      expect(newSeriesEvents.length, 3);

      originalSeriesEvents.sort((a, b) => a.start!.compareTo(b.start!));
      newSeriesEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Verify original series instances retain old title
      for (final instance in originalSeriesEvents) {
        expect(instance.title, 'Original Recurring Event');
      }

      // Verify split series instances have updated title
      for (final instance in newSeriesEvents) {
        expect(instance.title, 'Updated Title - This and Future Instances');
      }

      print('LOG_REPRO: [Test 3] Passed');
    });

    test('4. Edit This and Future Instances Twice', () async {
      final localLocation = tz.local;
      final now = tz.TZDateTime.now(localLocation);
      // Offset by 30 days to keep events separated
      final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 30));

      final eventId = await createDailyRecurringEvent(eventStart, 5);
      print('LOG_REPRO: [Test 4] Created event: $eventId');

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

      print('LOG_REPRO: [Test 4] Target instance 1 start: ${targetInstance.start}');

      // Create updated event object for target instance
      final updatedEvent = targetInstance;
      updatedEvent.title = 'Updated Title - First Split';

      print('LOG_REPRO: [Test 4] Editing this and future instances (first split)');
      final editResult = await deviceCalendarPlugin.createOrUpdateEvent(
        updatedEvent,
        instanceStartDate: targetStart,
        instanceEndDate: targetEnd,
        updateFollowingInstances: true,
      );
      expect(editResult?.isSuccess, true);
      final newSeriesEventId = editResult!.data!;
      print('LOG_REPRO: [Test 4] Created new split series with ID: $newSeriesEventId');

      // Wait for Android Calendar Provider background triggers to process the split and re-expand instances
      await Future.delayed(const Duration(seconds: 2));

      // Verify instances after edit
      final afterFirstEditResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(afterFirstEditResult.isSuccess, true);

      final originalSeriesEvents = afterFirstEditResult.data!.where((e) => e.eventId == eventId).toList();
      final newSeriesEvents = afterFirstEditResult.data!.where((e) => e.eventId == newSeriesEventId).toList();

      expect(originalSeriesEvents.length, 2);
      expect(newSeriesEvents.length, 3);

      newSeriesEvents.sort((a, b) => a.start!.compareTo(b.start!));

      // Choose the first instance of the new series (which is on the same day as the targetStart)
      final targetInstance2 = newSeriesEvents.first;
      final targetStart2 = targetInstance2.start!.millisecondsSinceEpoch;
      final targetEnd2 = targetInstance2.end!.millisecondsSinceEpoch;

      print('LOG_REPRO: [Test 4] Target instance 2 start: ${targetInstance2.start}');

      final updatedEvent2 = targetInstance2;
      updatedEvent2.title = 'Updated Title - Second Split';

      print('LOG_REPRO: [Test 4] Editing this and future instances (second split)');
      final editResult2 = await deviceCalendarPlugin.createOrUpdateEvent(
        updatedEvent2,
        instanceStartDate: targetStart2,
        instanceEndDate: targetEnd2,
        updateFollowingInstances: true,
      );
      expect(editResult2?.isSuccess, true);
      final thirdSeriesEventId = editResult2!.data!;
      print('LOG_REPRO: [Test 4] Created third split series with ID: $thirdSeriesEventId');

      // Wait for Android Calendar Provider
      await Future.delayed(const Duration(seconds: 2));

      // Verify final instances
      final finalResult = await deviceCalendarPlugin.retrieveEvents(calendarId, retrieveParams);
      expect(finalResult.isSuccess, true);

      print('LOG_REPRO: All final events found:');
      for (final event in finalResult.data!) {
        if (event.eventId == eventId || event.eventId == newSeriesEventId || event.eventId == thirdSeriesEventId) {
          print('  - ID: ${event.eventId}, Start: ${event.start}, Title: ${event.title}, Recur: ${event.recurrenceRule}');
        }
      }

      print('LOG_REPRO: [Test 4] Completed');
    });
  });
}
