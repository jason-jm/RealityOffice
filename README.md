# Reality Office

Reality Office 是一套运行在 PICO 头显上的开源办公软件。Pages 用于文档，Sheets 用于表格，Slides 用于演示。三个编辑器均可在设备上打开、编辑和保存文件，无需账号。项目自有代码采用 [MIT 许可证](LICENSE)。

Reality Office is an open-source office suite for PICO headsets. Pages, Sheets and Slides read and write DOCX, XLSX, PPTX and OpenDocument files on the device. The interface is available in 18 languages. See the [feature guide](docs/FEATURES.md) for format support and limitations, and the [privacy policy](DATA.md) for how the app handles files and spatial data.

## Download / 下载

[Reality Office 1.3.4 (build 9)](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3.4) is the signed release for arm64 headsets running **PICO OS 6.2 or later**. It uses PICO Spatial SDK **6.2.8**. For PICO OS 6.1, use the [earlier release candidate](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1).

Download the APK and `SHA256SUMS` into the same directory. Verify and install on macOS:

```sh
shasum -a 256 -c SHA256SUMS
adb install -r RealityOffice-v1.3.4.apk
```

On Linux, use `sha256sum -c SHA256SUMS`. The signed build can update an earlier installation signed by The Reality Team. See the [release notes](docs/releases/1.3.4.md).

## Build from source / 从源码构建

Install JDK 17 or 21 and the Android SDK (Platform 35). Set `ANDROID_HOME` to the SDK directory or configure Android Studio to use that SDK. The included Gradle Wrapper downloads Gradle 8.13. On the first build, Gradle resolves the PICO and Android dependencies over the network. The Gradle build is configured for a 6 GiB heap.

```sh
git clone https://github.com/jason-jm/RealityOffice.git
cd RealityOffice
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat`. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `./gradlew :app:assembleRelease` produces an unsigned release APK unless you provide your own signing configuration using [`keystore.properties.example`](keystore.properties.example). A locally signed APK cannot update the official APK in place.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# With a PICO OS 6.2+ test headset or emulator connected:
./gradlew :app:connectedDebugAndroidTest
```

Some optional interoperability tests require an external document corpus or LibreOffice. The core JVM tests run with the included fixtures. The [CI recipe](docs/ci/android.yml) lists the Gradle checks for contributors. To regenerate the checked-in editor icons, run `python3 tools/icons/make-icons.py` with Python 3.12 or later.

The three editors, format readers/writers, renderers and spatial UI are in [`app/src/main`](app/src/main); unit and device tests are in [`app/src/test`](app/src/test) and [`app/src/androidTest`](app/src/androidTest). [Report issues here](https://github.com/jason-jm/RealityOffice/issues).

## License / 许可证

Copyright © 2026 The Reality Team. Original project code and documentation are licensed under [MIT](LICENSE). The Gradle Wrapper and the PICO icon paths retain their own licenses; see [third-party notices](THIRD_PARTY_NOTICES.md).
