# MineScreen

把网页、视频和远程桌面真正放进 Minecraft 世界。

MineScreen 面向 Minecraft Java 1.21.1、NeoForge 21.1.219 和 Java 21，为相邻屏幕方块提供自动拼合画布、本地 MP4、MCEF 离屏网页、纯 Java RFB/VNC、位置音频以及可选服务端状态同步。服务端只管理配置、时间戳、权限和可选P2P信令，绝不转发像素、音频帧或玩家输入。

## 快速开始

1. 客户端安装 MineScreen 和官方 MCEF NeoForge Mod。
2. 放置 `minescreen:screen`；上下/左右相邻且朝向相同的方块会自动拼合。
3. `Shift + 右键` 任意瓦片打开 MineScreen 控制台。
4. 选择 `IDLE`、`VIDEO`、`WEB` 或 `VNC`，按界面提示填写并选择“保存并应用”。
5. WEB/VNC 可控制模式下，将准星移到屏幕上即可点击和滚动；文字/按键输入需要手持键盘或连接的固定键盘。

屏幕使用主手铁镐或更高等级镐拆卸。主手工具满足掉落等级时，左键不会发送给网页/VNC，而会保留为原版破坏键。

## 屏幕与拼合

- 同一维度、同一朝向、同一平面且四邻接的方块构成一个逻辑屏幕。
- 每个瓦片默认贡献 720×720 逻辑像素；全局上限为 3840×2160、约 8.3 MP。
- L 形等不规则组件仍按矩形包围盒计算连续 UV，但只为真实瓦片提交几何。空洞完全透明、不可命中，也不会产生无碰撞的黑色单面。
- 行列由世界坐标计算，不依赖放置或集合遍历顺序。
- 主方块按 `(Y, 屏幕右方向坐标, tile_id)` 决定；旧 Stage-1 多格锚点继续以兼容模式渲染。

## 内容模式

### IDLE

默认空闲模式，不发起任何外部连接。屏幕显示色彩测试图、方向参考线以及中央高对比度 `IDLE` 字样，便于检查拼合与 UV。

旧版客户端配置里的 `TEST` 会在读取时自动迁移为 `IDLE`。

### VIDEO

- 本地 MP4、自动播放、循环、暂停、进度跳转和最高 30 FPS。
- FFmpeg 视频线程写入 3 槽有界环形缓冲，渲染线程只消费完整帧并更新同一个 DynamicTexture。
- 视频保持原始宽高比，使用 letterbox/pillarbox，绝不拉伸。
- FFmpeg/OpenAL 音频作为位置音源播放，并用音频时钟校准视频。
- 超距、离屏、跨维度或区块卸载时暂停/停止工作，恢复后按媒体时间戳追平。

### WEB

- 使用 MCEF 2.1.6 的离屏 Chromium，viewport 与当前屏幕渲染分辨率一致。
- 页面普通链接、重定向、子资源和 `target=_blank` 都经过统一安全检查。新窗口目标会创建 MineScreen 内部标签页并自动切换过去。
- 控制台与主机内容页提供顶部标签栏、加载/跳转、后退、前进和刷新。普通加载更新主页；`Shift + 加载` 新建并持久化一个标签页；点击标签切换，`Shift + 点击标签` 关闭。
- 多人服务器同步当前活动页面 URL：重定向链稳定 750ms 后才发送一次，服务端去重、限速并只广播给同步距离内玩家；不传输网页像素、DOM、Cookie 或表单数据。
- 可选去中心化同步渲染：同屏客户端按UUID构造确定性二叉树，根节点读取MCEF纹理并以JPEG分发；中继节点原样转发已压缩帧，不重复编码。鼠标、键盘、导航和标签页命令沿树回传到根浏览器，因此所有节点观看同一动态页面。
- P2P默认关闭。服务器和客户端都需启用 `web_peer_distribution`；默认1280宽、最高8 FPS、JPEG质量58、总上传约8000 Kbps。静止画面会按压缩帧校验去重，超出上传预算直接丢帧以保持低延迟。
- P2P使用玩家直连TCP，Minecraft服务器只发送观察到的IP、端口和随机令牌。LAN通常可直接工作；公网NAT通常需要固定 `web_peer_listen_port` 并转发该TCP端口。直连失败或5秒无新帧时自动回退本地MCEF。
- 准星 UV 是网页唯一的虚拟鼠标位置，每个渲染帧重新计算。所有内容使用统一的观察者右方向坐标，因此画面与点击不再沿中轴镜像。

