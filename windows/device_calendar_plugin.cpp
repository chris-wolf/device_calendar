#include "device_calendar_plugin.h"

#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <unknwn.h>
#include <restrictederrorinfo.h>

#ifdef Required
#undef Required
#endif
#ifdef Optional
#undef Optional
#endif
#ifdef OPTIONAL
#undef OPTIONAL
#endif
#ifdef Free
#undef Free
#endif

#include <flutter/method_channel.h>
#include <flutter/plugin_registrar_windows.h>
#include <flutter/standard_method_codec.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.ApplicationModel.Appointments.h>
#include <winrt/Windows.UI.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <iomanip>
#include <memory>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::Foundation::Collections;
using namespace Windows::ApplicationModel::Appointments;
using namespace Windows::UI;

namespace device_calendar {

namespace {

// ==========================================
// Helper Functions: DateTime, Color, & JSON
// ==========================================

// Difference between Windows FileTime epoch (1601-01-01) and Unix epoch (1970-01-01) in 100-ns ticks.
constexpr int64_t kUnixEpochOffsetTicks = 116444736000000000LL;
constexpr int64_t kTicksPerMillisecond = 10000LL;

inline int64_t DateTimeToEpochMillis(const DateTime& dt) {
  int64_t ticks = dt.time_since_epoch().count();
  int64_t unixTicks = ticks - kUnixEpochOffsetTicks;
  return unixTicks / kTicksPerMillisecond;
}

inline DateTime EpochMillisToDateTime(int64_t epochMs) {
  int64_t ticks = epochMs * kTicksPerMillisecond + kUnixEpochOffsetTicks;
  return DateTime{
    std::chrono::duration_cast<DateTime::duration>(
      std::chrono::duration<int64_t, std::ratio<1, 10000000>>(ticks))
  };
}

inline int64_t ColorToArgb(const Color& color) {
  return (static_cast<int64_t>(color.A) << 24) |
         (static_cast<int64_t>(color.R) << 16) |
         (static_cast<int64_t>(color.G) << 8) |
         static_cast<int64_t>(color.B);
}

inline Color ArgbToColor(int64_t argb) {
  Color c;
  c.A = static_cast<uint8_t>((argb >> 24) & 0xFF);
  c.R = static_cast<uint8_t>((argb >> 16) & 0xFF);
  c.G = static_cast<uint8_t>((argb >> 8) & 0xFF);
  c.B = static_cast<uint8_t>(argb & 0xFF);
  return c;
}

inline Color HexStringToColor(const std::string& hex) {
  std::string clean = hex;
  if (clean.rfind("0x", 0) == 0 || clean.rfind("0X", 0) == 0) {
    clean = clean.substr(2);
  } else if (clean.rfind("#", 0) == 0) {
    clean = clean.substr(1);
  }
  uint64_t val = 0;
  try {
    val = std::stoull(clean, nullptr, 16);
  } catch (...) {
    val = 0xFFFF0000;  // Default to red if parse fails
  }
  if (clean.length() <= 6) {
    val |= 0xFF000000;
  }
  return ArgbToColor(static_cast<int64_t>(val));
}

inline std::string EscapeJson(const std::string& s) {
  std::ostringstream ss;
  for (char c : s) {
    switch (c) {
      case '"': ss << "\\\""; break;
      case '\\': ss << "\\\\"; break;
      case '\b': ss << "\\b"; break;
      case '\f': ss << "\\f"; break;
      case '\n': ss << "\\n"; break;
      case '\r': ss << "\\r"; break;
      case '\t': ss << "\\t"; break;
      default:
        if ('\x00' <= c && c <= '\x1f') {
          ss << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<int>(c);
        } else {
          ss << c;
        }
    }
  }
  return ss.str();
}

inline std::string FormatIso8601(const DateTime& dt) {
  int64_t epochMs = DateTimeToEpochMillis(dt);
  std::time_t t = static_cast<std::time_t>(epochMs / 1000);
  std::tm tm{};
  gmtime_s(&tm, &t);
  char buf[32];
  std::strftime(buf, sizeof(buf), "%Y%m%dT%H%M%SZ", &tm);
  return std::string(buf);
}

inline DateTime ParseIso8601(const std::string& dateStr) {
  // Simple ISO 8601 parser for strings like 2026-12-31T00:00:00Z or 20261231T000000Z
  std::tm tm{};
  int y = 0, m = 0, d = 0, h = 0, min = 0, sec = 0;
  if (sscanf_s(dateStr.c_str(), "%4d-%2d-%2dT%2d:%2d:%2d", &y, &m, &d, &h, &min, &sec) == 6 ||
      sscanf_s(dateStr.c_str(), "%4d%2d%2dT%2d%2d%2d", &y, &m, &d, &h, &min, &sec) == 6) {
    tm.tm_year = y - 1900;
    tm.tm_mon = m - 1;
    tm.tm_mday = d;
    tm.tm_hour = h;
    tm.tm_min = min;
    tm.tm_sec = sec;
    std::time_t t = _mkgmtime(&tm);
    return EpochMillisToDateTime(static_cast<int64_t>(t) * 1000);
  }
  return winrt::clock::now();
}

// Extractors for flutter::EncodableValue
template <typename T>
std::optional<T> GetValue(const flutter::EncodableMap& map, const std::string& key) {
  auto it = map.find(flutter::EncodableValue(key));
  if (it != map.end() && !it->second.IsNull()) {
    if (std::holds_alternative<T>(it->second)) {
      return std::get<T>(it->second);
    }
  }
  return std::nullopt;
}

std::optional<int64_t> GetInt64Value(const flutter::EncodableMap& map, const std::string& key) {
  auto it = map.find(flutter::EncodableValue(key));
  if (it != map.end() && !it->second.IsNull()) {
    if (std::holds_alternative<int32_t>(it->second)) {
      return static_cast<int64_t>(std::get<int32_t>(it->second));
    }
    if (std::holds_alternative<int64_t>(it->second)) {
      return std::get<int64_t>(it->second);
    }
  }
  return std::nullopt;
}

std::string GetStringValue(const flutter::EncodableMap& map, const std::string& key, const std::string& fallback = "") {
  auto val = GetValue<std::string>(map, key);
  return val.value_or(fallback);
}

// Request AppointmentStore (tries AllCalendarsReadWrite then falls back to AppCalendarsReadWrite)
IAsyncOperation<AppointmentStore> GetAppointmentStore() {
  try {
    auto store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AllCalendarsReadWrite);
    if (store) {
      co_return store;
    }
  } catch (...) {
  }
  try {
    auto store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AppCalendarsReadWrite);
    co_return store;
  } catch (...) {
    co_return nullptr;
  }
}

}  // namespace

