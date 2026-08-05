# MineScreen Canvas 1.2.0

> [!IMPORTANT]
> Minecraft 1.21.1, NeoForge 21.1.219, and Java 21 are still required. The compatibility layer
> prevents optional native media backends from crashing MineScreen; it cannot make an unsupported
> launcher, JVM, LWJGL build, or base game start.

## English

MineScreen Canvas 1.2.0 adds an emergency compatibility layer so clients can keep using core
displays when MCEF, bundled FFmpeg, or a platform-specific native library is unavailable.

### Highlights

- MCEF is now an optional client dependency. Full WEB support still prefers the official MCEF
  NeoForge mod, while unsupported clients retain IDLE, text, traffic, electric-light, and VNC
  displays.
- Browser and video engines are selected through lazy, failure-caching backend probes. A failed
  native initialization is reported once instead of being retried every frame or crashing startup.
- Existing Chromium-family browsers and system `ffmpeg`/`ffprobe` installations can provide
  limited emergency WEB or VIDEO playback. MineScreen never downloads these programs itself.
- Added platform classification for supported desktops, Windows 7, mobile launchers, HarmonyOS,
  LoongArch, and unknown environments, plus a core-only mode for unsafe combinations.
- Added a pure-Java lossless VNC fallback, retained thumbnails and clear compatibility/error
  screens when dynamic media is unavailable.
- Added an in-game **Compatibility & backends** page for status, re-detection, selecting existing
  executables, and copying local diagnostics.

### Compatibility notes

- Windows 7, Pojav/Amethyst, Android, iOS, HarmonyOS, LoongArch, and unknown architectures remain
  experimental, best-effort environments.
- External-browser playback is an emergency path with reduced resolution/FPS and no spatial web
  audio. External FFmpeg may also run without audio when no compatible native audio path exists.
- External program paths, probe results, local media paths, browser data, VNC credentials, and
  decoded frames remain client-local and are never uploaded to the Minecraft server.
- Multiplayer servers still synchronize only screen configuration and timing state; they do not
  relay WEB, VIDEO, or VNC pixels.

## 简体中文

MineScreen Canvas 1.2.0 加入应急兼容层：当 MCEF、内置 FFmpeg 或平台原生库不可用时，客户端
仍可启动并继续使用不依赖这些后端的基础显示功能。

### 主要内容

- MCEF 改为可选客户端依赖。完整 WEB 功能仍优先使用官方 MCEF NeoForge 模组；未安装或不兼容时，
  IDLE、文字、交通、电光展示与 VNC 仍可使用。
- 浏览器与视频引擎通过延迟探测和失败缓存选择。原生后端初始化失败只会报告一次，不再每帧重试，
  也不会因此让 MineScreen 在启动时崩溃。
- 可选择本机已有 Chromium 系浏览器或系统 `ffmpeg`/`ffprobe` 作为受限的应急 WEB/VIDEO 后端；
  MineScreen 不会自动下载这些第三方程序。
- 增加对受支持桌面、Windows 7、移动启动器、HarmonyOS、LoongArch 和未知环境的平台分类，
  对不安全组合自动进入仅核心功能模式。
- VNC 增加纯 Java 无损回退；动态媒体不可用时保留上次缩略图，并显示明确的兼容原因或错误页面。
- 游戏内新增“兼容性与后端”页面，可查看状态、重新检测、选择已有程序及复制本地诊断。

### 兼容边界

- Windows 7、Pojav/Amethyst、Android、iOS、HarmonyOS、LoongArch 与未知架构仍属于实验性、
  尽力兼容环境。
- 外部浏览器仅为降低分辨率/帧率且没有网页空间音频的应急路径；外部 FFmpeg 在缺少兼容音频后端时
  也可能静音播放。
- 外部程序路径、探测结果、本地媒体路径、浏览器数据、VNC 凭据及解码画面只保存在客户端，
  不会上传到 Minecraft 服务器。
- 多人服务端仍只同步屏幕配置与时间状态，不转发 WEB、VIDEO 或 VNC 画面帧。

完整变更见
[CHANGELOG.md](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/CHANGELOG.md)，安装与操作见
[中文说明](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README_ZH_CN.md) /
[English guide](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README.md)。
