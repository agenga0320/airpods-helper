# AirPods Helper (Android)

A small personal Android app for using AirPods on Android. Not published on Google Play.

## Features (phase 1)

- Reads the AirPods BLE "Proximity Pairing" broadcast to show left, right and case battery, charging state, in-ear state and lid state
- A foreground service keeps listening and shows the battery in the notification shade
- Opening the case lid shows an iPhone-style overlay card
- Two home screen widgets: 3×1 and 1×1

Requirements: Android 12 (API 31) or later. No root or Xposed needed.

## Build

```
gradlew :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project path must not contain non-ASCII characters (the Gradle test worker fails on such paths).

## Credits

The protocol parsing is based on these two open-source projects. Each packet format in the code notes which file it came from.

- [CAPod](https://github.com/d4rken-org/capod) by d4rken (GPL-3.0)
- [LibrePods](https://github.com/kavishdevar/librepods) by kavishdevar (GPL-3.0)

Some of the test data comes from CAPod's unit tests.

Not affiliated with Apple Inc. AirPods is a trademark of Apple Inc.

## License

GPL-3.0, see [LICENSE](LICENSE).