### VNC

- 内置 RFB 3.3/3.7/3.8 客户端，支持 None、经典 VNC DES 验证、Tight、Raw、CopyRect、DesktopSize 与 LastRect。
- Tight支持4路持久zlib、Copy/Palette/Gradient、Fill、JPEG和PNG；默认请求压缩等级9、JPEG质量5，并可通过NeoForge配置调整。
- 脏矩形只更新 DynamicTexture 对应区域。
- 默认允许控制；切换为只读后停止转发输入。准星鼠标和键盘设备输入会映射到远端 framebuffer。
- 不再申请单人独占租约；多个玩家默认可同时连接并控制同一屏幕所指向的 RFB 端点。
- 密码仅保存于 `config/minescreen-vnc-credentials.json`，不会进入服务器状态或网络包。

经典 RFB 不加密。即便密码只存本地，也建议通过可信 VPN、SSH 或 TLS 隧道连接。

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
- `Alt + Esc`：退出键盘输入模式。Windows 将它保留为系统级窗口切换快捷键，可能不会传给游戏，因此同时支持普通 `Esc` 作为可靠备用。
- 滚轮：准星仍位于屏幕时转发给页面/VNC；准星移开后立即恢复物品栏滚轮。
- 主手铁镐或更高等级镐 + 左键：拆卸屏幕，且该左键不会记录为内容点击；低等级镐、其他工具和空手没有拆卸进度。
- 控制期间仅在可配置角落显示小号高对比度“准星控制中”。

## 键盘、延长线与主机

- `minescreen:keyboard`：手持输入设备，只在持有并瞄准交互屏幕时占用按键。
- `minescreen:fixed_keyboard`：右键进入输入模式。可直接紧邻屏幕，或通过 `minescreen:screen_cable` 延长线连接。
- 延长线像管线一样根据相邻延长线、屏幕、主机和固定键盘自动生成六方向连接臂；寻路使用最多 256 节点的有界搜索，不会在 BER 中进行无限 BFS。
- `minescreen:computer`：通过相邻或延长线连接主屏幕。右键打开放大主机界面，左上角 `×` 退出。

主机界面包含：

- 主屏幕信息：显示拼合尺寸与实际分辨率，可逐个启用/禁用真实屏幕瓦片并切换分辨率。
- 播放内容：在同一个 GUI 页面同时显示媒体预览、WEB 标签栏、IDLE/VIDEO/WEB/VNC 控件、文本与文本框。
- 预览控制：中央实时预览默认启用鼠标，右侧提供音量控制。

MineScreen默认启用单纹理UI合成：媒体画面、控件框、文本、文本框内容和光标先进入同一透明纹理，再一次性绘制，避免ModernUI把文字当作独立后层模糊。`ui_provider` 可选择已注册的外部UI适配器；Otyacraft Engine Renewed、Cloth Config API和Architectury API需要各自的适配模块，因为它们不是同一种通用换肤接口。

关闭主机 GUI 不会关闭内容会话；主机方块正面会继续渲染缩小实时预览，作为待机显示。

## 引导与错误预防

MineScreen 控制台会显示当前拼合尺寸、屏幕 ID、实际渲染分辨率和与当前模式相关的配置说明，并在保存前检查：

- MP4 是否存在、可读取且扩展名正确。
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
  └─ 不在 BER 渲染期间执行 BFS

FFmpeg 视频/音频线程
  ├─ 解码、RGBA 缩放、PCM 重采样
  └─ 有界环形缓冲；绝不调用 Minecraft/OpenGL

Minecraft 渲染线程
  ├─ 消费最新完整帧或 VNC 脏矩形
  ├─ NativeImage.upload -> _texSubImage2D
  └─ 复用固定 texture id
```

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

构建产物位于 `build/libs/minescreen-0.2.0.jar`。
