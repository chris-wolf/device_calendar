#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html
#
Pod::Spec.new do |s|
  s.name             = 'device_calendar'
  s.version          = '4.3.1'
  s.summary          = 'A cross platform plugin for modifying calendars on the user\'s device.'
  s.description      = <<-DESC
A cross platform plugin for modifying calendars on the user's device.
                       DESC
  s.homepage         = 'https://github.com/builttoroam/device_calendar'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Built to Roam' => 'support@builttoroam.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'device_calendar/Sources/device_calendar/**/*'
  s.ios.dependency 'Flutter'
  s.osx.dependency 'FlutterMacOS'
  s.ios.deployment_target = '13.0'
  s.osx.deployment_target = '10.15'

  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES' }
  s.swift_version = '5.0'
end
