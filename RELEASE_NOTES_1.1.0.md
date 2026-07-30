# MineScreen Canvas 1.1.0

> [!IMPORTANT]
> MineScreen clients and MineScreen servers must update together because this release uses
> multiplayer protocol revision 13. Back up important worlds before upgrading.

## English

MineScreen Canvas 1.1.0 is the first release that combines the media screen system with the new
traffic, carriage, synchronized-text, and Create integration work.

### Highlights

- Play WEB pages through MCEF, local MP4 through the bundled FFmpeg runtime, and remote desktops
  through the VNC/RFB client.
- Build joined, irregular, rotated, cable-linked, panoramic, or independently assigned screens.
- Use Traffic Displays, Station_Next, Station_Map, RMP/JSON/JS templates, synchronized text boards,
  electric-light boards, 45-degree Door LCDs, 60-degree ceiling displays, and carriage information
  strips.
- Read Create train schedules, destination, consist, next stop, ETA, dwell state, and moving
  carriage position when a compatible Create installation is present.
- Add dynamic UTF-8 TXT notices over an existing traffic or carriage template without replacing
  its route, station, ETA, or animation.
- Use a universal desktop JAR containing FFmpeg natives for Windows x64, Linux x64/ARM64, and
  macOS x64/ARM64.

### Installation

1. Install Minecraft 1.21.1, NeoForge 21.1.219, and Java 21.
2. Install `minescreen-1.1.0.jar`.
3. Install the official MCEF NeoForge `2.1.6-1.21.1` mod on every client.
4. For synchronized multiplayer state and permissions, install the same MineScreen JAR on the
   server. MCEF is not required on a dedicated server.
5. Create is optional; use a compatible Create 6.0.9 build for train features.

### Important boundaries

- WEB, VIDEO, and VNC pixels are rendered by each client and are not relayed by the Minecraft
  server.
- Local paths, VNC passwords, browser cookies, and decoded media frames are not synchronized.
- Traffic template synchronization accepts only bounded declarative manifests; uploaded
  JavaScript is never executed by the server or receiving clients.
- Create carriage support remains experimental and should be tested with the exact modpack and
  train design used by the server.

## 简体中文

MineScreen Canvas 1.1.0 首次将媒体屏幕系统与交通显示、车厢显示、同步文字及 Create 适配整合
到同一个正式版本中。

### 主要内容

- 通过 MCEF 显示网页，通过内置 FFmpeg 播放本地 MP4，并使用 VNC/RFB 客户端连接远程桌面。
- 支持相邻拼合、不规则画布、旋转、延长线异面连接、全景联合以及独立内容分配。
- 加入交通显示屏、Station_Next、Station_Map、RMP/JSON/JS 模板、同步文字展示板、电光展示板、
  45°车门 LCD、60°吊顶屏和车厢信息条。
- 安装兼容 Create 后，可读取列车时刻表、终点、编组、下一站、ETA、停靠状态及移动车厢位置。
- 可在原交通/车厢模板上叠加动态 UTF-8 TXT 提示，不会替换线路、站点、ETA 或原动画。
- 一个通用桌面 JAR 内含 Windows x64、Linux x64/ARM64、macOS x64/ARM64 的 FFmpeg 原生库。

### 安装

1. 安装 Minecraft 1.21.1、NeoForge 21.1.219 与 Java 21。
2. 安装 `minescreen-1.1.0.jar`。
3. 每个客户端安装官方 MCEF NeoForge `2.1.6-1.21.1`。
4. 多人同步状态和权限时，服务端安装相同的 MineScreen JAR；独立服务器不需要 MCEF。
5. Create 为可选依赖；列车功能需要兼容的 Create 6.0.9 构建。

### 重要边界

- WEB、VIDEO、VNC 画面由各客户端自行连接和渲染，Minecraft 服务器不转发画面帧。
- 本地文件路径、VNC 密码、浏览器 Cookie 和解码后的媒体帧不会同步。
- 交通模板同步只接受受限声明式清单；服务器和接收客户端不会执行上传者的 JavaScript。
- Create 车厢适配仍属实验功能，正式服务器应使用最终模组包和实际车体进行测试。

完整变更见
[CHANGELOG.md](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/CHANGELOG.md)，安装与操作见
[中文说明](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README_ZH_CN.md) /
[English guide](https://github.com/Hgit-1/MineScreen-Canvas/blob/main/README.md)。
