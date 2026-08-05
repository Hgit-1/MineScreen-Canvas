<p align="center">
  <img src="src/main/resources/minescreen.png" alt="MineScreen Canvas 标志" width="192">
</p>

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
2. 将 `minescreen-1.2.0.jar` 放入客户端 `mods` 文件夹。
3. 如需完整 WEB 功能，在客户端安装官方 MCEF NeoForge 模组 `2.1.6-1.21.1`。
4. 首次进入游戏后，在“模组 -> MineScreen -> 配置”中检查设置。

MCEF 现在是推荐但可选的客户端后端。未安装时 MineScreen 仍可启动，IDLE、文字、交通、电光
展示与纯 Java VNC 继续可用；还可选择本机已有 Chromium 系浏览器作为静音的应急 WEB 后端。
FFmpeg 已随 MineScreen 提供，支持 Windows x64、Linux x64/ARM64 与 macOS x64/ARM64；内置
原生后端不可用时可选择已有 `ffmpeg` 与 `ffprobe`。MineScreen 不会自动下载这些外部程序。

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

WEB 优先使用 MCEF 的离屏 Chromium；应急模式通过仅监听回环地址的内部接口控制本机已有、隔离
运行的 Chromium。支持网页导航、在 MineScreen 内管理新标签页、标签页切换、
点击、滚轮、键盘输入以及网页请求的 Pointer Lock。按 Escape 可退出输入捕获。HTTP、HTTPS、
本地文件、私网和域名白名单由配置文件控制。

### VNC

VNC 从客户端直接连接 RFB 服务器，使用 Tight 风格矩形解码和可配置刷新率以减少带宽。VNC
凭据保存在客户端本地凭据文件中，不会写入服务器同步状态。

### 同步文字展示板

“同步文字展示板”和“动画文字展示板”用于低带宽公告与交通信息。右键可编辑最多 512 个字符、
文字/背景颜色、字号、动画类型和速度。同方向相邻且类型相同的展示板会自动连接为一个逻辑画布，
由确定性的主方块统一绘制，其他方块不会重复绘制文字。服务器只同步这些参数与统一动画起始
时间；静态换行、滚动、呼吸和提醒闪烁由每个客户端按同一时间轴渲染，不会把文字画面逐帧传过服务器。

文字展示板与交通显示屏是两类支持正反双面可读的平面设备。背面可以关闭、与正面显示相同
内容，或使用独立编辑的内容。普通 VIDEO/WEB/VNC Screen 仍明确保持单面显示。

“交通显示屏”提供结构化交通字段：线路/车次、终点、当前站、下一站、到站信息、状态和模板
ID。`Station_Next` 可自动读取附近或坐标绑定的 Create 站台，按列车名称/种别筛选，并只显示
未来 90 秒内到站或正在停靠的列车；停靠时改为固定黄/红倒计时。每条记录包含完整车次名、
种别颜色、编组辆数、来向和去向。Station_Next 使用完整画布；导入的 PNG 或
`background_image` 可作为全画布背景。`Station_Map` 使用 RMP 或声明式模板显示线路图，并高亮当前站与
下一目的地。同一 Create 站台附近的多块显示板共享缓存快照，不会每块、每帧重复扫描。

外部交通模板放在 `config/minescreen/traffic_templates/<template_id>/`。导入器现在支持 Rail Map
Painter 原生 `RMP_*.json` 的简化站点/线路图层，也支持 MineScreen `.js` 生成器一次性生成受限的
文字、矩形和线段版面。RMP 只负责静态地图；当前/下一站、ETA、状态、多人时间和车厢行为仍由
MineScreen 实时字段负责，两者不会重复维护同一份运行状态。

导入的 JavaScript 只会在 64 MiB 一次性子 JVM 中执行，禁止访问 Java 类、文件和网络，转换完成后
游戏只加载声明式 JSON。服务端、网页、世界 tick 和渲染器都不会执行脚本。具体限额和 RMP
“结构化简化导入、非像素级复刻”的边界见[交通模板文档](TRAFFIC_TEMPLATES.md)。

多人默认启用声明式交通模板同步：客户端只公告模板 SHA-256 和大小，服务器缺少时才请求分块，
再把验证通过的 `script_scene_v1` 清单提供给其他客户端。JS 源码、RMP 工程、PNG、本地路径和
画面帧都不传输；相同哈希不会重复占用带宽。

交通显示屏与车厢显示屏还可以在“内容与提示”页面导入可选的 UTF-8 TXT 动态提示。提示以同步
半透明文字条叠加在原模板上，不会替换线路图、站台信息、ETA 或列车动画。使用 `---` 分页，
可设置每页时间、切换动画、文字对齐以及上方/中央/下方位置。

