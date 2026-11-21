# Install Reality Office on PICO Space Pro

[English](#english) · [简体中文](#简体中文)

## English

Install the signed 1.3.4 APK (build 10) on **PICO Space Pro** running **PICO OS 6.2 or later** (arm64). To install from GitHub, you will need a computer, a USB data cable and access to USB debugging on the headset.

1. Download the [signed APK](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4-build10/RealityOffice-v1.3.4-build10.apk) and [checksum file](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4-build10/SHA256SUMS) into the same folder.
2. Install [Android SDK Platform-Tools (includes adb)](https://developer.android.com/tools/releases/platform-tools) on your computer. Follow [PICO’s headset setup instructions](https://developer.picoxr.com/document/unity/set-up-the-development-environment/) to enable developer mode and USB debugging. Connect the headset and accept the debugging prompt inside it.
3. Open a terminal in the download folder. The commands below verify the file, list the connected headset (its status should be `device`) and install the app. They assume `adb` is on your command path and only one device is connected.

```sh
shasum -a 256 -c SHA256SUMS
adb devices
adb install -r RealityOffice-v1.3.4-build10.apk
```

The first line is for macOS. On Linux, use `sha256sum -c SHA256SUMS`. In Windows PowerShell, use `Get-FileHash .\RealityOffice-v1.3.4-build10.apk -Algorithm SHA256` and compare the result with the checksum file before installing.

When you see `Success`, open **Reality Office** from the headset’s app library. To try the files in the screenshots, [download the Saturday Print Club examples](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip), unzip them, copy the three files to the headset’s Download folder and choose **Open from device or cloud…** on the home screen.

GitHub build 9 and earlier use `com.therealityteam.aeropages`. Build 10 on GitHub and the APK prepared for the PICO stores use `com.therealityteam.realityoffice`. The two apps can be installed side by side. To move your work, open each draft in the old app, choose **File → Save a Copy to…** and select a shared folder such as Download. In the new app, choose **Open from device or cloud…** and open the copies. Recent files, settings and private drafts stay with the app that created them.

For a headset still on **PICO OS 6.1**, see the [earlier 6.1-compatible release candidate](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1).

[Back to the English home page](../README.md) · [1.3.4 release notes](releases/1.3.4-build10.md)

## 简体中文

Reality Office 为 **PICO Space Pro** 打造。1.3.4（构建 10）需要 **PICO OS 6.2 或更高版本**（arm64）。通过 GitHub 安装需要一台电脑、一根可传输数据的 USB 线，以及头显上的 USB 调试权限。

1. 下载[签名 APK](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4-build10/RealityOffice-v1.3.4-build10.apk) 和[校验文件](https://github.com/jason-jm/RealityOffice/releases/download/v1.3.4-build10/SHA256SUMS)，放在同一文件夹。
2. 在电脑上安装 [Android SDK Platform-Tools（包含 adb）](https://developer.android.com/tools/releases/platform-tools)。按 [PICO 设备设置说明](https://developer.picoxr.com/document/unity/set-up-the-development-environment/)开启开发者模式和 USB 调试，连接头显，在头显里确认这台电脑的调试请求。
3. 在下载目录打开终端，运行下方命令。第一行校验文件是否完整，第二行应列出状态为 `device` 的头显，第三行安装应用。示例假设 `adb` 已加入命令路径，并且只连接了一台设备。

```sh
shasum -a 256 -c SHA256SUMS
adb devices
adb install -r RealityOffice-v1.3.4-build10.apk
```

第一行适用于 macOS；Linux 使用 `sha256sum -c SHA256SUMS`。Windows PowerShell 使用 `Get-FileHash .\RealityOffice-v1.3.4-build10.apk -Algorithm SHA256`，将结果与校验文件比对。确认一致后再安装。

看到 `Success` 后，从头显的应用库打开 **Reality Office**。要试用首页展示的内容，可[下载版画工作室的英文示例](https://raw.githubusercontent.com/jason-jm/RealityOffice/main/examples/print-club/Saturday-Print-Club.zip)，解压后把三个文件复制到头显的 Download 文件夹，再在 Reality Office 首页选「从设备或云端打开…」。

此前发布的构建 9 使用包名 `com.therealityteam.aeropages`。GitHub 新版和 PICO 送审包均为 1.3.4（构建 10），使用包名 `com.therealityteam.realityoffice`。两版可以同时安装。要把草稿带到新版，先在旧版打开草稿，通过「文件 → 保存副本到…」存入 Download 等共享文件夹，然后在新版选择「从设备或云端打开…」打开副本。最近文件记录、设置和私有草稿分别保存在各自应用中。

如果头显使用 **PICO OS 6.1**，请查看[此前的 6.1 兼容候选版](https://github.com/jason-jm/RealityOffice/releases/tag/v1.3-rc.1)。

[返回中文首页](languages/zh-CN.md) · [1.3.4 更新说明](releases/1.3.4-build10.md)
