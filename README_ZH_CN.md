# MineScreen Canvas

[English](README.md) | [简体中文](README_ZH_CN.md)

**MineScreen Canvas** 是本仓库与发行版的项目副标题，用于突出不规则拼合、多面联合画布和多内容源。游戏内模组名称仍为 **MineScreen**，注册命名空间仍为 `minescreen`，不会破坏既有存档。

本项目确实以 Montoyo 的 WebDisplays 1.12.2 为交互、外设、行为和数据模型参考。

本项目审阅 WebDisplays 1.12.2 的公开领域源码与物品体系，使用 Java 21、Mojmap、NeoForge 21.1.219 和外置 MCEF 2.1.6 独立重新实现，不复用旧 Forge/MCP API，也不打包旧版 Chromium。Screen、Configurator、Keyboard、Server 等功能以行为兼容为目标；本地视频、VNC、位置音频、不规则画布与P2P属于 MineScreen 扩展。

这不是 Montoyo 发布的官方新版本。上游来源与许可见 [UPSTREAM.md](UPSTREAM.md)，逐项进度与迁移顺序见 [PORTING.md](PORTING.md)。

## 快速开始

1. 客户端安装 MineScreen 和官方 MCEF NeoForge Mod。
2. 放置 `minescreen:screen`；方块支持六方向。同平面同朝向的相邻瓦片会自动拼合，也可用延长线连接不连续瓦片。
3. 至少连接一段 `minescreen:screen_cable`，并让拉杆、红石线或其他红石信号源给任意一段延长线供电；未供电屏幕保持全黑且释放内容后端。
4. 使用屏幕配置器或 `Shift + 右键` 已通电瓦片打开 MineScreen 控制台；断电屏幕会提示并拒绝打开自身面板，连接的主机仍可进入全部页面。
5. 选择 `IDLE`、`VIDEO`、`WEB` 或 `VNC`，按界面提示填写并选择“保存并应用”。
6. WEB/VNC 可控制模式下，将准星移到屏幕上即可点击和滚动；文字/按键输入需要手持键盘或连接的固定键盘。

移植版配置屏幕的标准方式是合成并手持 `minescreen:screen_configurator`，然后右键屏幕；Shift+右键暂时作为旧操作兼容入口。屏幕自身编辑器只保留播放内容配置，主屏幕信息与预览控制只存在于连接的主机 GUI。

屏幕使用主手铁镐或更高等级镐拆卸。主手工具满足掉落等级时，左键不会发送给网页/VNC，而会保留为原版破坏键。

## 屏幕与拼合

- 同一维度、同一朝向、同一平面的方块可通过四邻接或六向延长线网络构成一个逻辑屏幕。
- 支持 NORTH/SOUTH/EAST/WEST/UP/DOWN 六个可视方向；地面与天花板屏幕使用和墙面一致的 UV、射线及输入坐标系。
- 每个瓦片默认贡献 720×720 逻辑像素；全局上限为 3840×2160、约 8.3 MP。
- L 形等不规则组件仍按矩形包围盒计算连续 UV，但只为真实瓦片提交几何。空洞完全透明、不可命中，也不会产生无碰撞的黑色单面。
- 线缆连接的不连续异形画布同样只绘制真实瓦片；空白跨度不会生成空气中的画面或黑块。
- 行列由世界坐标计算，不依赖放置或集合遍历顺序。
- 主方块按 `(Y, 屏幕右方向坐标, tile_id)` 决定；旧 Stage-1 多格锚点继续以兼容模式渲染。
- 屏幕电源只沿 MineScreen 延长线网络传播，不会把延长线变成原版红石导线。拉杆可从任意相邻方向作为输入；网络中任意延长线有红石信号即可点亮全部相连屏幕面。

## 内容模式

### IDLE

默认空闲模式，不发起任何外部连接。屏幕显示色彩测试图、方向参考线以及中央高对比度 `IDLE` 字样，便于检查拼合与 UV。