### 电光展示板

电光展示板是独立的单面 LED/霓虹文字设备，默认采用暗色面板、青色全亮文字、扫描线和同步的
静态/跑马灯/呼吸/提醒动画。它只与其他电光展示板拼合，不会错误连接普通或动画文字展示板。

### 车厢三棱柱吊顶显示屏

吊顶显示屏采用等边三角形截面的正三棱柱造型：两个斜面可联动或分别显示交通/MEDIA 内容，
贴合吊顶的安装面不显示画面。它只会沿选定的水平轴连接，默认无需红石供电，适合狭窄车厢。
新增的 45° 单面车门 LCD 采用独立的水平拼合组，不会与 60° 双面吊顶屏误拼。“车厢到站信息条”
是交通专用的横向拼合设备，采用车门上方窄屏版式，自动显示 Create 列车名称/种别、编组辆数、
终点、下一站、后续停靠站、ETA 与停车倒计时。终点按时刻表预测顺序推导；模糊目的地无法可靠
解析时使用编辑器中的备用终点。乘车提示（例如“乘坐本列车需要另购特急券”）可单独编辑，不会
被运行状态覆盖。预计到站时间进入默认 10 秒提醒窗口时切换为“即将到站，请准备下车”，提醒
窗口可通过原生 NeoForge 配置调节（10～120 秒）。斜面 WEB/VNC 的精确准星交互目前仍属于
实验功能。

导入带站点数据的 LCD Studio JS/JSON 后，点击“配置 JS 站点与 Create 站名”进入逐站向导。
例如模板中的 `C1` 可以绑定 Create 实际站名 `abcd 上行`；附近站台和本车时刻表会作为自动建议。
`上行/下行`、`上り/下り` 或 `upbound/downbound` 后缀会被识别为不同方向，适用于终点折返并
使用不同站台的往返时刻表。

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
| 右键文字展示板 | 打开由服务器校验的文字与样式编辑器。 |
| 同方向相邻文字展示板 | 自动连接为一个同步文字画布。 |
| 右键交通显示屏 | 编辑线路、站点、到站信息和客户端模板 ID。 |
| 交通编辑器“内容与提示” | 导入或清除动态 TXT 提示，不改变原交通模板。 |
| 交通编辑器“快速设置” | 选择手动、`Station_Next` 或 `Station_Map`；实时模式可自动绑定 Create 站台。 |
| 车厢交通编辑器“站名映射” | 逐站关联 LCD 代码与 Create 完整站名，并使用附近站台/时刻表建议。 |
| 从背面右键文字/交通显示屏 | 双面模式为“独立”时编辑背面内容。 |
| Shift + 右键吊顶显示屏 | 配置两个斜面之一，并选择交通信息或 MEDIA 内容。 |
| Shift + 右键 45° 车门 LCD | 配置单面内容；交通模式在组装后自动读取本列车下一站。 |
| 右键车厢到站信息条 | 配置备用线路/终点以及可选乘车提示；组装后自动读取本车时刻表。 |
| 右键电光展示板 | 编辑单面发光文字及动画。 |

网页请求 Pointer Lock 后，MineScreen 会把视角对准“逻辑画面中心实际所在的物理屏幕”，而
不是固定瞄准主方块。旋转屏幕、异面屏幕、不连续布局和中心空洞都会参与计算。

## 屏幕拼合与主机

- 同方向相邻 Screen 自动组成一个逻辑画布。
- Computer 与延长线可以连接不同方向、不同平面的屏幕。
- 主机支持自由分屏、横向联合画布、纵向联合画布和自定义位置。
- 可以按屏幕独立禁用、调整顺序和分配不同内容区域。
- 空洞或禁用瓦片保持透明/空白，不会渲染“没有方块却有黑色画面”的区域。
- 普通 Screen 为单面；只有文字展示板与交通显示屏支持可选的正反面内容。

## Create / 机械动力车厢（实验性）

MineScreen 可以在 Create 的客户端车厢虚拟世界中拼合并渲染同平面相邻屏幕。车厢移动与旋转
变换由 Create 提供；组装前已存在的 VIDEO/WEB 会话和纹理会被短期保留，避免仅因组装而刷新
网页或重启视频。由于虚拟车厢红石不会像普通世界持续 tick，第一层兼容会在车厢画面被渲染时
让屏幕保持工作。

安装 Create 6.0.9 或更高版本后，MineScreen 会通过 Create 公开的车厢变换 API 取得实时世界坐标，并将
画布可见性、屏幕声音以及车厢内通过延长线连接的音箱一起移动，不再继续使用组装地点的位置。

