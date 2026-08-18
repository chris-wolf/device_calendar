#include <flutter/method_call.h>
#include <flutter/method_result_functions.h>
#include <flutter/standard_method_codec.h>
#include <gtest/gtest.h>
#include <windows.h>

#include <memory>
#include <string>
#include <variant>

#include "device_calendar_plugin.h"

namespace device_calendar {
namespace test {

namespace {

using flutter::EncodableList;
using flutter::EncodableMap;
using flutter::EncodableValue;
using flutter::MethodCall;
using flutter::MethodResultFunctions;

}  // namespace

TEST(DeviceCalendarPlugin, RetrieveEventColorsReturnsEmptyListOnWindows) {
  DeviceCalendarPlugin plugin;
  bool success_called = false;
  plugin.HandleMethodCall(
      MethodCall("retrieveEventColors", std::make_unique<EncodableValue>()),
      std::make_unique<MethodResultFunctions<>>(
          [&success_called](const EncodableValue* result) {
            success_called = true;
            EXPECT_TRUE(std::holds_alternative<EncodableList>(*result));
          },
          nullptr, nullptr));

  EXPECT_TRUE(success_called);
}

TEST(DeviceCalendarPlugin, ShowiOSEventModalReturnsErrorOnWindows) {
  DeviceCalendarPlugin plugin;
  bool error_called = false;
  plugin.HandleMethodCall(
      MethodCall("showiOSEventModal", std::make_unique<EncodableValue>()),
      std::make_unique<MethodResultFunctions<>>(
          nullptr,
          [&error_called](const std::string& error_code,
                          const std::string& error_message,
                          const EncodableValue* error_details) {
            error_called = true;
            EXPECT_EQ(error_code, "UNSUPPORTED_PLATFORM");
          },
          nullptr));

  EXPECT_TRUE(error_called);
}

}  // namespace test
}  // namespace device_calendar
