# 把 Reality Office 装到头显 · Install on your headset

## 简体中文

正式版 1.3.4 适用于运行 **PICO OS 6.2 或更高版本的 arm64 头显**。通过 GitHub 安装需要一台电脑、一根可传输数据的 USB 线，以及头显上的 USB 调试权限。

1. 下载[签名 APK](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk) 和[校验文件](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/SHA256SUMS)，放在同一文件夹。
2. 在电脑上安装 [Android SDK Platform-Tools（包含 adb）](https://developer.android.com/tools/releases/platform-tools)。按 [PICO 设备设置说明](https://developer.picoxr.com/document/unity/set-up-the-development-environment/)开启开发者模式和 USB 调试，连接头显，在头显里确认这台电脑的调试请求。
3. 在下载目录打开终端，运行下方命令。第一行校验文件是否完整，第二行应列出状态为 `device` 的头显，第三行安装应用。示例假设 `adb` 已加入命令路径，并且只连接了一台设备。

```sh
shasum -a 256 -c SHA256SUMS
adb devices
adb install -r RealityOffice-v1.3.4.apk
```

第一行适用于 macOS；Linux 使用 `sha256sum -c SHA256SUMS`。Windows PowerShell 使用 `Get-FileHash .\RealityOffice-v1.3.4.apk -Algorithm SHA256`，将结果与校验文件比对。确认一致后再安装。

看到 `Success` 后，从头显的应用库打开 **Reality Office**。要试用首页展示的内容，可[下载中文示例包](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/coast-weekend/coast-weekend-zh-CN.zip)，解压后把三个文件复制到头显的 Download 文件夹，再在 Reality Office 首页选「从设备或云端打开…」。

已经安装过同一签名的正式版？上面的命令可原地更新，保留应用数据。如果头显使用 **PICO OS 6.1**，请查看[此前的 6.1 兼容候选版](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1)。

[返回中文首页](../README.md) · [1.3.4 更新说明](releases/1.3.4.md)

## English

The signed 1.3.4 APK runs on **arm64 headsets with PICO OS 6.2 or later**. To install from GitHub, you will need a computer, a USB data cable and access to USB debugging on the headset.

1. Download the [signed APK](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/RealityOffice-v1.3.4.apk) and [checksum file](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4/SHA256SUMS) into the same folder.
2. Install [Android SDK Platform-Tools (includes adb)](https://developer.android.com/tools/releases/platform-tools) on your computer. Follow [PICO’s headset setup instructions](https://developer.picoxr.com/document/unity/set-up-the-development-environment/) to enable developer mode and USB debugging. Connect the headset and accept the debugging prompt inside it.
3. Open a terminal in the download folder. The commands below verify the file, list the connected headset (its status should be `device`) and install the app. They assume `adb` is on your command path and only one device is connected.

```sh
shasum -a 256 -c SHA256SUMS
adb devices
adb install -r RealityOffice-v1.3.4.apk
```

The first line is for macOS. On Linux, use `sha256sum -c SHA256SUMS`. In Windows PowerShell, use `Get-FileHash .\RealityOffice-v1.3.4.apk -Algorithm SHA256` and compare the result with the checksum file before installing.

When you see `Success`, open **Reality Office** from the headset’s app library. To try the files in the screenshots, [download the English examples](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/coast-weekend/coast-weekend-en.zip), unzip them, copy the three files to the headset’s Download folder and choose **Open from device or cloud…** on the home screen.

The same command updates a previous release with the same signature and preserves its app data. For a headset still on **PICO OS 6.1**, see the [earlier 6.1-compatible release candidate](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1).

[Back to the English home page](../README.en.md) · [1.3.4 release notes](releases/1.3.4.md)
