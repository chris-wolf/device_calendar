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

inline std::vector<int64_t> ParseRemindersCsv(const std::string& csv) {
  std::vector<int64_t> result;
  std::stringstream ss(csv);
  std::string item;
  while (std::getline(ss, item, ',')) {
    if (!item.empty()) {
      try {
        result.push_back(std::stoll(item));
      } catch (...) {}
    }
  }
  return result;
}

inline std::string FormatRemindersCsv(const std::vector<int64_t>& minutes) {
  std::ostringstream ss;
  for (size_t i = 0; i < minutes.size(); ++i) {
    if (i > 0) ss << ",";
    ss << minutes[i];
  }
  return ss.str();
}

inline std::pair<std::string, std::vector<int64_t>> ExtractDescriptionAndReminders(const std::string& raw) {
  const std::string tagPrefix = "<!--dc_reminders:";
  const std::string tagSuffix = "-->";
  size_t startPos = raw.find(tagPrefix);
  if (startPos == std::string::npos) {
    return {raw, {}};
  }
  size_t endPos = raw.find(tagSuffix, startPos);
  if (endPos == std::string::npos) {
    return {raw, {}};
  }
  std::string csv = raw.substr(startPos + tagPrefix.length(), endPos - (startPos + tagPrefix.length()));
  std::vector<int64_t> reminders = ParseRemindersCsv(csv);

  std::string clean = raw.substr(0, startPos);
  if (!clean.empty() && clean.back() == '\n') {
    clean.pop_back();
  }
  if (endPos + tagSuffix.length() < raw.length()) {
    clean += raw.substr(endPos + tagSuffix.length());
  }
  return {clean, reminders};
}

inline std::string EncodeDescriptionWithReminders(const std::string& desc, const std::vector<int64_t>& reminders) {
  if (reminders.size() <= 1) {
    return desc;
  }
  std::string result = desc;
  if (!result.empty()) {
    result += "\n";
  }
  result += "<!--dc_reminders:" + FormatRemindersCsv(reminders) + "-->";
  return result;
}

inline int64_t GetAllDayStartEpochMillis(const DateTime& dt) {
  int64_t epochMs = DateTimeToEpochMillis(dt);
  std::time_t t = static_cast<std::time_t>(epochMs / 1000);
  std::tm tmLocal{};
  localtime_s(&tmLocal, &t);

  std::tm tmUtc{};
  tmUtc.tm_year = tmLocal.tm_year;
  tmUtc.tm_mon = tmLocal.tm_mon;
  tmUtc.tm_mday = tmLocal.tm_mday;
  tmUtc.tm_hour = 0;
  tmUtc.tm_min = 0;
  tmUtc.tm_sec = 0;
  std::time_t tUtc = _mkgmtime(&tmUtc);
  return static_cast<int64_t>(tUtc) * 1000;
}