断电优先于内容模式：IDLE、VIDEO、WEB、VNC 都显示纯黑，并停止浏览器、解码器、VNC连接和音频。重新供电后按保存的状态异步恢复。

旧版客户端配置里的 `TEST` 会在读取时自动迁移为 `IDLE`。

### VIDEO

- 本地 MP4 或直接 HTTP(S) 视频地址（支持最长 65,535 字符的签名 URL）、自动播放、循环、暂停、进度跳转和最高 30 FPS。
- FFmpeg 视频线程写入 3 槽有界环形缓冲，渲染线程只消费完整帧并更新同一个 DynamicTexture。
- 视频保持原始宽高比，使用 letterbox/pillarbox，绝不拉伸。
- FFmpeg/OpenAL 音频作为位置音源播放，并用音频时钟校准视频。
- 屏幕默认声距由 `default_screen_sound_distance` 控制；直接或通过延长线连接 `minescreen:speaker` 可将覆盖扩展到 `speaker_sound_distance`。多个音箱复用同一解码流。
- 音量调整只热更新当前音频增益，不重建 Chromium、视频解码器或网页标签。
- 超距、离屏、跨维度或区块卸载时暂停/停止工作，恢复后按媒体时间戳追平。
- VIDEO、WEB、VNC 分别保存 `video_source`、`web_source`、`vnc_source`；切换模式时恢复该模式自己的地址，不再把网页 URL 填入视频路径。

#### 共享媒体 ID

`media_id` 是多人游戏中的“视频逻辑名称”，不是文件路径或下载地址。例如所有玩家均填写 `lobby_intro_v1`，但每个客户端可以分别选择自己电脑上的 MP4 路径。服务器同步这个 ID、暂停/循环状态与播放时间戳；只有本地 profile 的 ID 与服务器 ID 相同时，该客户端才使用自己映射的文件。本地路径、解码帧和音频不经过 Minecraft 服务器。

### WEB

- 使用 MCEF 2.1.6 的离屏 Chromium，viewport 与当前屏幕渲染分辨率一致。
- 页面普通链接、重定向、子资源和 `target=_blank` 都经过统一安全检查。新窗口目标会创建 MineScreen 内部标签页并自动切换过去。
- 控制台与主机内容页提供顶部标签栏、加载/跳转、后退、前进和刷新。普通加载更新主页；`Shift + 加载` 新建并持久化一个标签页；点击标签切换，`Shift + 点击标签` 关闭。
- WEB 会话内部的标签布局支持单页、左右、上下与四标签视图；它与下面的“独立内容区域”是两层不同功能，切换标签布局不会刷新页面。
- 多人服务器同步当前活动页面 URL：重定向链稳定 750ms 后才发送一次，服务端去重、限速并只广播给同步距离内玩家；不传输网页像素、DOM、Cookie 或表单数据。
- 可选去中心化同步渲染：同屏客户端按UUID构造确定性二叉树，根节点读取MCEF纹理并以JPEG分发；中继节点原样转发已压缩帧，不重复编码。鼠标、键盘、导航和标签页命令沿树回传到根浏览器，因此所有节点观看同一动态页面。
- P2P默认关闭。服务器和客户端都需启用 `web_peer_distribution`；默认1280宽、最高8 FPS、JPEG质量58、总上传约8000 Kbps。静止画面会按压缩帧校验去重，超出上传预算直接丢帧以保持低延迟。
- P2P使用玩家直连TCP，Minecraft服务器只发送观察到的IP、端口和随机令牌。LAN通常可直接工作；公网NAT通常需要固定 `web_peer_listen_port` 并转发该TCP端口。直连失败或5秒无新帧时自动回退本地MCEF。
- 准星 UV 是网页唯一的虚拟鼠标位置，每个渲染帧重新计算。所有内容使用统一的观察者右方向坐标，因此画面与点击不再沿中轴镜像。

### VNC

