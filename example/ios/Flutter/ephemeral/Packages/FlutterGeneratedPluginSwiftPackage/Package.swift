// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.
//
// Generated file. Do not edit.
//

import PackageDescription

let package = Package(
    name: "FlutterGeneratedPluginSwiftPackage",
    platforms: [
        .iOS("13.0")
    ],
    products: [
        .library(name: "FlutterGeneratedPluginSwiftPackage", type: .static, targets: ["FlutterGeneratedPluginSwiftPackage"])
    ],
    dependencies: [
        .package(name: "device_calendar", path: "../.packages/device_calendar"),
        .package(name: "flutter_timezone", path: "../.packages/flutter_timezone-5.1.0"),
        .package(name: "integration_test", path: "../.packages/integration_test"),
        .package(name: "FlutterFramework", path: "../.packages/FlutterFramework")
    ],
    targets: [
        .target(
            name: "FlutterGeneratedPluginSwiftPackage",
            dependencies: [
                .product(name: "device-calendar", package: "device_calendar"),
                .product(name: "flutter-timezone", package: "flutter_timezone"),
                .product(name: "integration-test", package: "integration_test"),
                .product(name: "FlutterFramework", package: "FlutterFramework")
            ]
        )
    ]
)
