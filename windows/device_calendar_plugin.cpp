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

// Request AppointmentStore for read/enumeration operations.
// Uses AllCalendarsReadWrite first so that FindAppointmentCalendarsAsync can
// enumerate all calendars (app-created and system). AppCalendarsReadWrite's
// FindAppointmentCalendarsAsync does not reliably list calendars on desktop apps.
IAsyncOperation<AppointmentStore> GetAppointmentStoreForRead() {
  AppointmentStore store{nullptr};
  bool fallback = false;
  try {
    store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AllCalendarsReadWrite);
  } catch (...) {
    fallback = true;
  }
  if (fallback || !store) {
    try {
      store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AppCalendarsReadWrite);
    } catch (...) {
      store = nullptr;
    }
  }
  co_return store;
}

// Request AppointmentStore for write operations.
// Prefer AppCalendarsReadWrite first — it works reliably for creating, updating,
// and deleting events in app-created calendars on desktop (non-UWP) Flutter apps.
// AllCalendarsReadWrite can trigger hidden OS-level consent dialogs for write
// operations (SaveAppointmentAsync) in non-UWP apps, causing indefinite hangs.
// Fall back to AllCalendarsReadWrite only if AppCalendarsReadWrite fails.
IAsyncOperation<AppointmentStore> GetAppointmentStore() {
  AppointmentStore store{nullptr};
  bool fallback = false;
  try {
    store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AppCalendarsReadWrite);
  } catch (...) {
    fallback = true;
  }
  if (fallback || !store) {
    try {
      store = co_await AppointmentManager::RequestStoreAsync(AppointmentStoreAccessType::AllCalendarsReadWrite);
    } catch (...) {
      store = nullptr;
    }
  }
  co_return store;
}

// Safe Calendar Property Accessors
inline std::string SafeGetCalendarId(const AppointmentCalendar& cal) {
  try { return winrt::to_string(cal.LocalId()); } catch (...) { return ""; }
}
inline std::string SafeGetCalendarName(const AppointmentCalendar& cal) {
  try { return winrt::to_string(cal.DisplayName()); } catch (...) { return ""; }
}
inline bool SafeGetCalendarIsReadOnly(const AppointmentCalendar& cal) {
  try { return !cal.CanCreateOrUpdateAppointments(); } catch (...) { return false; }
}
inline bool SafeGetCalendarIsDefault(const AppointmentCalendar& cal) {
  try { return !cal.IsHidden(); } catch (...) { return true; }
}
inline int64_t SafeGetCalendarColor(const AppointmentCalendar& cal) {
  try { return ColorToArgb(cal.DisplayColor()); } catch (...) { return 0xFFFF0000; }
}
inline std::string SafeGetCalendarAccountName(const AppointmentCalendar& cal) {
  try { return winrt::to_string(cal.SourceDisplayName()); } catch (...) { return ""; }
}
inline std::string SafeGetCalendarAccountType(const AppointmentCalendar& cal) {
  try {
    std::string type = winrt::to_string(cal.UserDataAccountId());
    return type.empty() ? "Local" : type;
  } catch (...) {
    return "Local";
  }
}

// Safe Appointment Property Accessors
inline std::string SafeGetEventId(const Appointment& app) {
  try {
    std::string id = winrt::to_string(app.LocalId());
    if (id.empty()) id = winrt::to_string(app.RoamingId());
    return id;
  } catch (...) {
    return "";
  }
}
inline std::string SafeGetEventTitle(const Appointment& app) {
  try { return winrt::to_string(app.Subject()); } catch (...) { return ""; }
}
inline std::string SafeGetEventDescription(const Appointment& app) {
  try { return winrt::to_string(app.Details()); } catch (...) { return ""; }
}
inline int64_t SafeGetEventStartTime(const Appointment& app) {
  try { return DateTimeToEpochMillis(app.StartTime()); } catch (...) { return 0; }
}
inline int64_t SafeGetEventEndTime(const Appointment& app) {
  try { return DateTimeToEpochMillis(app.StartTime() + app.Duration()); } catch (...) { return 0; }
}
inline bool SafeGetEventAllDay(const Appointment& app) {
  try { return app.AllDay(); } catch (...) { return false; }
}
inline std::string SafeGetEventLocation(const Appointment& app) {
  try { return winrt::to_string(app.Location()); } catch (...) { return ""; }
}
inline std::string SafeGetEventUrl(const Appointment& app) {
  try {
    auto uri = app.Uri();
    if (uri) return winrt::to_string(uri.RawUri());
  } catch (...) {}
  return "";
}
inline std::string SafeGetEventAvailability(const Appointment& app) {
  try {
    switch (app.BusyStatus()) {
      case AppointmentBusyStatus::Free: return "FREE";
      case AppointmentBusyStatus::Tentative: return "TENTATIVE";
      case AppointmentBusyStatus::Busy: return "BUSY";
      case AppointmentBusyStatus::OutOfOffice:
      case AppointmentBusyStatus::WorkingElsewhere: return "UNAVAILABLE";
    }
  } catch (...) {}
  return "BUSY";
}

}  // namespace

