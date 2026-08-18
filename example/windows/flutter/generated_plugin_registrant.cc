//
//  Generated file. Do not edit.
//

// clang-format off

#include "generated_plugin_registrant.h"

#include <device_calendar/device_calendar_plugin_c_api.h>
#include <flutter_timezone/flutter_timezone_plugin_c_api.h>

void RegisterPlugins(flutter::PluginRegistry* registry) {
  DeviceCalendarPluginCApiRegisterWithRegistrar(
      registry->GetRegistrarForPlugin("DeviceCalendarPluginCApi"));
  FlutterTimezonePluginCApiRegisterWithRegistrar(
      registry->GetRegistrarForPlugin("FlutterTimezonePluginCApi"));
}
