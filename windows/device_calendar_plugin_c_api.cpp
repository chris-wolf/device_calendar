#include "include/device_calendar/device_calendar_plugin_c_api.h"

#include <flutter/plugin_registrar_windows.h>

#include "device_calendar_plugin.h"

void DeviceCalendarPluginCApiRegisterWithRegistrar(
    FlutterDesktopPluginRegistrarRef registrar) {
  device_calendar::DeviceCalendarPlugin::RegisterWithRegistrar(
      flutter::PluginRegistrarManager::GetInstance()
          ->GetRegistrar<flutter::PluginRegistrarWindows>(registrar));
}