// ==========================================
// Plugin Implementation
// ==========================================

// static
void DeviceCalendarPlugin::RegisterWithRegistrar(flutter::PluginRegistrarWindows *registrar) {
  try {
    winrt::init_apartment(winrt::apartment_type::single_threaded);
  } catch (...) {}

  auto channel = std::make_unique<flutter::MethodChannel<flutter::EncodableValue>>(
      registrar->messenger(), "plugins.builttoroam.com/device_calendar",
      &flutter::StandardMethodCodec::GetInstance());

  auto plugin = std::make_unique<DeviceCalendarPlugin>(std::move(channel));

  plugin->channel_->SetMethodCallHandler(
      [plugin_pointer = plugin.get()](const auto &call, auto result) {
        plugin_pointer->HandleMethodCall(call, std::move(result));
      });

  registrar->AddPlugin(std::move(plugin));
}

DeviceCalendarPlugin::DeviceCalendarPlugin(std::unique_ptr<flutter::MethodChannel<flutter::EncodableValue>> channel)
    : channel_(std::move(channel)) {}

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
    auto store = co_await GetAppointmentStoreForRead();
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

      std::string id = SafeGetCalendarId(cal);
      std::string name = SafeGetCalendarName(cal);
      bool isReadOnly = SafeGetCalendarIsReadOnly(cal);
      int64_t colorArgb = SafeGetCalendarColor(cal);
      std::string accountName = SafeGetCalendarAccountName(cal);
      std::string accountType = SafeGetCalendarAccountType(cal);
      bool isDefault = SafeGetCalendarIsDefault(cal);

      json << "{"
           << "\"id\":\"" << EscapeJson(id) << "\","
           << "\"name\":\"" << EscapeJson(name) << "\","
           << "\"isReadOnly\":" << (isReadOnly ? "true" : "false") << ","
           << "\"isDefault\":" << (isDefault ? "true" : "false") << ","
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
    try { cal.CanCreateOrUpdateAppointments(true); } catch (...) {}
    co_await cal.SaveAsync();

    std::string calId = SafeGetCalendarId(cal);
    result->Success(flutter::EncodableValue(calId));
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

    auto store = co_await GetAppointmentStoreForRead();
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
    FindAppointmentsOptions options;
    options.IncludeHidden(true);
    try { options.MaxCount(10000); } catch (...) {}
    try {
      options.FetchProperties().Append(AppointmentProperties::Subject());
      options.FetchProperties().Append(AppointmentProperties::Location());
      options.FetchProperties().Append(AppointmentProperties::StartTime());
      options.FetchProperties().Append(AppointmentProperties::Duration());
      options.FetchProperties().Append(AppointmentProperties::AllDay());
      options.FetchProperties().Append(AppointmentProperties::BusyStatus());
      options.FetchProperties().Append(AppointmentProperties::Details());
      options.FetchProperties().Append(AppointmentProperties::Reminder());
      options.FetchProperties().Append(AppointmentProperties::Uri());
      options.FetchProperties().Append(AppointmentProperties::Recurrence());
      options.FetchProperties().Append(AppointmentProperties::Invitees());
      options.FetchProperties().Append(AppointmentProperties::Organizer());
    } catch (...) {}

    if (startMsOpt.has_value() && endMsOpt.has_value()) {
      DateTime startDt = EpochMillisToDateTime(startMsOpt.value());
      DateTime endDt = EpochMillisToDateTime(endMsOpt.value());
      TimeSpan duration = endDt - startDt;
      if (duration.count() < 0) duration = TimeSpan{0};

      auto results = co_await cal.FindAppointmentsAsync(startDt, duration, options);
      for (const auto& app : results) {
        if (!filterEventIds.empty()) {
          std::string localId = "";
          try { localId = winrt::to_string(app.LocalId()); } catch (...) {}
          std::string roamingId = "";
          try { roamingId = winrt::to_string(app.RoamingId()); } catch (...) {}

          bool match = false;
          for (const auto& fid : filterEventIds) {
            if ((!localId.empty() && localId == fid) ||
                (!roamingId.empty() && roamingId == fid)) {
              match = true;
              break;
            }
          }
          if (!match) continue;
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
    } else {
      DateTime startDt = winrt::clock::now() - std::chrono::hours(24 * 365 * 5);
      TimeSpan duration = std::chrono::hours(24 * 365 * 10);
      auto results = co_await cal.FindAppointmentsAsync(startDt, duration, options);
      for (const auto& app : results) {
        appointments.push_back(app);
      }
    }

    std::ostringstream json;
    json << "[";
    bool firstEvent = true;

    for (const auto& app : appointments) {
      if (!firstEvent) json << ",";
      firstEvent = false;

      std::string id = SafeGetEventId(app);
      std::string title = SafeGetEventTitle(app);
      std::string description = SafeGetEventDescription(app);
      int64_t startMs = SafeGetEventStartTime(app);
      int64_t endMs = SafeGetEventEndTime(app);
      bool allDay = SafeGetEventAllDay(app);
      std::string location = SafeGetEventLocation(app);
      std::string url = SafeGetEventUrl(app);
      std::string availability = SafeGetEventAvailability(app);
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
      try {
        auto invitees = app.Invitees();
        if (invitees && invitees.Size() > 0) {
          json << ",\"attendees\":[";
          bool firstInvitee = true;
          for (const auto& inv : invitees) {
            if (!firstInvitee) json << ",";
            firstInvitee = false;
            std::string invName = "";
            try { invName = winrt::to_string(inv.DisplayName()); } catch (...) {}
            std::string invEmail = "";
            try { invEmail = winrt::to_string(inv.Address()); } catch (...) {}
            int role = 1;
            try { role = static_cast<int>(inv.Role()) + 1; } catch (...) {}
            json << "{"
                 << "\"name\":\"" << EscapeJson(invName) << "\","
                 << "\"emailAddress\":\"" << EscapeJson(invEmail) << "\","
                 << "\"role\":" << role << ","
                 << "\"isOrganizer\":false"
                 << "}";
          }
          json << "]";
        }
      } catch (...) {}

      // Reminders
      try {
        if (app.Reminder()) {
          auto remDuration = app.Reminder().Value();
          int minutes = static_cast<int>(remDuration.count() / (kTicksPerMillisecond * 1000 * 60));
          json << ",\"reminders\":[{\"minutes\":" << minutes << "}]";
        }
      } catch (...) {}

      // Recurrence
      try {
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
      } catch (...) {}

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

    // ---------------------------------------------------------------
    // IMPORTANT: Parse ALL arguments BEFORE the first co_await.
    // After co_await, the coroutine may resume on a different thread
    // and method_call (passed by reference) will have been destroyed
    // when HandleMethodCall returned. Accessing args after co_await
    // is use-after-free.
    // ---------------------------------------------------------------

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

    // Pre-parse reminders
    std::optional<int64_t> reminderMinutes;
    {
      auto itReminders = args->find(flutter::EncodableValue("reminders"));
      if (itReminders != args->end() && !itReminders->second.IsNull()) {
        if (const auto* remList = std::get_if<flutter::EncodableList>(&itReminders->second)) {
          if (!remList->empty()) {
            if (const auto* remMap = std::get_if<flutter::EncodableMap>(&remList->front())) {
              reminderMinutes = GetInt64Value(*remMap, "minutes");
            }
          }
        }
      }
    }

    // Pre-parse recurrence rule
    struct RecurrenceData {
      bool hasRule = false;
      std::string freq = "DAILY";
      int64_t interval = 1;
      std::optional<int64_t> count;
      std::string until;
      std::vector<std::string> byDay;
    } rruleData;
    {
      auto itRrule = args->find(flutter::EncodableValue("recurrenceRule"));
      if (itRrule != args->end() && !itRrule->second.IsNull()) {
        if (const auto* rruleMap = std::get_if<flutter::EncodableMap>(&itRrule->second)) {
          rruleData.hasRule = true;
          rruleData.freq = GetStringValue(*rruleMap, "freq", "DAILY");
          auto intervalOpt = GetInt64Value(*rruleMap, "interval");
          rruleData.interval = intervalOpt.value_or(1);
          rruleData.count = GetInt64Value(*rruleMap, "count");
          rruleData.until = GetStringValue(*rruleMap, "until");

          auto itByDay = rruleMap->find(flutter::EncodableValue("byday"));
          if (itByDay != rruleMap->end() && !itByDay->second.IsNull()) {
            if (const auto* byDayList = std::get_if<flutter::EncodableList>(&itByDay->second)) {
              for (const auto& dayVal : *byDayList) {
                if (const auto* dayStr = std::get_if<std::string>(&dayVal)) {
                  rruleData.byDay.push_back(*dayStr);
                }
              }
            }
          }
        }
      }
    }

    // Pre-parse attendees
    struct AttendeeData {
      std::string name;
      std::string email;
      std::optional<int64_t> role;
    };
    std::vector<AttendeeData> attendeesData;
    bool hasAttendees = false;
    {
      auto itAttendees = args->find(flutter::EncodableValue("attendees"));
      if (itAttendees != args->end() && !itAttendees->second.IsNull()) {
        if (const auto* attList = std::get_if<flutter::EncodableList>(&itAttendees->second)) {
          hasAttendees = true;
          for (const auto& attVal : *attList) {
            if (const auto* attMap = std::get_if<flutter::EncodableMap>(&attVal)) {
              AttendeeData ad;
              ad.name = GetStringValue(*attMap, "name");
              ad.email = GetStringValue(*attMap, "emailAddress");
              ad.role = GetInt64Value(*attMap, "role");
              attendeesData.push_back(std::move(ad));
            }
          }
        }
      }
    }

    // ---------------------------------------------------------------
    // All arguments parsed. Safe to co_await from here.
    // ---------------------------------------------------------------

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

    Appointment appointment{nullptr};
    if (!eventId.empty()) {
      try {
        appointment = co_await cal.GetAppointmentAsync(winrt::to_hstring(eventId));
      } catch (...) {
        appointment = nullptr;
      }
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

    // Reminders (from pre-parsed data)
    if (reminderMinutes.has_value()) {
      try {
        TimeSpan rem = std::chrono::duration_cast<TimeSpan>(std::chrono::minutes(reminderMinutes.value()));
        appointment.Reminder(rem);
      } catch (...) {}
    }

    // Recurrence Rule (from pre-parsed data)
    if (rruleData.hasRule) {
      try {
        AppointmentRecurrence recurrence;
        if (rruleData.freq == "DAILY") recurrence.Unit(AppointmentRecurrenceUnit::Daily);
        else if (rruleData.freq == "WEEKLY") recurrence.Unit(AppointmentRecurrenceUnit::Weekly);
        else if (rruleData.freq == "MONTHLY") recurrence.Unit(AppointmentRecurrenceUnit::Monthly);
        else if (rruleData.freq == "YEARLY") recurrence.Unit(AppointmentRecurrenceUnit::Yearly);

        recurrence.Interval(static_cast<uint32_t>(rruleData.interval));

        if (rruleData.count.has_value() && rruleData.count.value() > 0) {
          recurrence.Occurrences(static_cast<uint32_t>(rruleData.count.value()));
        }

        if (!rruleData.until.empty()) {
          recurrence.Until(ParseIso8601(rruleData.until));
        }

        AppointmentDaysOfWeek days = AppointmentDaysOfWeek::None;
        for (const auto& dayStr : rruleData.byDay) {
          if (dayStr == "SU") days |= AppointmentDaysOfWeek::Sunday;
          else if (dayStr == "MO") days |= AppointmentDaysOfWeek::Monday;
          else if (dayStr == "TU") days |= AppointmentDaysOfWeek::Tuesday;
          else if (dayStr == "WE") days |= AppointmentDaysOfWeek::Wednesday;
          else if (dayStr == "TH") days |= AppointmentDaysOfWeek::Thursday;
          else if (dayStr == "FR") days |= AppointmentDaysOfWeek::Friday;
          else if (dayStr == "SA") days |= AppointmentDaysOfWeek::Saturday;
        }
        if (days != AppointmentDaysOfWeek::None) {
          recurrence.DaysOfWeek(days);
        }

        appointment.Recurrence(recurrence);
      } catch (...) {}
    }

    // Attendees (from pre-parsed data)
    if (hasAttendees) {
      try {
        appointment.Invitees().Clear();
        for (const auto& ad : attendeesData) {
          AppointmentInvitee invitee;
          if (!ad.name.empty()) invitee.DisplayName(winrt::to_hstring(ad.name));
          if (!ad.email.empty()) invitee.Address(winrt::to_hstring(ad.email));
          if (ad.role.has_value()) {
            int r = static_cast<int>(ad.role.value());
            if (r == 1) invitee.Role(AppointmentParticipantRole::RequiredAttendee);
            else if (r == 2) invitee.Role(AppointmentParticipantRole::OptionalAttendee);
            else if (r == 3) invitee.Role(AppointmentParticipantRole::Resource);
          }
          appointment.Invitees().Append(invitee);
        }
      } catch (...) {}
    }

    co_await cal.SaveAppointmentAsync(appointment);

    std::string savedId = SafeGetEventId(appointment);
    if (savedId.empty()) {
      savedId = eventId;
    }

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

    try {
      co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
    } catch (...) {}

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
      bool instanceDeleted = false;
      try {
        co_await cal.DeleteAppointmentInstanceAsync(winrt::to_hstring(eventId), instanceDt);
        instanceDeleted = true;
      } catch (...) {
        instanceDeleted = false;
      }
      if (!instanceDeleted) {
        try {
          co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
        } catch (...) {}
      }
    } else {
      try {
        co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
      } catch (...) {}
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