// ==========================================
// Plugin Implementation
// ==========================================

// static
void DeviceCalendarPlugin::RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar) {
  auto channel = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      registrar->messenger(), "plugins.builttoroam.com/device_calendar",
      &flutter::StandardMethodCodec::GetInstance());

  auto plugin = std::make_unique<DeviceCalendarPlugin>();

  channel->SetMethodCallHandler(
      [plugin_pointer = plugin.get()](const auto &call, auto result) {
        plugin_pointer->HandleMethodCall(call, std::move(result));
      });

  registrar->AddPlugin(std::move(plugin));
}

DeviceCalendarPlugin::DeviceCalendarPlugin() {}

DeviceCalendarPlugin::~DeviceCalendarPlugin() {}

void DeviceCalendarPlugin::HandleMethodCall(
    const flutter::MethodCall<flutter::EncodableValue> &method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  const std::string& method_name = method_call.method_name();

  if (method_name == "hasPermissions" || method_name == "requestPermissions") {
    CheckOrRequestPermissions(std::move(result));
  } else if (method_name == "retrieveCalendars") {
    RetrieveCalendars(std::move(result));
  } else if (method_name == "retrieveEvents") {
    RetrieveEvents(method_call, std::move(result));
  } else if (method_name == "createCalendar") {
    CreateCalendar(method_call, std::move(result));
  } else if (method_name == "deleteCalendar") {
    DeleteCalendar(method_call, std::move(result));
  } else if (method_name == "updateCalendarColor") {
    UpdateCalendarColor(method_call, std::move(result));
  } else if (method_name == "createOrUpdateEvent") {
    CreateOrUpdateEvent(method_call, std::move(result));
  } else if (method_name == "deleteEvent") {
    DeleteEvent(method_call, std::move(result));
  } else if (method_name == "deleteEventInstance") {
    DeleteEventInstance(method_call, std::move(result));
  } else if (method_name == "retrieveEventColors" || method_name == "retrieveCalendarColors") {
    result->Success(flutter::EncodableValue(flutter::EncodableList{}));
  } else if (method_name == "showiOSEventModal") {
    result->Error("UNSUPPORTED_PLATFORM", "showiOSEventModal is only supported on iOS");
  } else {
    result->NotImplemented();
  }
}

