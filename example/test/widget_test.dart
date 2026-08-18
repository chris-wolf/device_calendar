import 'package:flutter_test/flutter_test.dart';

import 'package:device_calendar_example/main.dart';

void main() {
  testWidgets('App renders successfully smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());
    expect(find.byType(MyApp), findsOneWidget);
  });
}