inline int64_t GetAllDayEndEpochMillis(const DateTime& startDt, const TimeSpan& duration) {
  DateTime endDt = startDt + duration;
  int64_t endEpochMs = DateTimeToEpochMillis(endDt);
  std::time_t t = static_cast<std::time_t>(endEpochMs / 1000);
  std::tm tmCheck{};
  localtime_s(&tmCheck, &t);
  if (tmCheck.tm_hour == 0 && tmCheck.tm_min == 0 && tmCheck.tm_sec == 0) {
    t -= 1;
    localtime_s(&tmCheck, &t);
  }

  std::tm tmUtc{};
  tmUtc.tm_year = tmCheck.tm_year;
  tmUtc.tm_mon = tmCheck.tm_mon;
  tmUtc.tm_mday = tmCheck.tm_mday;
  tmUtc.tm_hour = 23;
  tmUtc.tm_min = 59;
  tmUtc.tm_sec = 59;
  std::time_t tUtc = _mkgmtime(&tmUtc);
  return static_cast<int64_t>(tUtc) * 1000;
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
inline std::string SafeGetEventCalendarId(const Appointment& app) {
  try { return winrt::to_string(app.CalendarId()); } catch (...) { return ""; }
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
      options.FetchProperties().Append(AppointmentProperties::UserResponse());
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
      std::string rawDescription = SafeGetEventDescription(app);
      auto [cleanDescription, customReminders] = ExtractDescriptionAndReminders(rawDescription);

      bool allDay = SafeGetEventAllDay(app);
      int64_t startMs = allDay ? GetAllDayStartEpochMillis(app.StartTime()) : SafeGetEventStartTime(app);
      int64_t endMs = allDay ? GetAllDayEndEpochMillis(app.StartTime(), app.Duration()) : SafeGetEventEndTime(app);
      std::string location = SafeGetEventLocation(app);
      std::string url = SafeGetEventUrl(app);
      std::string availability = SafeGetEventAvailability(app);
      std::string status = "CONFIRMED";
      try {
        switch (app.UserResponse()) {
          case AppointmentParticipantResponse::Tentative: status = "TENTATIVE"; break;
          case AppointmentParticipantResponse::Declined: status = "CANCELED"; break;
          case AppointmentParticipantResponse::None: status = "NONE"; break;
          case AppointmentParticipantResponse::Accepted:
          default:
            status = "CONFIRMED"; break;
        }
      } catch (...) {}

      json << "{"
           << "\"calendarId\":\"" << EscapeJson(calendarId) << "\","
           << "\"eventId\":\"" << EscapeJson(id) << "\","
           << "\"eventTitle\":\"" << EscapeJson(title) << "\","
           << "\"eventDescription\":\"" << EscapeJson(cleanDescription) << "\","
           << "\"eventStartDate\":" << startMs << ","
           << "\"eventStartTimeZone\":null,"
           << "\"eventEndDate\":" << endMs << ","
           << "\"eventEndTimeZone\":null,"
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
        std::vector<int64_t> remMinutesList = customReminders;
        if (remMinutesList.empty() && app.Reminder()) {
          auto remDuration = app.Reminder().Value();
          int minutes = static_cast<int>(remDuration.count() / (kTicksPerMillisecond * 1000 * 60));
          remMinutesList.push_back(minutes);
        }

        if (!remMinutesList.empty()) {
          json << ",\"reminders\":[";
          for (size_t i = 0; i < remMinutesList.size(); ++i) {
            if (i > 0) json << ",";
            json << "{\"minutes\":" << remMinutesList[i] << "}";
          }
          json << "]";
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
          } else if (recurrence.Until()) {
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
    std::string eventStatus = GetStringValue(*args, "eventStatus", "CONFIRMED");

    auto instanceStartMsOpt = GetInt64Value(*args, "instanceStartDate");
    auto instanceEndMsOpt = GetInt64Value(*args, "instanceEndDate");
    auto updateFollowingOpt = GetValue<bool>(*args, "updateFollowingInstances");

    bool allDay = allDayOpt.value_or(false);
    int64_t startMs = startMsOpt.value_or(0);
    int64_t endMs = endMsOpt.value_or(startMs);

    // Pre-parse reminders
    std::vector<int64_t> reminderMinutesList;
    bool hasRemindersKey = false;
    {
      auto itReminders = args->find(flutter::EncodableValue("reminders"));
      if (itReminders != args->end() && !itReminders->second.IsNull()) {
        hasRemindersKey = true;
        if (const auto* remList = std::get_if<flutter::EncodableList>(&itReminders->second)) {
          for (const auto& remVal : *remList) {
            if (const auto* remMap = std::get_if<flutter::EncodableMap>(&remVal)) {
              auto mins = GetInt64Value(*remMap, "minutes");
              if (mins.has_value()) {
                reminderMinutesList.push_back(mins.value());
              }
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

    auto applyCommonProperties = [&](Appointment& app, int64_t sMs, int64_t eMs, bool isAllDay) {
      app.Subject(winrt::to_hstring(title));

      std::string encodedDetails = EncodeDescriptionWithReminders(description, reminderMinutesList);
      app.Details(winrt::to_hstring(encodedDetails));

      app.Location(winrt::to_hstring(location));
      app.AllDay(isAllDay);

      DateTime sDt = EpochMillisToDateTime(sMs);
      app.StartTime(sDt);
      if (isAllDay) {
        if (eMs >= sMs) {
          int64_t diffMs = eMs - sMs;
          int64_t days = (diffMs / 86400000LL) + 1;
          if (days < 1) days = 1;
          app.Duration(std::chrono::hours(days * 24));
        } else {
          app.Duration(std::chrono::hours(24));
        }
      } else {
        if (eMs >= sMs) {
          DateTime eDt = EpochMillisToDateTime(eMs);
          app.Duration(eDt - sDt);
        } else {
          app.Duration(TimeSpan{0});
        }
      }

      if (availability == "FREE") {
        app.BusyStatus(AppointmentBusyStatus::Free);
      } else if (availability == "TENTATIVE") {
        app.BusyStatus(AppointmentBusyStatus::Tentative);
      } else if (availability == "UNAVAILABLE") {
        app.BusyStatus(AppointmentBusyStatus::OutOfOffice);
      } else {
        app.BusyStatus(AppointmentBusyStatus::Busy);
      }

      if (eventStatus == "TENTATIVE") {
        app.UserResponse(AppointmentParticipantResponse::Tentative);
      } else if (eventStatus == "CANCELED" || eventStatus == "CANCELLED") {
        app.UserResponse(AppointmentParticipantResponse::Declined);
      } else if (eventStatus == "NONE") {
        app.UserResponse(AppointmentParticipantResponse::None);
      } else {
        app.UserResponse(AppointmentParticipantResponse::Accepted);
      }

      if (!urlStr.empty()) {
        try {
          app.Uri(Uri(winrt::to_hstring(urlStr)));
        } catch (...) {}
      }

      if (hasRemindersKey) {
        if (!reminderMinutesList.empty()) {
          try {
            TimeSpan rem = std::chrono::duration_cast<TimeSpan>(std::chrono::minutes(reminderMinutesList[0]));
            app.Reminder(rem);
          } catch (...) {}
        } else {
          try {
            app.Reminder(nullptr);
          } catch (...) {}
        }
      }

      if (hasAttendees) {
        try {
          app.Invitees().Clear();
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
            app.Invitees().Append(invitee);
          }
        } catch (...) {}
      }
    };

    auto buildRecurrenceRule = [&](AppointmentRecurrence& recurrence, int64_t countOverride = -1) {
      if (rruleData.freq == "DAILY") recurrence.Unit(AppointmentRecurrenceUnit::Daily);
      else if (rruleData.freq == "WEEKLY") recurrence.Unit(AppointmentRecurrenceUnit::Weekly);
      else if (rruleData.freq == "MONTHLY") recurrence.Unit(AppointmentRecurrenceUnit::Monthly);
      else if (rruleData.freq == "YEARLY") recurrence.Unit(AppointmentRecurrenceUnit::Yearly);

      recurrence.Interval(static_cast<uint32_t>(rruleData.interval));

      int64_t cVal = countOverride >= 0 ? countOverride : (rruleData.count.has_value() ? rruleData.count.value() : -1);
      if (cVal > 0) {
        recurrence.Occurrences(static_cast<uint32_t>(cVal));
      } else if (!rruleData.until.empty()) {
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
      if (days == AppointmentDaysOfWeek::None && rruleData.freq == "WEEKLY") {
        std::time_t t = static_cast<std::time_t>(startMs / 1000);
        std::tm tm{};
        gmtime_s(&tm, &t);
        switch (tm.tm_wday) {
          case 0: days = AppointmentDaysOfWeek::Sunday; break;
          case 1: days = AppointmentDaysOfWeek::Monday; break;
          case 2: days = AppointmentDaysOfWeek::Tuesday; break;
          case 3: days = AppointmentDaysOfWeek::Wednesday; break;
          case 4: days = AppointmentDaysOfWeek::Thursday; break;
          case 5: days = AppointmentDaysOfWeek::Friday; break;
          case 6: days = AppointmentDaysOfWeek::Saturday; break;
        }
      }
      if (days != AppointmentDaysOfWeek::None) {
        recurrence.DaysOfWeek(days);
      }
    };

    // Case 1: Editing a recurring instance
    if (instanceStartMsOpt.has_value() && !eventId.empty()) {
      if (updateFollowingOpt.value_or(false) == false) {
        // Edit Only This Instance (Single Exception)
        DateTime origInstDt = EpochMillisToDateTime(instanceStartMsOpt.value());
        try {
          co_await cal.DeleteAppointmentInstanceAsync(winrt::to_hstring(eventId), origInstDt);
        } catch (...) {}

        Appointment newApp;
        applyCommonProperties(newApp, startMs, endMs, allDay);
        co_await cal.SaveAppointmentAsync(newApp);

        std::string newId = SafeGetEventId(newApp);
        result->Success(flutter::EncodableValue(newId));
        co_return;
      } else {
        // Edit This and Future Instances (Series Splitting)
        Appointment origApp{nullptr};
        try {
          origApp = co_await cal.GetAppointmentAsync(winrt::to_hstring(eventId));
        } catch (...) {
          origApp = nullptr;
        }

        int64_t occurrencesBefore = 0;
        if (origApp && origApp.Recurrence()) {
          try {
            FindAppointmentsOptions findOpts;
            findOpts.IncludeHidden(true);
            DateTime searchStart = origApp.StartTime();
            DateTime searchEnd = EpochMillisToDateTime(instanceStartMsOpt.value());
            TimeSpan searchDur = searchEnd - searchStart;
            if (searchDur.count() > 0) {
              auto priorList = co_await cal.FindAppointmentsAsync(searchStart, searchDur, findOpts);
              for (const auto& app : priorList) {
                if (SafeGetEventId(app) == eventId) {
                  if (DateTimeToEpochMillis(app.StartTime()) < instanceStartMsOpt.value()) {
                    occurrencesBefore++;
                  }
                }
              }
            }
          } catch (...) {}

          if (occurrencesBefore == 0) {
            try {
              co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
            } catch (...) {}
          } else {
            if (origApp.Recurrence().Occurrences()) {
              origApp.Recurrence().Occurrences(static_cast<uint32_t>(occurrencesBefore));
            } else {
              origApp.Recurrence().Until(EpochMillisToDateTime(instanceStartMsOpt.value() - 1));
            }
            try {
              co_await cal.SaveAppointmentAsync(origApp);
            } catch (...) {}
          }
        }

        Appointment newSeriesApp;
        applyCommonProperties(newSeriesApp, startMs, endMs, allDay);
        if (rruleData.hasRule) {
          try {
            AppointmentRecurrence newRecurrence;
            int64_t countRemaining = -1;
            if (rruleData.count.has_value() && rruleData.count.value() > 0) {
              countRemaining = rruleData.count.value() - occurrencesBefore;
              if (countRemaining <= 0) countRemaining = 1;
            }
            buildRecurrenceRule(newRecurrence, countRemaining);
            newSeriesApp.Recurrence(newRecurrence);
          } catch (...) {}
        }
        co_await cal.SaveAppointmentAsync(newSeriesApp);

        std::string newSeriesId = SafeGetEventId(newSeriesApp);
        result->Success(flutter::EncodableValue(newSeriesId));
        co_return;
      }
    }

    // Case 2: Standard Create or Update Event
    Appointment appointment{nullptr};
    if (!eventId.empty()) {
      try {
        appointment = co_await cal.GetAppointmentAsync(winrt::to_hstring(eventId));
      } catch (...) {
        appointment = nullptr;
      }

      if (appointment) {
        std::string existingCalId = SafeGetEventCalendarId(appointment);
        if (!existingCalId.empty() && existingCalId != calendarId) {
          try {
            auto oldCal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(existingCalId));
            if (oldCal) {
              co_await oldCal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
            }
          } catch (...) {}
          appointment = Appointment();
        }
      } else {
        // Event was not found directly in destination calendar `cal`. Check if it exists in another calendar (moved event).
        try {
          auto readStore = co_await GetAppointmentStoreForRead();
          if (readStore) {
            auto allCals = co_await readStore.FindAppointmentCalendarsAsync(FindAppointmentCalendarsOptions::IncludeHidden);
            for (const auto& otherCal : allCals) {
              std::string otherCalId = SafeGetCalendarId(otherCal);
              if (!otherCalId.empty() && otherCalId != calendarId) {
                try {
                  auto writeOtherCal = co_await store.GetAppointmentCalendarAsync(winrt::to_hstring(otherCalId));
                  if (writeOtherCal) {
                    auto oldApp = co_await writeOtherCal.GetAppointmentAsync(winrt::to_hstring(eventId));
                    if (oldApp) {
                      co_await writeOtherCal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
                      break;
                    }
                  }
                } catch (...) {}
              }
            }
          }
        } catch (...) {}
        appointment = Appointment();
      }
    }
    if (!appointment) {
      appointment = Appointment();
    }

    applyCommonProperties(appointment, startMs, endMs, allDay);

    if (rruleData.hasRule) {
      try {
        AppointmentRecurrence recurrence;
        buildRecurrenceRule(recurrence);
        appointment.Recurrence(recurrence);
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
    bool followingInstances = GetValue<bool>(*args, "followingInstances").value_or(false);

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
      if (followingInstances) {
        Appointment masterApp{nullptr};
        try {
          masterApp = co_await cal.GetAppointmentAsync(winrt::to_hstring(eventId));
        } catch (...) {
          masterApp = nullptr;
        }

        if (masterApp && masterApp.Recurrence()) {
          int64_t occurrencesBefore = 0;
          try {
            FindAppointmentsOptions findOpts;
            findOpts.IncludeHidden(true);
            DateTime searchStart = masterApp.StartTime();
            DateTime searchEnd = EpochMillisToDateTime(startMsOpt.value());
            TimeSpan searchDur = searchEnd - searchStart;
            if (searchDur.count() > 0) {
              auto priorList = co_await cal.FindAppointmentsAsync(searchStart, searchDur, findOpts);
              for (const auto& app : priorList) {
                if (SafeGetEventId(app) == eventId) {
                  if (DateTimeToEpochMillis(app.StartTime()) < startMsOpt.value()) {
                    occurrencesBefore++;
                  }
                }
              }
            }
          } catch (...) {}

          if (occurrencesBefore == 0) {
            try {
              co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
            } catch (...) {}
          } else {
            if (masterApp.Recurrence().Occurrences()) {
              masterApp.Recurrence().Occurrences(static_cast<uint32_t>(occurrencesBefore));
            } else {
              masterApp.Recurrence().Until(EpochMillisToDateTime(startMsOpt.value() - 1));
            }
            try {
              co_await cal.SaveAppointmentAsync(masterApp);
            } catch (...) {}
          }
        } else {
          try {
            co_await cal.DeleteAppointmentAsync(winrt::to_hstring(eventId));
          } catch (...) {}
        }
      } else {
        bool instanceDeleted = false;
        DateTime instanceDt = EpochMillisToDateTime(startMsOpt.value());
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