// 1. Permissions
fire_and_forget DeviceCalendarPlugin::CheckOrRequestPermissions(
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    auto store = co_await GetAppointmentStore();
    result->Success(flutter::EncodableValue(store != nullptr));
  } catch (...) {
    result->Success(flutter::EncodableValue(false));
  }
}

// 2. Retrieve Calendars
fire_and_forget DeviceCalendarPlugin::RetrieveCalendars(
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto calendars = co_await store.FindAppointmentCalendarsAsync(FindAppointmentCalendarsOptions::IncludeHidden);
    std::ostringstream json;
    json << "[";
    bool first = true;

    for (const auto& cal : calendars) {
      if (!first) json << ",";
      first = false;

      std::string id = winrt::to_string(cal.LocalId());
      std::string name = winrt::to_string(cal.DisplayName());
      bool isReadOnly = !cal.CanCreateOrUpdateAppointments();
      int64_t colorArgb = ColorToArgb(cal.DisplayColor());
      std::string accountName = winrt::to_string(cal.SourceDisplayName());
      std::string accountType = winrt::to_string(cal.UserDataAccountId());
      if (accountType.empty()) accountType = "Local";

      json << "{"
           << "\"id\":\"" << EscapeJson(id) << "\","
           << "\"name\":\"" << EscapeJson(name) << "\","
           << "\"isReadOnly\":" << (isReadOnly ? "true" : "false") << ","
           << "\"isDefault\":" << (cal.IsHidden() ? "false" : "true") << ","
           << "\"color\":" << colorArgb << ","
           << "\"accountName\":\"" << EscapeJson(accountName) << "\","
           << "\"accountType\":\"" << EscapeJson(accountType) << "\""
           << "}";
    }

    json << "]";
    result->Success(flutter::EncodableValue(json.str()));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while retrieving calendars");
  }
}

// 3. Create Calendar
fire_and_forget DeviceCalendarPlugin::CreateCalendar(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for createCalendar");
      co_return;
    }

    std::string calendarName = GetStringValue(*args, "calendarName");
    std::string calendarColor = GetStringValue(*args, "calendarColor", "0xFFFF0000");

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.CreateAppointmentCalendarAsync(winrt::to_hstring(calendarName));
    if (!cal) {
      result->Error("500", "Failed to create appointment calendar");
      co_return;
    }

    cal.DisplayColor(HexStringToColor(calendarColor));
    co_await cal.SaveAsync();

    result->Success(flutter::EncodableValue(winrt::to_string(cal.LocalId())));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while creating calendar");
  }
}

// 4. Delete Calendar
fire_and_forget DeviceCalendarPlugin::DeleteCalendar(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for deleteCalendar");
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Error("404", "Calendar not found");
      co_return;
    }

    co_await cal.DeleteAsync();
    result->Success(flutter::EncodableValue(true));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while deleting calendar");
  }
}

// 5. Update Calendar Color
fire_and_forget DeviceCalendarPlugin::UpdateCalendarColor(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Success(flutter::EncodableValue(false));
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    auto colorOpt = GetInt64Value(*args, "calendarColor");

    if (calendarId.empty() || !colorOpt.has_value()) {
      result->Success(flutter::EncodableValue(false));
      co_return;
    }

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Success(flutter::EncodableValue(false));
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Success(flutter::EncodableValue(false));
      co_return;
    }

    cal.DisplayColor(ArgbToColor(colorOpt.value()));
    co_await cal.SaveAsync();
    result->Success(flutter::EncodableValue(true));
  } catch (...) {
    result->Success(flutter::EncodableValue(false));
  }
}