- 内置 RFB 3.3/3.7/3.8 客户端，支持 None、经典 VNC DES 验证、Tight、Raw、CopyRect、DesktopSize 与 LastRect。
- Tight支持4路持久zlib、Copy/Palette/Gradient、Fill、JPEG和PNG；默认请求压缩等级9、JPEG质量5，并可通过NeoForge配置调整。
- 握手与首帧阶段有15秒超时；连接开始、首帧成功或认证/协议失败都会在游戏内给出状态，不再无限停留于无说明的 IDLE。
- Tight 持久 zlib 会完整消费每个 `Z_SYNC_FLUSH` 边界，连续压缩矩形不会因残留输入使解码线程停止。
- 脏矩形只更新 DynamicTexture 对应区域。
- 默认允许控制；切换为只读后停止转发输入。准星鼠标和键盘设备输入会映射到远端 framebuffer。
- 每个VNC屏幕可选择默认、5、10、15、20、30或60 FPS。限速发生在RFB framebuffer请求端，会实际减少VNC服务器编码工作和网络带宽，而不是接收后再丢帧；NeoForge配置 `vnc_max_fps` 控制默认值。
- 不再申请单人独占租约；多个玩家默认可同时连接并控制同一屏幕所指向的 RFB 端点。
- 密码仅保存于 `config/minescreen-vnc-credentials.json`，不会进入服务器状态或网络包。

经典 RFB 不加密。即便密码只存本地，也建议通过可信 VPN、SSH 或 TLS 隧道连接。

## 独立内容区域与逐屏用途

- 一个拼合屏幕最多包含主画面 `M` 与区域 `1–3`，每个区域都有独立的 IDLE/VIDEO/WEB/VNC 配置、分辨率、音量、标签页和输入会话。
- 在主机“主屏幕信息”页先选择“指派：主画面/区域 1–3”，再点击实体瓦片即可改变该屏幕的用途；`Shift + 点击` 保留为启用/禁用瓦片。
- 点击“配置区域”进入该区域自己的内容编辑器。没有瓦片的区域不会启动解码器或浏览器。
- 同一区域的多个瓦片按自身矩形包围盒组成连续画布；不同区域可以同时显示完全不同的内容。准星会自动换算到命中区域的局部 UV，并把输入发送到对应会话。
- 世界屏幕、主机内容预览和主机方块的小型待机画面使用相同的区域合成结果。改变 WEB 区域的瓦片范围只调用浏览器 `resize`，不会刷新网页。
- 区域来源和瓦片指派保存在客户端 `config/minescreen-screens.json`；当前多人权威 Payload 仍只同步主画面，独立区域同步将在后续扩展协议中加入。

## 主机多平面与联合画布

- 一台主机会发现同一延长线网络上的全部屏幕平面；不同朝向、不同平面的屏幕不会被强行合并为一个 `ScreenGroup`，因此每一面的射线、UV和碰撞仍保持正确。
- 异面屏幕必须分别接入同一条 `minescreen:screen_cable` 网络；仅仅在墙角互相接触不会跨面连接。
- `自由分屏`模式下，各平面保留独立的 IDLE/VIDEO/WEB/VNC profile 与会话。主机预览以网格同时显示全部平面，点击对应预览即可把鼠标/内置键盘输入送往该平面。
- 联合画布只适合一个连续来源；信息页不会再显示无效的逐瓦片区域按钮。点击“独立分屏”会切换到自由分屏并定位当前选中的物理屏幕，随后可分别给两块屏幕设置不同内容并同时运行。
- `横向联合画布`和`纵向联合画布`把全部异面屏幕视为主屏的同一个逻辑画面源，支持 IDLE、VIDEO、WEB 与 VNC。整个网络只创建一个后端：一个 FFmpeg 解码器、一个 Chromium 会话或一条 RFB 连接，而不是为每个平面重复加载。
- 每个物理平面按照自身瓦片宽高取得联合画布切片；WEB 的多标签分屏 pane 还会与这些切片求交，因此跨越墙角、天花板或不等尺寸平面时不会重复绘制整张网页。准星点击与滚轮使用同一归一化变换反算到联合 WEB/VNC 坐标。
- 平面初始顺序稳定按连接发现顺序排列。主机左侧先选择屏幕面，再用“顺序 ◀/▶”交换横向或纵向自动排列顺序；四个方向按钮以一方块为步长调整逻辑位置，并自动切换为`自定义联合画布`。布局保存在 `host_surface_order` 和 `host_surface_positions`，重进世界后仍会恢复。
- 主机预览用边框标出每个独立屏幕切片，并显示编号、朝向和 `宽×高` 方块尺寸；当前选择为黄色，其余为青色。自定义画布允许留空和重叠，适合不规则、异面或有意镜像同一区域的屏幕。切回`自由分屏`即可恢复每面独立内容。

