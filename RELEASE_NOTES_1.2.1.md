# MineScreen Canvas 1.2.1

> [!IMPORTANT]
> Minecraft 1.21.1, NeoForge 21.1.219, and Java 21 are still required. MCEF remains optional, and
> experimental platforms are supported on a best-effort basis only.

## English

MineScreen Canvas 1.2.1 makes the universal mod JAR much smaller and moves FFmpeg preparation to a
secure, platform-specific runtime installer shown after Minecraft reaches its main menu.

### Highlights

- Removed bundled JavaCPP and FFmpeg native binaries from the mod JAR.
- Downloads only the matching FFmpeg package for Windows, Linux, macOS, or Android ARM64.
- Verifies normal TLS certificates and hostnames, resolved public IP addresses, exact file size,
  and a release-pinned SHA-256 before extraction or execution.
- Tries a small built-in list of trusted HTTPS repositories and provides the exact canonical Maven
  Central URL plus verified manual import when all sources fail.
- Shows FFmpeg and MCEF preparation on one Minecraft-styled progress screen when both are active.
- Starts preparation at the normal main menu, so selecting VIDEO later does not require another
  save or leave the screen permanently black.
- Adds Pojav, FCL, and ZL2 launcher recognition with conservative Android defaults: 854-pixel
  decode width, 15 FPS nearby, and 5 FPS at distance.
- Restores positional video audio for the external FFmpeg process backend when the runtime supports
  PCM output.

### Safety and compatibility

- MineScreen does not download a browser. Full WEB remains MCEF-first; desktop Chromium is only an
  optional installed compatibility backend.
- Runtime files remain client-local and are never uploaded to a Minecraft server.
- HarmonyOS, Windows 7, Android launchers, LoongArch, iOS, and unknown architectures remain
  experimental. A failed optional backend degrades locally without changing shared screen state.
- Never accept a manually downloaded package if the browser reports an invalid certificate, and do
  not obtain FFmpeg packages from unknown websites.

## 简体中文

MineScreen Canvas 1.2.1 显著缩小通用模组 JAR，并改为在 Minecraft 进入主菜单后，通过安全的
平台运行库安装器准备 FFmpeg。

### 主要变化

- 从模组 JAR 中移除内置 JavaCPP 与 FFmpeg 原生二进制。
- 仅下载当前 Windows、Linux、macOS 或 Android ARM64 对应的平台包。
- 解压和执行前依次校验正常 TLS 证书与主机名、DNS 解析后的公网地址、准确文件大小以及随版本
  固定的 SHA-256。
- 内置少量可信 HTTPS 仓库；全部失败时给出准确的 Maven Central 官方地址，并支持校验后手动导入。
- MCEF 与 FFmpeg 同时准备时，共用一个 Minecraft 风格进度页面。
- 进入正常主菜单后即开始准备，之后选择 VIDEO 无需再次保存，也不会因下载尚未完成而永久黑屏。
- 识别 Pojav、FCL 与 ZL2，并采用较保守的 Android 默认值：854 像素宽、近处 15 FPS、远处 5 FPS。
- 外部 FFmpeg 进程支持 PCM 输出时，恢复视频的位置音频与距离衰减。

### 安全与兼容边界

- MineScreen 不会下载浏览器。完整 WEB 仍优先使用 MCEF；桌面 Chromium 仅作为用户已有的兼容后端。
- 下载的运行库与本地媒体路径只保存在客户端，绝不会上传到 Minecraft 服务器。
- HarmonyOS、Windows 7、Android 启动器、LoongArch、iOS 与未知架构仍为实验性支持。可选后端
  失败时只在该客户端降级，不会更改服务器共享的屏幕状态。
- 手动下载时若浏览器提示证书无效，绝对不要继续；也不要从不明网站获取 FFmpeg 包。

完整变更见
[CHANGELOG.md](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/CHANGELOG.md)，安装与操作见
[中文说明](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README_ZH_CN.md) /
[English guide](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README.md)。
