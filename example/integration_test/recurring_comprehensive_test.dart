import 'dart:io';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:device_calendar/device_calendar.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  tz.initializeTimeZones();

  group('Calendar Recurring Event Comprehensive Test Suite', () {
    late DeviceCalendarPlugin deviceCalendarPlugin;
    String? calendarIdA;
    String? calendarIdB;
    EventColor? eventColorA;
    EventColor? eventColorB;

    setUpAll(() async {
      deviceCalendarPlugin = DeviceCalendarPlugin();

      // Request permissions
      final permissionsGranted = await deviceCalendarPlugin.requestPermissions();
      expect(permissionsGranted.isSuccess, true);
      expect(permissionsGranted.data, true);

      // Create local calendar A
      final createCalendarAResult = await deviceCalendarPlugin.createCalendar(
        'Comprehensive Test Calendar A',
        localAccountName: 'reproduction_account_a',
      );
      expect(createCalendarAResult.isSuccess, true);
      calendarIdA = createCalendarAResult.data;
      print('LOG_REPRO: Created calendar A with ID: $calendarIdA');

      // Create local calendar B
      final createCalendarBResult = await deviceCalendarPlugin.createCalendar(
        'Comprehensive Test Calendar B',
        localAccountName: 'reproduction_account_b',
      );
      expect(createCalendarBResult.isSuccess, true);
      calendarIdB = createCalendarBResult.data;
      print('LOG_REPRO: Created calendar B with ID: $calendarIdB');

      // Retrieve event colors if on Android
      if (Platform.isAndroid) {
        final calendarsResult = await deviceCalendarPlugin.retrieveCalendars();
        expect(calendarsResult.isSuccess, true);
        final calA = calendarsResult.data!.firstWhere((c) => c.id == calendarIdA);
        final calB = calendarsResult.data!.firstWhere((c) => c.id == calendarIdB);

        final colorsA = await deviceCalendarPlugin.retrieveEventColors(calA);
        final colorsB = await deviceCalendarPlugin.retrieveEventColors(calB);

        if (colorsA != null && colorsA.isNotEmpty) {
          eventColorA = colorsA.first;
          print('LOG_REPRO: Retrieved event color for Calendar A: ${eventColorA?.color}');
        }
        if (colorsB != null && colorsB.isNotEmpty) {
          eventColorB = colorsB.first;
          print('LOG_REPRO: Retrieved event color for Calendar B: ${eventColorB?.color}');
        }
      }
    });

    tearDownAll(() async {
      if (calendarIdA != null) {
        final deleteResult = await deviceCalendarPlugin.deleteCalendar(calendarIdA!);
        print('LOG_REPRO: Deleted calendar A result: ${deleteResult.data}');
      }
      if (calendarIdB != null) {
        final deleteResult = await deviceCalendarPlugin.deleteCalendar(calendarIdB!);
        print('LOG_REPRO: Deleted calendar B result: ${deleteResult.data}');
      }
    });

    Future<String> createDailyRecurringEvent(
        String? calendarId, tz.TZDateTime start, int occurrences) async {
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

    group('Deletion Scenarios', () {
      test('1. Delete All Instances', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 10));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Delete All] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 7)),
        );

        // Verify 5 instances initially
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        // Delete all
        final deleteResult = await deviceCalendarPlugin.deleteEvent(calendarIdA, eventId);
        expect(deleteResult.isSuccess, true);
        expect(deleteResult.data, true);

        await Future.delayed(const Duration(seconds: 2));

        // Verify 0 instances remain
        final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterDeleteResult.isSuccess, true);
        final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
        expect(afterDeleteEvents.length, 0);
        print('LOG_REPRO: [Test Delete All] Passed');
      });

      test('2. Delete Only This Instance', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 20));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Delete Only This] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 7)),
        );

        // Verify 5 instances initially
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

        // Delete 3rd instance (index 2)
        final targetInstance = initialEvents[2];
        final targetStart = targetInstance.start!.millisecondsSinceEpoch;
        final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

        final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
          calendarIdA,
          eventId,
          targetStart,
          targetEnd,
          false,
        );
        expect(deleteResult.isSuccess, true);
        expect(deleteResult.data, true);

        await Future.delayed(const Duration(seconds: 2));

        // Verify 4 instances remain
        final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterDeleteResult.isSuccess, true);
        final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
        expect(afterDeleteEvents.length, 4);

        final targetStillExists = afterDeleteEvents.any((e) => e.start!.millisecondsSinceEpoch == targetStart);
        expect(targetStillExists, false);
        print('LOG_REPRO: [Test Delete Only This] Passed');
      });

      test('3. Delete This and Future Instances', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 30));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Delete This and Future] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 7)),
        );

        // Verify 5 instances initially
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

        // Delete 3rd instance (index 2) and future
        final targetInstance = initialEvents[2];
        final targetStart = targetInstance.start!.millisecondsSinceEpoch;
        final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

        final deleteResult = await deviceCalendarPlugin.deleteEventInstance(
          calendarIdA,
          eventId,
          targetStart,
          targetEnd,
          true,
        );
        expect(deleteResult.isSuccess, true);
        expect(deleteResult.data, true);

        await Future.delayed(const Duration(seconds: 2));

        // Verify 2 instances remain
        final afterDeleteResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterDeleteResult.isSuccess, true);
        final afterDeleteEvents = afterDeleteResult.data!.where((e) => e.eventId == eventId).toList();
        expect(afterDeleteEvents.length, 2);

        afterDeleteEvents.sort((a, b) => a.start!.compareTo(b.start!));
        expect(afterDeleteEvents[0].start!.millisecondsSinceEpoch, initialEvents[0].start!.millisecondsSinceEpoch);
        expect(afterDeleteEvents[1].start!.millisecondsSinceEpoch, initialEvents[1].start!.millisecondsSinceEpoch);
        print('LOG_REPRO: [Test Delete This and Future] Passed');
      });
    });

    group('Edit Scenarios', () {
      test('4. Edit All Instances (Change Title, Time, and Calendar)', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 40));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Edit All] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 7)),
        );

        // Verify 5 instances initially in calendarIdA
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        // Modify fields
        final firstInstance = initialEvents.first;
        firstInstance.title = 'All - Updated Title';
        firstInstance.start = firstInstance.start!.add(const Duration(hours: 1));
        firstInstance.end = firstInstance.end!.add(const Duration(hours: 1));
        firstInstance.calendarId = calendarIdB;

        final editResult = await deviceCalendarPlugin.createOrUpdateEvent(firstInstance);
        expect(editResult?.isSuccess, true);
        final updatedEventId = editResult!.data!;

        await Future.delayed(const Duration(seconds: 2));

        // Verify 0 instances remain in calendarIdA
        final afterEditResultA = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterEditResultA.isSuccess, true);
        final afterEditEventsA = afterEditResultA.data!.where((e) => e.eventId == eventId || e.eventId == updatedEventId).toList();
        expect(afterEditEventsA.length, 0);

        // Verify 5 instances exist in calendarIdB
        final afterEditResultB = await deviceCalendarPlugin.retrieveEvents(calendarIdB, retrieveParams);
        expect(afterEditResultB.isSuccess, true);
        final afterEditEventsB = afterEditResultB.data!.where((e) => e.eventId == updatedEventId).toList();
        expect(afterEditEventsB.length, 5);

        for (final instance in afterEditEventsB) {
          expect(instance.title, 'All - Updated Title');
          expect(instance.start!.hour, 11);
          expect(instance.end!.hour, 12);
        }
        print('LOG_REPRO: [Test Edit All] Passed');
      });

      test('5. Edit Only This Instance (Change Title, Time, Day, and Color)', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 50));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Edit Only This] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 10)),
        );

        // Verify 5 instances initially in calendarIdA
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (index 2)
        final targetInstance = initialEvents[2];
        final targetStart = targetInstance.start!.millisecondsSinceEpoch;
        final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

        final updatedEvent = targetInstance;
        updatedEvent.title = 'Only This - Updated Title';
        final originalTargetDay = targetInstance.start!.day;
        updatedEvent.start = targetInstance.start!.add(const Duration(days: 1, hours: 2));
        updatedEvent.end = targetInstance.end!.add(const Duration(days: 1, hours: 2));

        if (Platform.isAndroid && eventColorA != null) {
          updatedEvent.updateEventColor(eventColorA);
        }

        final editResult = await deviceCalendarPlugin.createOrUpdateEvent(
          updatedEvent,
          instanceStartDate: targetStart,
          instanceEndDate: targetEnd,
          updateFollowingInstances: false,
        );
        expect(editResult?.isSuccess, true);

        await Future.delayed(const Duration(seconds: 2));

        // Verify instances in calendarIdA
        final afterEditResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterEditResult.isSuccess, true);

        final originalInstances = afterEditResult.data!.where((e) => e.eventId == eventId && e.title == 'Original Recurring Event').toList();
        final exceptionInstances = afterEditResult.data!.where((e) => e.title == 'Only This - Updated Title').toList();

        expect(originalInstances.length, 4);
        expect(exceptionInstances.length, 1);

        final exceptionEvent = exceptionInstances.first;
        expect(exceptionEvent.start!.hour, 12);
        expect(exceptionEvent.start!.day, originalTargetDay + 1);
        if (Platform.isAndroid && eventColorA != null) {
          expect(exceptionEvent.color, eventColorA?.color);
        }
        print('LOG_REPRO: [Test Edit Only This] Passed');
      });

      test('6. Edit This and Future Instances (Change Title, Time, Day, Color, and Calendar)', () async {
        final localLocation = tz.local;
        final now = tz.TZDateTime.now(localLocation);
        final eventStart = tz.TZDateTime(localLocation, now.year, now.month, now.day, 10, 0, 0).add(const Duration(days: 60));

        final eventId = await createDailyRecurringEvent(calendarIdA, eventStart, 5);
        print('LOG_REPRO: [Test Edit This and Future] Created event: $eventId');

        final retrieveParams = RetrieveEventsParams(
          startDate: eventStart.subtract(const Duration(days: 1)),
          endDate: eventStart.add(const Duration(days: 10)),
        );

        // Verify 5 instances initially in calendarIdA
        final initialEventsResult = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(initialEventsResult.isSuccess, true);
        final initialEvents = initialEventsResult.data!.where((e) => e.eventId == eventId).toList();
        expect(initialEvents.length, 5);

        initialEvents.sort((a, b) => a.start!.compareTo(b.start!));

        // Select 3rd instance (index 2)
        final targetInstance = initialEvents[2];
        final targetStart = targetInstance.start!.millisecondsSinceEpoch;
        final targetEnd = targetInstance.end!.millisecondsSinceEpoch;

        final updatedEvent = targetInstance;
        updatedEvent.title = 'This and Future - Updated Title';
        final originalTargetDay = targetInstance.start!.day;
        updatedEvent.start = targetInstance.start!.add(const Duration(days: 1, hours: 2));
        updatedEvent.end = targetInstance.end!.add(const Duration(days: 1, hours: 2));
        updatedEvent.calendarId = calendarIdB;

        if (Platform.isAndroid && eventColorB != null) {
          updatedEvent.updateEventColor(eventColorB);
        }

        final editResult = await deviceCalendarPlugin.createOrUpdateEvent(
          updatedEvent,
          instanceStartDate: targetStart,
          instanceEndDate: targetEnd,
          updateFollowingInstances: true,
        );
        expect(editResult?.isSuccess, true);
        final newSeriesEventId = editResult!.data!;
        print('LOG_REPRO: [Test Edit This and Future] Split series event ID: $newSeriesEventId');

        await Future.delayed(const Duration(seconds: 2));

        // Verify instances in calendarIdA: only first 2 instances remain
        final afterEditResultA = await deviceCalendarPlugin.retrieveEvents(calendarIdA, retrieveParams);
        expect(afterEditResultA.isSuccess, true);
        final afterEditEventsA = afterEditResultA.data!.where((e) => e.eventId == eventId).toList();
        expect(afterEditEventsA.length, 2);
        for (final instance in afterEditEventsA) {
          expect(instance.title, 'Original Recurring Event');
          expect(instance.start!.hour, 10);
        }

        // Verify instances in calendarIdB: remaining 3 instances should be here
        final afterEditResultB = await deviceCalendarPlugin.retrieveEvents(calendarIdB, retrieveParams);
        expect(afterEditResultB.isSuccess, true);
        final afterEditEventsB = afterEditResultB.data!.where((e) => e.eventId == newSeriesEventId).toList();
        expect(afterEditEventsB.length, 3);

        afterEditEventsB.sort((a, b) => a.start!.compareTo(b.start!));

        expect(afterEditEventsB[0].start!.day, originalTargetDay + 1);
        for (final instance in afterEditEventsB) {
          expect(instance.title, 'This and Future - Updated Title');
          expect(instance.start!.hour, 12);
          if (Platform.isAndroid && eventColorB != null) {
            expect(instance.color, eventColorB?.color);
          }
        }
        print('LOG_REPRO: [Test Edit This and Future] Passed');
      });
    });
  });
}