## 分辨率与网页 UI 缩放

每个逻辑屏幕可在控制台选择 100%、75%、50%、33% 或 25% 渲染分辨率。物理屏幕尺寸与 UV 不变，仅改变内容纹理/浏览器 viewport：

- 降低 WEB 分辨率会让 CSS viewport 变小，网页按钮和文字在世界屏幕上显得更大。
- 降低 VIDEO 分辨率会减少 FFmpeg 缩放、帧缓冲和纹理上传开销。
- 保存后相关会话会安全重建，不会每 tick 重建纹理。
- `default_resolution_percent` 可在 NeoForge 原生配置中设置新屏幕的默认比例。

## 操作

- `Shift + 右键`：打开屏幕控制台。
- 准星指向可控制的 WEB/VNC：只更新虚拟指针，不切换为独立 GUI 鼠标；没有键盘设备时不会转发物理按键。
- 手持 `minescreen:keyboard` 并瞄准屏幕：进入按键输入模式。此时移动键被键盘模式占用，无法行走属于预期行为。
- 右键已通过延长线连接屏幕的固定键盘：进入固定键盘输入模式，可有多个玩家/键盘同时输入。
- `Esc`：退出键盘输入模式，并释放网页请求的鼠标锁定。
- WEB请求鼠标锁定时，浏览器虚拟光标会回到中心，玩家视角会校正为垂直正对当前屏幕面，然后冻结MC相机旋转，避免保留奇怪仰角。
- 滚轮：准星仍位于屏幕时转发给页面/VNC；准星移开后立即恢复物品栏滚轮。
- 主手铁镐或更高等级镐 + 左键：拆卸屏幕，且该左键不会记录为内容点击；低等级镐、其他工具和空手没有拆卸进度。
- 控制期间仅在可配置角落显示小号高对比度“准星控制中”。

## 键盘、延长线与主机

- `minescreen:keyboard`：手持输入设备，只在持有并瞄准交互屏幕时占用按键。
- `minescreen:fixed_keyboard`：右键进入输入模式。可直接紧邻屏幕，或通过 `minescreen:screen_cable` 延长线连接。
- `minescreen:speaker`：被动声音端点，可直接紧邻屏幕或接入同一延长线网络以扩大位置音频覆盖范围。
- 延长线像管线一样根据相邻延长线、屏幕、主机、音箱和固定键盘自动生成六方向连接臂；拓扑使用有界搜索并在客户端 tick 批量更新，不会在 BER 中进行 BFS。
- `minescreen:computer`：通过相邻或延长线连接主屏幕。右键打开放大主机界面，左上角 `×` 退出。
- 主机“预览控制”自带键盘：点击预览取得WEB/VNC输入焦点，不需要手持或固定键盘；按`Esc`先释放输入，再按一次关闭主机。原版和其他模组热键会在KeyboardHandler入口被拦截。

主机界面包含：

- 主屏幕信息：显示拼合尺寸与实际分辨率，可逐个启用/禁用真实屏幕瓦片并切换分辨率。
- 播放内容：在同一个 GUI 页面同时显示媒体预览、WEB 标签栏、IDLE/VIDEO/WEB/VNC 控件、文本与文本框。
- 预览控制：中央实时预览默认启用鼠标，右侧提供音量控制。