// 6. Retrieve Events
fire_and_forget DeviceCalendarPlugin::RetrieveEvents(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for retrieveEvents");
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    auto startMsOpt = GetInt64Value(*args, "startDate");
    auto endMsOpt = GetInt64Value(*args, "endDate");

    std::vector<std::string> filterEventIds;
    auto itIds = args->find(flutter::EncodableValue("eventIds"));
    if (itIds != args->end() && !itIds->second.IsNull()) {
      if (const auto* list = std::get_if<flutter::EncodableList>(&itIds->second)) {
        for (const auto& item : *list) {
          if (const auto* str = std::get_if<std::string>(&item)) {
            filterEventIds.push_back(*str);
          }
        }
      }
    }

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Error("404", "Calendar not found");
      co_return;
    }

    std::vector<Appointment> appointments;

    if (startMsOpt.has_value() && endMsOpt.has_value()) {
      DateTime startDt = EpochMillisToDateTime(startMsOpt.value());
      DateTime endDt = EpochMillisToDateTime(endMsOpt.value());
      TimeSpan duration = endDt - startDt;
      if (duration.count() < 0) duration = TimeSpan{0};

      FindAppointmentsOptions options;
      options.IncludeHidden(true);

      auto results = co_await cal.FindAppointmentsAsync(startDt, duration, options);
      for (const auto& app : results) {
        if (!filterEventIds.empty()) {
          std::string id = winrt::to_string(app.LocalId().empty() ? app.RoamingId() : app.LocalId());
          if (std::find(filterEventIds.begin(), filterEventIds.end(), id) == filterEventIds.end()) {
            continue;
          }
        }
        appointments.push_back(app);
      }
    } else if (!filterEventIds.empty()) {
      for (const auto& idStr : filterEventIds) {
        try {
          auto app = co_await cal.GetAppointmentAsync(winrt::to_hstring(idStr));
          if (app) appointments.push_back(app);
        } catch (...) {}
      }
    }

    std::ostringstream json;
    json << "[";
    bool firstEvent = true;

    for (const auto& app : appointments) {
      if (!firstEvent) json << ",";
      firstEvent = false;

      std::string id = winrt::to_string(app.LocalId().empty() ? app.RoamingId() : app.LocalId());
      std::string title = winrt::to_string(app.Subject());
      std::string description = winrt::to_string(app.Details());
      int64_t startMs = DateTimeToEpochMillis(app.StartTime());
      int64_t endMs = DateTimeToEpochMillis(app.StartTime() + app.Duration());
      bool allDay = app.AllDay();
      std::string location = winrt::to_string(app.Location());
      std::string url = app.Uri() ? winrt::to_string(app.Uri().RawUri()) : "";

      std::string availability = "BUSY";
      switch (app.BusyStatus()) {
        case AppointmentBusyStatus::Free: availability = "FREE"; break;
        case AppointmentBusyStatus::Tentative: availability = "TENTATIVE"; break;
        case AppointmentBusyStatus::Busy: availability = "BUSY"; break;
        case AppointmentBusyStatus::OutOfOffice:
        case AppointmentBusyStatus::WorkingElsewhere: availability = "UNAVAILABLE"; break;
      }

      std::string status = "CONFIRMED";

      json << "{"
           << "\"calendarId\":\"" << EscapeJson(calendarId) << "\","
           << "\"eventId\":\"" << EscapeJson(id) << "\","
           << "\"eventTitle\":\"" << EscapeJson(title) << "\","
           << "\"eventDescription\":\"" << EscapeJson(description) << "\","
           << "\"eventStartDate\":" << startMs << ","
           << "\"eventStartTimeZone\":\"UTC\","
           << "\"eventEndDate\":" << endMs << ","
           << "\"eventEndTimeZone\":\"UTC\","
           << "\"eventAllDay\":" << (allDay ? "true" : "false") << ","
           << "\"eventLocation\":\"" << EscapeJson(location) << "\","
           << "\"eventURL\":" << (url.empty() ? "null" : ("\"" + EscapeJson(url) + "\"")) << ","
           << "\"availability\":\"" << availability << "\","
           << "\"eventStatus\":\"" << status << "\"";

      // Attendees
      auto invitees = app.Invitees();
      if (invitees && invitees.Size() > 0) {
        json << ",\"attendees\":[";
        bool firstInvitee = true;
        for (const auto& inv : invitees) {
          if (!firstInvitee) json << ",";
          firstInvitee = false;
          std::string invName = winrt::to_string(inv.DisplayName());
          std::string invEmail = winrt::to_string(inv.Address());
          int role = static_cast<int>(inv.Role()) + 1; // 1: Required, 2: Optional, 3: Resource
          json << "{"
               << "\"name\":\"" << EscapeJson(invName) << "\","
               << "\"emailAddress\":\"" << EscapeJson(invEmail) << "\","
               << "\"role\":" << role << ","
               << "\"isOrganizer\":false"
               << "}";
        }
        json << "]";
      }

      // Reminders
      if (app.Reminder()) {
        auto remDuration = app.Reminder().Value();
        int minutes = static_cast<int>(remDuration.count() / (kTicksPerMillisecond * 1000 * 60));
        json << ",\"reminders\":[{\"minutes\":" << minutes << "}]";
      }

      // Recurrence
      auto recurrence = app.Recurrence();
      if (recurrence) {
        std::string freq = "DAILY";
        switch (recurrence.Unit()) {
          case AppointmentRecurrenceUnit::Daily: freq = "DAILY"; break;
          case AppointmentRecurrenceUnit::Weekly: freq = "WEEKLY"; break;
          case AppointmentRecurrenceUnit::Monthly:
          case AppointmentRecurrenceUnit::MonthlyOnDay: freq = "MONTHLY"; break;
          case AppointmentRecurrenceUnit::Yearly:
          case AppointmentRecurrenceUnit::YearlyOnDay: freq = "YEARLY"; break;
        }

        json << ",\"recurrenceRule\":{"
             << "\"freq\":\"" << freq << "\","
             << "\"interval\":" << recurrence.Interval();

        if (recurrence.Occurrences()) {
          json << ",\"count\":" << recurrence.Occurrences().Value();
        }

        if (recurrence.Until()) {
          json << ",\"until\":\"" << FormatIso8601(recurrence.Until().Value()) << "\"";
        }

        auto days = recurrence.DaysOfWeek();
        if (days != AppointmentDaysOfWeek::None) {
          json << ",\"byday\":[";
          bool firstDay = true;
          auto appendDay = [&](AppointmentDaysOfWeek dayFlag, const char* name) {
            if ((days & dayFlag) == dayFlag) {
              if (!firstDay) json << ",";
              firstDay = false;
              json << "\"" << name << "\"";
            }
          };
          appendDay(AppointmentDaysOfWeek::Sunday, "SU");
          appendDay(AppointmentDaysOfWeek::Monday, "MO");
          appendDay(AppointmentDaysOfWeek::Tuesday, "TU");
          appendDay(AppointmentDaysOfWeek::Wednesday, "WE");
          appendDay(AppointmentDaysOfWeek::Thursday, "TH");
          appendDay(AppointmentDaysOfWeek::Friday, "FR");
          appendDay(AppointmentDaysOfWeek::Saturday, "SA");
          json << "]";
        }

        json << "}";
      }

      json << "}";
    }

    json << "]";
    result->Success(flutter::EncodableValue(json.str()));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while retrieving events");
  }
}