移动中的同平面 Screen 现在也支持准星点击、滚轮和手持 Keyboard。MineScreen 会把世界射线
变换到车厢局部坐标；网页请求 Pointer Lock 时，再把逻辑画面中心换算到车厢当前世界位置。
固定键盘、车厢红石、异面主机网络和更严格的车厢内部方块遮挡仍在
[FUTURE.md](FUTURE.md) 的后续适配计划中。

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
- Create 站台自动查找半径，以及声明式交通模板同步和服务器模板数量上限。

`config/minescreen-client.toml` 保存仅客户端使用的兼容设置。屏幕编辑器中的“兼容性与后端”
页面会直观显示 WEB/VIDEO 当前使用的引擎，可重新检测、选择本机已有程序和复制诊断信息。
外部程序路径与检测结果绝不会上传到服务器。

## 平台兼容性（v1.2.0）

首先必须由启动器、Java 21 与 LWJGL 成功启动 Minecraft 1.21.1。MineScreen 无法让底层不兼容的
JVM 或图形栈启动，但会在识别到不兼容平台后避免主动加载可选原生库。

| 环境 | WEB | VIDEO | 基础显示 / VNC |
|---|---|---|---|
| Windows 10+、Linux、macOS 桌面 | MCEF 优先，已有 Chromium 备用 | 内置 FFmpeg 优先，系统 FFmpeg 备用 | 支持 |
| Win7 / 旧桌面 | 实验性已有 Chromium 或上次缩略图 | 实验性系统 FFmpeg | 尽力兼容 |
| LoongArch / 非常见桌面架构 | 检测到的系统 Chromium | 对应架构的系统 FFmpeg | 游戏能启动时尽力兼容 |
| Pojav、Android、iOS、HarmonyOS | 停用动态 WEB，显示缩略图与原因 | 仅在确实存在可执行程序时启用 | 尽力兼容 |
| 完全未知环境 | 不加载可选原生网页引擎 | 不加载可选原生视频引擎 | 仅核心模式 |

Win7、Pojav/Amethyst、HarmonyOS 与 LoongArch 都属于实验环境，不是正式支持目标。外部浏览器
默认静音并限制分辨率/FPS；外部 FFmpeg 优先保证画面、暂停、跳转和循环，可能没有音频。

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

- MCEF 为可选但强烈推荐的完整 WEB 后端；外部浏览器只是应急兼容路径，网页空间音频等能力会降级。
- 本地视频当前以 MP4 为主，支持将第一条音轨解码为 48 kHz 立体声位置音频；尚不支持选择
  或混合多条音轨。
- VNC 带宽取决于桌面变化量、压缩方式、分辨率和 FPS。
- 客户端媒体源不会被 MineScreen 变成服务器媒体中继。
- Create 车厢支持移动显示板配置以及准星/手持键盘交互，但发布整合包前仍需使用目标 Create
  版本和实际车体进行验证。

## 进一步阅读

- [English user guide](README.md)
- [未来路线图](FUTURE.md)
- [开发者与兼容性说明](PORTING.md)
- [1.1.0 生产就绪测试报告](docs/PRODUCTION_READINESS_1.1.0.md)
- [更新日志](CHANGELOG.md)
- [交通模板与 Rail Map Toolkit PNG 工作流](TRAFFIC_TEMPLATES.md)
- [JR-East-inspired 车内 LCD 浏览器/服务端示例](examples/traffic_templates/jr_east_lcd/)
- [线性双语车内 LCD 浏览器/服务端示例](examples/traffic_templates/linear_dual_language_lcd/)

## 特别鸣谢

- Montoyo 与 WebDisplays：提供游戏内网页显示设备的历史概念与参考背景；
- CinemaMod/MCEF：提供 WEB 模式使用的离屏 Chromium 集成；
- FFmpeg 与 Bytedeco JavaCPP：提供媒体解码和 Java 原生绑定；
- NeoForge 项目：提供模组加载器、API 与开发工具；
- simibubi 与 Create 团队：提供可选的车厢结构变换 API；
- Rail Map Toolkit：其铁路线路图工作流是 MineScreen PNG/模板导入接口的兼容目标之一。
- jonhweider/TrainLCD：为线性车内 LCD 的多语言站名、局部站序与信息层级提供产品设计参考；
- Mozilla Rhino：为隔离的模板导入子进程提供 MPL-2.0 JavaScript 引擎。

MineScreen Canvas 是独立项目，不分发 WebDisplays、Create、MCEF 或 Rail Map Toolkit。本项目不会
在没有明确许可证和署名记录的情况下打包 Rail Map Toolkit 工程解析器或第三方美术资源。

## 许可证

MineScreen 代码使用 MIT License，详见 [LICENSE](LICENSE)。
内嵌第三方组件继续使用其各自许可证，详见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