MineScreen默认启用单纹理UI合成：媒体画面、控件框、文本、文本框内容和光标先进入同一透明纹理，再一次性绘制，避免ModernUI把文字当作独立后层模糊。`ui_provider` 可选择已注册的外部UI适配器；Otyacraft Engine Renewed、Cloth Config API和Architectury API需要各自的适配模块，因为它们不是同一种通用换肤接口。

NeoForge原生配置页、屏幕编辑器和主机面板可显示原创像素风二次元助手。`ui_show_mascot` 可关闭面板装饰；装饰不覆盖按钮、文本框或预览画面。

网页加载页支持配置文件自定义：`web_loading_style` 可选 `ORBIT`、`PULSE`、`MINIMAL`，并可调整 `web_loading_accent_color`、`web_loading_background_color`、`web_loading_speed_percent`、缩略图和像素助手开关。

关闭主机 GUI 不会关闭内容会话；主机方块正面会继续渲染缩小实时预览，作为待机显示。

## 引导与错误预防

MineScreen 屏幕编辑器集中显示内容模式、来源、分辨率、WEB 标签/分屏和配置说明；主机信息页另行显示拼合尺寸、屏幕 ID 与真实瓦片状态。保存前会检查：

- 本地 MP4 是否存在、可读取且扩展名正确；远程视频 URL 是否有效并通过协议、域名和解析后 IP 策略。
- WEB URL 是否有效以及是否通过协议、域名和解析后 IP 策略。
- VNC 地址是否符合 `vnc://host:port`。
- 联机媒体 ID 是否只含安全字符。
- MCEF/解码器后端错误与浏览器当前 URL。
- WEB 标签页列表；运行时新窗口自动切换，显式 `Shift + 加载` 标签会保存到本地 profile。

省略网页协议时自动补充 `https://`。安全策略阻止请求时不会静默降级，界面会提示需要检查的 NeoForge 配置。

## 网络安全策略

未开放 LAN 的集成单人世界默认启用 `unrestricted_singleplayer`：HTTP、HTTPS、任意域名、localhost、私网、cloud metadata、`file://` 和本地 MP4 均直接放行。单人本地 profile 不发布到集成服务器状态频道。

开放 LAN 或连接多人服务器后立即恢复安全默认值：

- 默认仅允许 HTTPS，并启用域名白名单。
- 默认阻止 HTTP、`file://`、localhost、回环地址、RFC1918/IPv6 ULA 私网、link-local 与 cloud metadata。
- `allow_http` 可显式允许 HTTP；`allow_localhost`、`allow_private_ip`、`allow_cloud_metadata`、`disable_whitelist` 和 `allow_file_protocol` 分别解除对应限制。
- 域名解析后的实际 IP 同样检查，以降低 DNS rebinding 风险。

配置入口为 `Mods → MineScreen → Config`，也可编辑 `config/minescreen-common.toml`。所有放宽选项均在 NeoForge 原生配置页和 TOML 注释中显示风险说明。

## 客户端与服务端部署

单机：

- 安装 MineScreen 与官方 MCEF NeoForge Mod。
- 视频、网页和 VNC 完全由本机客户端连接、解码与渲染。
- 未开放 LAN 时默认使用单人宽松策略。

多人：

- 服务器安装 MineScreen；dedicated server 不安装 MCEF，也不初始化 FFmpeg、CEF 或 OpenAL。
- Payload 只同步 `screen_id`、位置、尺寸、内容类型、公开 URL/VNC endpoint 或 `media_id`、播放时间戳、权限和音量；WEB 导航只发送去重后的活动 URL。启用P2P后额外同步直连端点和随机令牌，但不包含画面。服务端每5秒仅向附近玩家发送一次权威状态心跳。
- 默认不分配单人独占控制权。多个客户端各自连接内容源，VNC 服务器会直接接收各玩家的并发输入。
- 本地路径、密码、VNC framebuffer、视频帧和PCM永不进入游戏Payload。可选P2P的JPEG网页帧只走玩家之间的独立TCP连接。
- 服务端校验距离、维度、真实拼合拓扑、所有者/OP 权限和字段边界。