// 7. Create or Update Event
fire_and_forget DeviceCalendarPlugin::CreateOrUpdateEvent(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for createOrUpdateEvent");
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    std::string eventId = GetStringValue(*args, "eventId");
    std::string title = GetStringValue(*args, "eventTitle");
    std::string description = GetStringValue(*args, "eventDescription");
    auto startMsOpt = GetInt64Value(*args, "eventStartDate");
    auto endMsOpt = GetInt64Value(*args, "eventEndDate");
    auto allDayOpt = GetValue<bool>(*args, "eventAllDay");
    std::string location = GetStringValue(*args, "eventLocation");
    std::string urlStr = GetStringValue(*args, "eventURL");
    std::string availability = GetStringValue(*args, "availability", "BUSY");

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Error("404", "Calendar not found");
      co_return;
    }

    Appointment appointment;
    if (!eventId.empty()) {
      try {
        appointment = co_await cal.GetAppointmentAsync(winrt::to_hstring(eventId));
      } catch (...) {}
    }
    if (!appointment) {
      appointment = Appointment();
    }

    appointment.Subject(winrt::to_hstring(title));
    appointment.Details(winrt::to_hstring(description));
    appointment.Location(winrt::to_hstring(location));

    bool allDay = allDayOpt.value_or(false);
    appointment.AllDay(allDay);

    int64_t startMs = startMsOpt.value_or(0);
    int64_t endMs = endMsOpt.value_or(startMs);

    DateTime startDt = EpochMillisToDateTime(startMs);
    appointment.StartTime(startDt);

    if (endMs >= startMs) {
      DateTime endDt = EpochMillisToDateTime(endMs);
      appointment.Duration(endDt - startDt);
    } else {
      appointment.Duration(TimeSpan{0});
    }

    // Availability
    if (availability == "FREE") {
      appointment.BusyStatus(AppointmentBusyStatus::Free);
    } else if (availability == "TENTATIVE") {
      appointment.BusyStatus(AppointmentBusyStatus::Tentative);
    } else if (availability == "UNAVAILABLE") {
      appointment.BusyStatus(AppointmentBusyStatus::OutOfOffice);
    } else {
      appointment.BusyStatus(AppointmentBusyStatus::Busy);
    }

    // URL
    if (!urlStr.empty()) {
      try {
        appointment.Uri(Uri(winrt::to_hstring(urlStr)));
      } catch (...) {}
    }

    // Reminders
    auto itReminders = args->find(flutter::EncodableValue("reminders"));
    if (itReminders != args->end() && !itReminders->second.IsNull()) {
      if (const auto* remList = std::get_if<flutter::EncodableList>(&itReminders->second)) {
        if (!remList->empty()) {
          if (const auto* remMap = std::get_if<flutter::EncodableMap>(&remList->front())) {
            auto minOpt = GetInt64Value(*remMap, "minutes");
            if (minOpt.has_value()) {
              TimeSpan rem = std::chrono::duration_cast<TimeSpan>(std::chrono::minutes(minOpt.value()));
              appointment.Reminder(rem);
            }
          }
        }
      }
    }

    // Recurrence Rule
    auto itRrule = args->find(flutter::EncodableValue("recurrenceRule"));
    if (itRrule != args->end() && !itRrule->second.IsNull()) {
      if (const auto* rruleMap = std::get_if<flutter::EncodableMap>(&itRrule->second)) {
        AppointmentRecurrence recurrence;
        std::string freq = GetStringValue(*rruleMap, "freq", "DAILY");
        if (freq == "DAILY") recurrence.Unit(AppointmentRecurrenceUnit::Daily);
        else if (freq == "WEEKLY") recurrence.Unit(AppointmentRecurrenceUnit::Weekly);
        else if (freq == "MONTHLY") recurrence.Unit(AppointmentRecurrenceUnit::Monthly);
        else if (freq == "YEARLY") recurrence.Unit(AppointmentRecurrenceUnit::Yearly);

        auto intervalOpt = GetInt64Value(*rruleMap, "interval");
        recurrence.Interval(static_cast<uint32_t>(intervalOpt.value_or(1)));

        auto countOpt = GetInt64Value(*rruleMap, "count");
        if (countOpt.has_value() && countOpt.value() > 0) {
          recurrence.Occurrences(static_cast<uint32_t>(countOpt.value()));
        }

        std::string untilStr = GetStringValue(*rruleMap, "until");
        if (!untilStr.empty()) {
          recurrence.Until(ParseIso8601(untilStr));
        }

        auto itByDay = rruleMap->find(flutter::EncodableValue("byday"));
        if (itByDay != rruleMap->end() && !itByDay->second.IsNull()) {
          if (const auto* byDayList = std::get_if<flutter::EncodableList>(&itByDay->second)) {
            AppointmentDaysOfWeek days = AppointmentDaysOfWeek::None;
            for (const auto& dayVal : *byDayList) {
              if (const auto* dayStr = std::get_if<std::string>(&dayVal)) {
                if (*dayStr == "SU") days |= AppointmentDaysOfWeek::Sunday;
                else if (*dayStr == "MO") days |= AppointmentDaysOfWeek::Monday;
                else if (*dayStr == "TU") days |= AppointmentDaysOfWeek::Tuesday;
                else if (*dayStr == "WE") days |= AppointmentDaysOfWeek::Wednesday;
                else if (*dayStr == "TH") days |= AppointmentDaysOfWeek::Thursday;
                else if (*dayStr == "FR") days |= AppointmentDaysOfWeek::Friday;
                else if (*dayStr == "SA") days |= AppointmentDaysOfWeek::Saturday;
              }
            }
            if (days != AppointmentDaysOfWeek::None) {
              recurrence.DaysOfWeek(days);
            }
          }
        }

        appointment.Recurrence(recurrence);
      }
    }

    // Attendees
    auto itAttendees = args->find(flutter::EncodableValue("attendees"));
    if (itAttendees != args->end() && !itAttendees->second.IsNull()) {
      if (const auto* attList = std::get_if<flutter::EncodableList>(&itAttendees->second)) {
        appointment.Invitees().Clear();
        for (const auto& attVal : *attList) {
          if (const auto* attMap = std::get_if<flutter::EncodableMap>(&attVal)) {
            std::string name = GetStringValue(*attMap, "name");
            std::string email = GetStringValue(*attMap, "emailAddress");
            auto roleOpt = GetInt64Value(*attMap, "role");

            AppointmentInvitee invitee;
            invitee.DisplayName(winrt::to_hstring(name));
            invitee.Address(winrt::to_hstring(email));
            if (roleOpt.has_value()) {
              int r = static_cast<int>(roleOpt.value());
              if (r == 1) invitee.Role(AppointmentParticipantRole::RequiredAttendee);
              else if (r == 2) invitee.Role(AppointmentParticipantRole::OptionalAttendee);
              else if (r == 3) invitee.Role(AppointmentParticipantRole::Resource);
            }
            appointment.Invitees().Append(invitee);
          }
        }
      }
    }

    co_await cal.SaveAppointmentAsync(appointment);

    std::string savedId = winrt::to_string(appointment.LocalId());
    if (savedId.empty()) savedId = winrt::to_string(appointment.RoamingId());

    result->Success(flutter::EncodableValue(savedId));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while creating or updating event");
  }
}

