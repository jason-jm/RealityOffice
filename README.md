# Reality Office

Reality Office brings documents, spreadsheets and presentations to PICO spatial windows. Pages, Sheets and Slides open and save DOCX, XLSX, PPTX and OpenDocument files. The suite supports 18 interface languages.

Reality Office 可在 PICO 头显上编辑文档、处理表格、播放演示，并将文件导出为 PDF。正式版 1.3.2（内部版本号 7）适用于 PICO OS 6.2 或更高版本的 arm64 设备。

Stable 1.3.2 (build 7) requires an arm64 PICO headset running PICO OS 6.2 or later. Download the signed APK and its `SHA256SUMS` file from the [latest release](https://github.com/jason-jm/RealityOffice/releases/latest). Devices running PICO OS 6.1 can use the [earlier release candidate](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1). On macOS, use the following commands to verify the APK and update a connected headset.

```sh
shasum -a 256 -c SHA256SUMS
adb install -r RealityOffice-v1.3.2.apk
```

Each release lists its changes and hardware verification status. The [privacy policy](DATA.md) explains how files, spatial data, diagnostics and system integrations work.
