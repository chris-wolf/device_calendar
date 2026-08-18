import 'package:flutter/material.dart';

import 'common/app_routes.dart';
import 'presentation/pages/calendars.dart';
import 'presentation/pages/integration_test_runner_page.dart';

import 'dart:io';
import 'dart:ui';

void main() {
  WidgetsFlutterBinding.ensureInitialized();

  FlutterError.onError = (FlutterErrorDetails details) {
    try {
      File(r'c:\Users\windo\Develop\device_calendar\flutter_fatal.log').writeAsStringSync(
        'FlutterError: ${details.exceptionAsString()}\n${details.stack}\n',
        mode: FileMode.append,
      );
    } catch (_) {}
    FlutterError.presentError(details);
  };

  PlatformDispatcher.instance.onError = (Object error, StackTrace stack) {
    try {
      File(r'c:\Users\windo\Develop\device_calendar\flutter_fatal.log').writeAsStringSync(
        'PlatformDispatcher error: $error\n$stack\n',
        mode: FileMode.append,
      );
    } catch (_) {}
    return true;
  };

  runApp(const MyApp());
}

class MyApp extends StatefulWidget {
  const MyApp({Key? key}) : super(key: key);

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      theme: ThemeData(),
      themeMode: ThemeMode.system,
      darkTheme: ThemeData.dark(),
      routes: {
        AppRoutes.calendars: (context) {
          return const CalendarsPage(key: Key('calendarsPage'));
        },
        AppRoutes.integrationTestRunner: (context) {
          return const IntegrationTestRunnerPage();
        },
      },
    );
  }
}
