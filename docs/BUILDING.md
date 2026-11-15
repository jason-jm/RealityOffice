# Build Reality Office from source

To install the signed app on your headset, see the [installation guide](INSTALL.md). To change the app or run the tests, start here.

Install JDK 17 or 21 and Android SDK Platform 35. Set `ANDROID_HOME` to the SDK directory or configure Android Studio to use it. The Gradle Wrapper downloads Gradle 8.13; the first build resolves the Android dependencies and PICO Spatial SDK 6.2.8 over the network. Gradle is configured for a 6 GiB heap.

```sh
git clone https://github.com/jason-jm/RealityOffice.git
cd RealityOffice
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. A release build (`./gradlew :app:assembleRelease`) is unsigned unless you supply your own signing configuration using [keystore.properties.example](../keystore.properties.example). An APK signed with a different key cannot update the official release in place.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# With a PICO OS 6.2+ test headset or emulator connected:
./gradlew :app:connectedDebugAndroidTest
```

The core JVM tests use the included fixtures. Some optional interoperability tests require an external document corpus or LibreOffice. See the [CI recipe](ci/android.yml) for the full Gradle checks. To regenerate the editor icons, run `python3 tools/icons/make-icons.py` from the repository root with Python 3.12 or later.

The editors, file readers and writers, renderers and spatial UI live in [app/src/main](../app/src/main); [unit tests](../app/src/test) and [device tests](../app/src/androidTest) live alongside them. The [feature guide](FEATURES.md) documents format support and limitations.

[English home page](../README.md) · [简体中文首页](languages/zh-CN.md) · [Report an issue](https://github.com/jason-jm/RealityOffice/issues)
