# MineScreen Canvas

MineScreen Canvas 是面向玩家的 Minecraft Java 版屏幕模组，目标环境为 Minecraft 1.21.1 与
NeoForge 21.1.219。你可以在世界中搭建屏幕，播放网页、本地视频、VNC 桌面，也可以使用
IDLE 模式检查屏幕方向、拼合关系和画面位置。

> [!NOTE]
> 本项目使用了 AI 编程工具辅助编写。所有代码、依赖、媒体行为和安全策略仍应由人类审阅。
> 报告问题时请附上 Minecraft、NeoForge、MineScreen 版本、使用模式和客户端日志。

> [!WARNING]
> WEB、VIDEO 与 VNC 内容通常由每个玩家的客户端自行连接和渲染。Minecraft 服务器不会转发
> 解码后的视频帧、VNC 帧或浏览器画面。播放路径、密码、Cookie、本地文件路径不会通过多人状态发送。

## 安装

1. 安装 Minecraft Java 1.21.1 和 NeoForge 21.1.219。
2. 将 `minescreen-0.3.0.jar` 放入客户端 `mods` 文件夹。
3. 使用 WEB 模式时，在客户端另外安装官方 MCEF NeoForge 模组 `2.1.6-1.21.1`。
4. 首次进入游戏后，在“模组 -> MineScreen -> 配置”中检查设置。

只有使用 WEB 模式的客户端需要 MCEF，独立服务器不需要安装 MCEF。FFmpeg 已随 MineScreen
提供给本地视频路径使用；当前主要验证平台为 Windows x64。

## 第一次使用屏幕

1. 放置 Screen 方块，并使用延长线连接到通电的红石信号或拉杆。
2. 同方向相邻的 Screen 会自动拼合；缺少的瓦片保持为空，不会在空气中生成多余黑色区域。
3. 准星对准已通电屏幕，按住 Shift 右键，或手持 Screen Configurator 右键。
4. 选择 IDLE、VIDEO、WEB 或 VNC，填写内容后点击“保存并应用”。
5. 连接 Computer 后可右键打开主机控制面板；关闭面板后主机仍会显示缩小的待机预览。

屏幕未通电时显示全黑，并且不能从屏幕本身进入配置面板；主机面板仍可打开并提示断电状态。

## 内容模式

### IDLE

IDLE 是推荐的初始模式。它显示色彩测试条、方向参考线和中央 `IDLE` 字样，不访问外部
网络。若提供透明素材，MineScreen 会将其裁剪到 IDLE 下方的灰色区域，方便在世界中确认素材
是否正确加载。

### VIDEO

VIDEO 使用 FFmpeg 播放本地 MP4，支持播放/暂停、进度跳转、循环、分辨率调整和最高 30 FPS。
视频路径只保存在客户端，不会发送给服务器。

### WEB

WEB 使用 MCEF 的离屏 Chromium，支持网页导航、在 MineScreen 内管理新标签页、标签页切换、
点击、滚轮、键盘输入以及网页请求的 Pointer Lock。按 Escape 可退出输入捕获。HTTP、HTTPS、
本地文件、私网和域名白名单由配置文件控制。

### VNC

VNC 从客户端直接连接 RFB 服务器，使用 Tight 风格矩形解码和可配置刷新率以减少带宽。VNC
凭据保存在客户端本地凭据文件中，不会写入服务器同步状态。

## 常用操作

| 操作 | 作用 |
|---|---|
| 准星对准通电屏幕 | 准星位置就是虚拟鼠标位置。 |
| 左键 | 点击屏幕；主手持铁镐或更高等级镐时，左键用于挖掘屏幕。 |
| 滚轮 | 滚动当前 WEB/VNC 表面。 |
| Shift + 右键 | 打开屏幕编辑器。 |
| 右键 Computer | 打开主机控制面板。 |
| 右键固定键盘 | 进入键盘输入模式。 |
| 手持 Keyboard 对准屏幕 | 将键盘输入发送给当前屏幕。 |
| Escape | 释放键盘焦点或网页鼠标锁定。 |

网页请求 Pointer Lock 后，MineScreen 会把视角对准“逻辑画面中心实际所在的物理屏幕”，而
不是固定瞄准主方块。旋转屏幕、异面屏幕、不连续布局和中心空洞都会参与计算。

## 屏幕拼合与主机

- 同方向相邻 Screen 自动组成一个逻辑画布。
- Computer 与延长线可以连接不同方向、不同平面的屏幕。
- 主机支持自由分屏、横向联合画布、纵向联合画布和自定义位置。
- 可以按屏幕独立禁用、调整顺序和分配不同内容区域。
- 空洞或禁用瓦片保持透明/空白，不会渲染“没有方块却有黑色画面”的区域。

## 多人说明

MineScreen 是“客户端模组 + 可选服务端模组”设计。多人服务器需要同步屏幕权限、布局和
播放状态时，建议服务端也安装 MineScreen；但每个客户端仍然独立连接网页、视频或 VNC 源。
时间戳和部分状态可以同步，但受网络延迟、解码速度和媒体源差异影响，不能承诺所有玩家逐帧
完全一致。

## 配置与安全

可以编辑 `config/minescreen-common.toml`，也可以使用 NeoForge 原生配置界面。配置包含：

- 屏幕分辨率、画布像素上限和远距离降帧；
- WEB 加载动画、页面缩略图和自定义透明装饰；
- `web_loading_show_custom_decoration`；
- `ui_show_custom_decoration`、`ui_custom_decoration_opacity_percent`；
- VNC FPS、音频距离、WEB P2P 和渲染距离。

单人世界默认设置更方便使用；将世界开放到局域网前，请重新检查 HTTP、localhost、私网 IP、
云 metadata、任意域名和 `file://` 等开关。

## 自定义图片

将透明 PNG 放入 [user_assets](user_assets/)：

- `loading_decoration.png`：按比例叠加到 WEB 加载/错误背景；
- `panel_decoration.png`：以低不透明度绘制在主机和屏幕配置界面控件后方；
- 两种素材也会被适配到 IDLE 的下方灰色区域，确保世界内预览可见。

具体尺寸和构图建议见 [user_assets/README_ZH_CN.txt](user_assets/README_ZH_CN.txt)。缺失图片会
自动忽略，不会出现紫黑缺失纹理。

## 已知边界

- WEB 模式必须在客户端安装 MCEF。
- 本地视频当前以 MP4 为主，尚未混入视频音轨。
- VNC 带宽取决于桌面变化量、压缩方式、分辨率和 FPS。
- 客户端媒体源不会被 MineScreen 变成服务器媒体中继。

## 进一步阅读

- [English user guide](README.md)
- [未来路线图](FUTURE.md)
- [开发者与兼容性说明](PORTING.md)

## 特别鸣谢

特别感谢 Montoyo 与 WebDisplays 项目对“在 Minecraft 世界中使用网页显示设备”这一方向的
历史贡献与启发。MineScreen Canvas 是独立的 Minecraft 1.21.1 NeoForge 项目，不是 WebDisplays
移植版，也不是兼容替代品；视频、VNC、音频、延长线、异面拼合和 P2P 功能均有自己的实现与行为。

## 许可证

MineScreen 代码使用 MIT License，详见 [LICENSE](LICENSE)。