// 8. Delete Event
fire_and_forget DeviceCalendarPlugin::DeleteEvent(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for deleteEvent");
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    std::string eventId = GetStringValue(*args, "eventId");

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Error("404", "Calendar not found");
      co_return;
    }

    co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
    result->Success(flutter::EncodableValue(true));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while deleting event");
  }
}

// 9. Delete Event Instance
fire_and_forget DeviceCalendarPlugin::DeleteEventInstance(
    const flutter::MethodCall<flutter::EncodableValue>& method_call,
    std::unique_ptr<flutter::MethodResult<flutter::EncodableValue>> result) {
  try {
    const auto* args = std::get_if<flutter::EncodableMap>(method_call.arguments());
    if (!args) {
      result->Error("400", "Invalid arguments for deleteEventInstance");
      co_return;
    }

    std::string calendarId = GetStringValue(*args, "calendarId");
    std::string eventId = GetStringValue(*args, "eventId");
    auto startMsOpt = GetInt64Value(*args, "eventStartDate");

    auto store = co_await GetAppointmentStore();
    if (!store) {
      result->Error("400", "Unable to obtain appointment store");
      co_return;
    }

    auto cal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(calendarId));
    if (!cal) {
      result->Error("404", "Calendar not found");
      co_return;
    }

    if (startMsOpt.has_value()) {
      DateTime instanceDt = EpochMillisToDateTime(startMsOpt.value());
      co_await cal.DeleteAppointmentInstanceAsync(winrt::to_hstring(eventId), instanceDt);
    } else {
      co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
    }

    result->Success(flutter::EncodableValue(true));
  } catch (const hresult_error& ex) {
    result->Error(std::to_string(ex.code()), winrt::to_string(ex.message()));
  } catch (const std::exception& ex) {
    result->Error("500", ex.what());
  } catch (...) {
    result->Error("502", "Unknown error while deleting event instance");
  }
}

}  // namespace device_calendar
