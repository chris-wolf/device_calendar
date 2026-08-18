#ifndef FLUTTER_PLUGIN_DEVICE_CALENDAR_PLUGIN_H_
#define FLUTTER_PLUGIN_DEVICE_CALENDAR_PLUGIN_H_

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <winrt/Windows.Foundation.h>

#include <memory>

namespace device_calendar {

class DeviceCalendarPlugin : public flutter::Plugin {
 public:
  static void RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar);

  DeviceCalendarPlugin();

  virtual ~DeviceCalendarPlugin();

  // Disallow copy and assign.
  DeviceCalendarPlugin(const DeviceCalendarPlugin&) = delete;
  DeviceCalendarPlugin& operator=(const DeviceCalendarPlugin&) = delete;

  // Called when a method is called on this plugin's channel from Dart.
  void HandleMethodCall(
      const flutter::MethodCall<flutter::EncodableValue> &method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

 private:
  winrt::fire_and_forget CheckOrRequestPermissions(
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget RetrieveCalendars(
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget CreateCalendar(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget DeleteCalendar(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget UpdateCalendarColor(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget RetrieveEvents(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget CreateOrUpdateEvent(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget DeleteEvent(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);

  winrt::fire_and_forget DeleteEventInstance(
      const flutter::MethodCall<flutter::EncodableValue>& method_call,
      std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result);
};

}  // namespace device_calendar

#endif  // FLUTTER_PLUGIN_DEVICE_CALENDAR_PLUGIN_H_
