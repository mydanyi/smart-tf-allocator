# 智能TF卡分配器

[English](README.md) | 简体中文

**一张TF卡 双系统一起用**

用一根滑条，为 Android 应用存储和 Linux 游戏库分配 TF 卡空间。本应用是 [Armada Installer](https://github.com/mydanyi/armada-installer) 的安卓配套工具，供运行 Android 与 ArmadaOS 的掌机使用。

[下载 APK](https://github.com/mydanyi/smart-tf-allocator/releases/latest) · [Armada Installer](https://github.com/mydanyi/armada-installer) · [反馈问题](https://github.com/mydanyi/smart-tf-allocator/issues)

![智能TF卡分配器在 AYN Odin 3 上的实机界面](docs/screenshot-zh-CN.png)

> **分配空间会清空所选整张 TF 卡，请先备份。** 本应用会重新分区，不支持保留原有数据调整大小。

## 功能

- 左右拖动滑条，选择分配给 Android 和 Linux 的空间，实时查看预计容量；滑条中段（Android 比例 10%–90%）为混合分配，滑到两端则整张卡只归一个系统使用。
- 初始化 Android 内部扩展存储，供受支持的应用及数据使用，也支持整张卡作为 Android 存储。
- 为 Linux 预留配套分区，让 Armada Installer 识别并格式化为游戏库，也可把整张卡交给 Linux。
- 未 root 的设备可通过 Shizuku 使用；已 root 的设备可通过 root 启动 Shizuku。
- 支持未 root 且固件禁止读取存储卡硬件 CID 的设备：此时应用改用存储卡上的卷来识别卡片，无需 root 或修改系统安全设置。以此方式创建的 Linux 空间需要 Armada Installer 0.5.0 或更高版本；能正常读取 CID 的存储卡保持原有流程。
- 深色界面，支持触屏与手柄操作，适配掌机横屏。
- 可在应用内切换简体中文和英文。
- 提供运行日志开关和复制日志功能，方便排查问题。

## 使用要求

- Android 8.0 及以上，设备具有 microSD 卡槽。
- 系统固件支持 Android 将存储卡用作内部存储；具体取决于设备固件。
- 已安装并启动 [Shizuku](https://shizuku.rikka.app/)，且已向本应用授权。
- Linux 端需要从机身存储启动 ArmadaOS，并使用支持 **TF卡游戏库** 的 [Armada Installer 插件](https://github.com/mydanyi/armada-installer)。整张卡作为 Linux 使用时需要该版本；若固件禁止读取存储卡硬件 CID，涉及 Linux 的空间还需要 Armada Installer 0.5.0 或更高版本。整张卡作为 Android 存储时不需要 Linux 端操作。

本应用不负责安装 ArmadaOS。系统镜像和设备支持信息请查看官方 [ArmadaOS 项目](https://github.com/armada-os/armada)。

## 使用步骤

1. 备份 TF 卡，在 [发布页面](https://github.com/mydanyi/smart-tf-allocator/releases/latest) 下载并安装 APK。
2. 启动 Shizuku，向智能TF卡分配器授权。Android 11 及以上可通过无线调试在设备上启动，无需 root；其他启动方式请参考 [Shizuku 官方指南](https://shizuku.rikka.app/zh-hans/guide/setup/)，并以当前安装版本显示的步骤为准。设备重启后，如 Shizuku 未运行，请重新启动。
3. 打开本应用并选择 TF 卡，必要时点击 **重新扫描**。
4. 拖动滑条选择分配比例：滑到两端时整张卡归一个系统，Android 比例 10%–90% 时与 Linux 共用。显示容量为估算值，已考虑 Android 存储元数据开销。
5. 点击 **确认分配方案**，核对存储卡和容量，然后点击 **清除并分区**。
6. 保持存储卡插入、应用开启，等待分区完成；若保留了 Linux 空间，还需等待 Linux 交接信息写入完成。若仅交接信息写入失败，可点击 **重试写入**。整张卡作为 Android 存储时没有 Linux 后续步骤。
7. 如果分配了 Linux 空间，从机身存储启动 ArmadaOS，在 Armada Installer 中选择 **TF卡游戏库**，选择对应存储卡，按提示完成 Linux 分区格式化、挂载和游戏库接入。

| 工具 | 负责的部分 |
| --- | --- |
| 智能TF卡分配器（安卓） | 分配存储卡空间，初始化 Android 内部扩展存储，准备 Linux 交接信息 |
| Armada Installer（ArmadaOS） | 识别配套分区，格式化为 Linux 文件系统，挂载并接入游戏库 |

能否将应用放入扩展存储，取决于 Android、设备固件及应用本身，本工具不会自动迁移应用。Android 内部扩展存储会加密并与设备绑定，不能作为跨设备通用共享盘。Linux 分区格式化后，Android 可能无法再读取这一部分。

## 日志与问题反馈

打开 **运行日志** 可查看运行事件，点击 **复制日志** 可用于反馈。提交问题时，请提供设备型号、Android 版本、应用版本，以及出错前的操作；分享日志前请检查并移除个人信息。

## 从源码构建

需要 JDK 17 和 Android SDK 35，Gradle 及插件版本已在项目中固定。

```sh
./gradlew :app:assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/`，应用包名为 `io.github.mydanyi.armadatf`。

## 许可证与致谢

本项目原创代码使用 [GNU GPL v3.0 或更新版本](LICENSE)。第三方组件与图形资源保留各自许可，详见 [第三方声明](THIRD_PARTY_NOTICES.md)。

Android 机器人图形由 Google 创作并分享的作品复制或修改，按 [Creative Commons 3.0 署名许可](https://creativecommons.org/licenses/by/3.0/) 使用。Android 是 Google LLC 的商标。

## 支持与交流

应用问题请在本仓库的 [Issues](https://github.com/mydanyi/smart-tf-allocator/issues) 反馈；Linux 游戏库配置请查看 [Armada Installer](https://github.com/mydanyi/armada-installer)；系统镜像及设备支持请查看 [ArmadaOS](https://github.com/armada-os/armada)。

- [单翼制作 · 哔哩哔哩](https://space.bilibili.com/258786)
- [爱发电支持](https://afdian.com/a/meetmiku)
- QQ 粉丝群：487945399
- QQ 交流二群：477426414