### WEB P2P安全边界

- 启用后，同一屏幕的参与玩家会获得彼此由服务器观察到的IP和监听端口。
- 随机令牌阻止未参加该屏幕会话的普通连接，但当前MVP没有TLS，不能抵抗链路窃听。
- 只同步最终网页画面与交互命令，不发送Cookie仓库、密码文件或DOM。页面中实际可见的敏感内容仍会进入压缩画面，因此不要对不可信玩家启用。

## 性能与线程模型

```text
客户端主线程
  ├─ 拼合缓存、配置、输入路由与生命周期
  ├─ 每 tick 最多完成一个 VIDEO/WEB/VNC 后端初始化
  └─ 不在 BER 渲染期间执行 BFS、DNS或文件探测

MineScreen 内容预检线程（单个 daemon worker）
  ├─ 本地文件、URL/DNS/IP策略与 VNC 端点校验
  └─ 8项有界队列；不调用 Minecraft、MCEF或OpenGL

FFmpeg 视频/音频线程
  ├─ 解码、RGBA 缩放、PCM 重采样
  └─ 有界环形缓冲；绝不调用 Minecraft/OpenGL

Minecraft 渲染线程
  ├─ 消费最新完整帧或 VNC 脏矩形
  ├─ NativeImage.upload -> _texSubImage2D
  ├─ 复用固定 texture id
  └─ WEB加载页使用8 FPS复用纹理；仅在关闭可见标签时读取一次缩略图

MineScreen WEB缩略图线程（单个有界daemon worker）
  └─ 将退出前画面缩至最多640×360并保存为JPEG；下次加载先显示缓存缩略图
```

默认最多同时保留8个已初始化内容后端、约3318万逻辑画布像素，以及每个WEB会话6个Chromium标签页。联合画布只计为一个会话；历史标签页按每tick一个恢复。这些上限可在NeoForge原生配置的 `max_active_content_sessions`、`max_active_canvas_pixels` 和 `max_web_tabs_per_session` 中调整。

延长线/主机拓扑使用5 tick缓存并按主机位置直接索引，避免每个渲染帧或每次打开GUI重复BFS。调整联合布局时保留同一内容会话：WEB不刷新页面，VNC不断开RFB；VIDEO只有画布像素尺寸实际变化时才重建缩放缓冲，并从当前媒体时间继续。

WEB主框架导航会显示旋转加载页；HTTP 4xx/5xx、DNS、连接和证书等CEF错误显示独立错误页。成功内容仍直接绑定MCEF纹理。缩略图位于`config/minescreen-web-thumbnails/`，只保存在客户端本地，不经游戏服务器或P2P元数据同步。

Minecraft 1.21.1 没有后续版本的 `GpuTexture` 类型。本项目使用该版本实际存在的 `DynamicTexture`、`NativeImage.upload`、`RenderSystem`、`RenderType`、`PoseStack` 和 `VertexConsumer`，未使用旧 `Tessellator.getBuilder()` 链。

## 预期列表

- 光学亮度适配：根据环境光、天空光和观看条件动态调整屏幕发光感、对比度与亮度，同时保留手动覆盖。
- 更完整的网页输入法/剪贴板桥接与无障碍缩放。
- VNC 加密传输适配和更多 RFB 编码。
- 跨平台 FFmpeg 原生分类器发布与自动诊断。

## 构建

要求 Java 21，使用仓库内 Gradle Wrapper：

```text
gradlew.bat build
```

关键依赖：

- `org.bytedeco:ffmpeg-platform:7.1-1.5.11`；Windows x64 发布包通过 NeoForge Jar-in-Jar 包含 FFmpeg/JavaCPP API 与原生分类器。
- `com.cinemamod:mcef:2.1.6-1.21.1` 仅编译使用。
- `com.cinemamod:mcef-neoforge:2.1.6-1.21.1` 仅开发运行；最终用户单独安装官方 MCEF，MineScreen 不打包 CEF 原生组件。

构建产物位于 `build/libs/minescreen-0.3.0.jar`。
