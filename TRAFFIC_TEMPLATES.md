# MineScreen 交通模板、RMP 与脚本导入

MineScreen 将交通显示拆成三个互不重复的层：

| 层 | 负责内容 |
|---|---|
| Rail Map Painter（RMP）导入层 | 静态站点坐标、线路拓扑、站名和近似线路色 |
| MineScreen 交通状态层 | 线路、终点、当前/下一站、ETA、状态、多人同步和动画时间 |
| 可选 JS 生成层 | 在导入时生成文字、矩形、椭圆和线段组成的声明式车内版面 |

RMP 不提供统一的实时“当前站/下一站”语义。因此导入 RMP 只替换视觉模板，不会根据
Graphology 节点数组顺序猜测行车顺序，也不会覆盖编辑器中已有的实时交通字段。

## 文件位置与导入

在交通显示屏或三棱柱吊顶显示屏的交通模式中点击“导入 RMP / 模板 / JS”，可选择：

- Rail Map Painter 官方导出的 `RMP_<时间戳>.json`；
- 内容同为 JSON 的 `.rmp` 便利别名（这不是 RMP 官方扩展名）；
- MineScreen `manifest.json`；
- MineScreen 导入时 JavaScript 生成器 `.js`。
- 直接选择 `.png`，作为 `Station_Next` 的全画布自定义背景。
- UTF-8 `.txt` 动态文稿；导入后按世界时间同步翻页。

导入结果保存在客户端：

```text
config/minescreen/traffic_templates/<template_id>/
  manifest.json
  rmp-map.json              # 导入 RMP 时生成的紧凑数据
  rail_map.png              # 可选背景图
```

## 动态 TXT 文稿

交通显示屏设置页打开“内容与提示”，在“动态文字提示层”一行点击“导入 TXT”。TXT 不会
替换基础 LCD/RMP/Station_Next 模板，而是在原画面最后绘制一条动态提示；线路、站点、ETA
和列车动画仍会继续更新。普通交通显示屏、45°车门 LCD、60°吊顶屏和车内简要屏均可使用。
导入后保存的是独立的受限声明式提示模板，不保存本地绝对路径；多人同步提示内容和计时参数，
翻页时钟使用世界时间，服务器不持续传输画面帧。

用单独一行 `---` 分隔页面：

```text
@title=乘客公告
@loop=true
@default_duration=4s
@default_transition=fade
@default_align=center
@position=bottom
@background_color=#D908131F
@text_color=#FFFFFF
欢迎乘坐本次列车
Welcome aboard
---
@duration=3s
@transition=slide_left
下一站：中央港
Next stop: Central Harbor
```

全局指令必须位于第一段文字之前：

- `@title`：模板名称；
- `@loop=true|false`：是否循环；
- `@default_duration=4s`：默认停留时间，也可使用 `2000ms` 或 `80t`；
- `@default_transition=none|fade|slide_left|typewriter|blink`；
- `@default_align=left|center|right`；
- `@position=top|center|bottom`：提示条位于原画面的上方、中央或下方；
- `@text_color`、`@background_color`、`@accent_color`：`#RRGGBB` 或
  `#AARRGGBB`；提示条建议使用带透明度的背景，例如 `#D908131F`。

每个页面开头可用 `@duration`、`@transition`、`@align` 覆盖默认值。单文件最多
128 KiB、64 页，每页最多 2048 个字符；未知指令会明确报错，避免拼写错误被静默忽略。
可直接使用
[动态公告示例](examples/traffic_templates/dynamic_text_notice/example.txt)。

多人会同步模板 ID、结构化交通字段，以及通过校验的 `script_scene_v1` 声明式画面清单。客户端
先发送 SHA-256 与大小，服务器只在缺少该版本时请求 24 KiB 分块；相同哈希不会重复传输。
服务器和接收客户端都不会执行上传者的 JS。JS 源码、本地绝对路径、RMP 原工程、PNG 和其他
侧载资源不上传；含这些本地资源的模板仍为本地模板，其他玩家缺失时回退到内置样式。

服务器将已验证清单保存在世界目录的 `minescreen/traffic_templates/`，每个最多 256 KiB，数量由
`max_synced_traffic_templates` 限制。同名模板首次上传者可更新自己的模板，管理员可以替换任意
模板，其他玩家不能覆盖已有 ID。

## Rail Map Painter 原生 JSON

MineScreen 支持 Rail Map Painter 当前通用保存结构：顶层 `version`、`graph`，以及 Graphology
序列化的 `nodes`/`edges`。导入器读取：

- 可见 `stn_*` 节点；
- `x`、`y` 坐标；
- 站点类型对象中的 `names`；
- 可见边的端点与可识别颜色。

RMP 线路牌节点中的 `num` 是乘客可见标识，MineScreen 会优先保留它。配色数组中的 `JY`、
`JK` 等系统代码只作为 `systemCode` 元数据保存，除非用户主动填写，否则不会覆盖线路牌。

这是结构化简化导入，不是 RMP 的像素级渲染器。曲线路径、特殊站点 SVG、字体、Master
节点、嵌入图片、图层和城市专属样式会被忽略或降级为直线/方形站点。历史保存版本只保证
尽力读取上述稳定字段；无损工作流仍是从 RMP 导出 PNG，再作为 `background_image` 使用。

MineScreen 的适配器是独立实现，不复制或打包 GPL-3.0-only 的 RMP React/SVG 渲染与迁移代码。

## 声明式模板

最小 `manifest.json`：

```json
{
  "id": "metro_blue",
  "name": "Metro Blue Line",
  "layout": "builtin_transit_v1",
  "background_color": "#FF081522",
  "text_color": "#FFFFFFFF",
  "accent_color": "#FF3A9BFF",
  "background_image": "rail_map.png"
}
```

仓库中的 [traffic-template-editor.html](tools/traffic-template-editor.html) 是无需安装的模组外
编辑器。模板 ID 只允许小写字母、数字、点、下划线和连字符。

## JavaScript 车内版面生成器

导入带有 `directions.stations` 的 JS/JSON 后，车厢显示屏编辑器会提供“配置 JS 站点与
Create 站名”。向导按模板站点逐页保存映射，例如：

```json
{
  "station_bindings": {
    "C1": "abcd 上行",
    "C2": "efgh 上行",
    "C1D": "abcd 下行"
  }
}
```

上行、下行使用不同 Create 站台时必须保存为不同模板代码。向导会读取附近已加载的 Create
车站和当前列车的时刻表作为建议；它不会模糊替换用户已经确认的完整站名。映射保存在导入模板
的 `manifest.json` 中，声明式无侧载模板可继续使用既有哈希同步机制。

JS 是 MineScreen 自己的导入协议，不是 RMP 官方脚本格式。脚本必须定义一次性的
`generate()`，返回 `script_scene_v1`：

```javascript
function generate() {
  return {
    id: "carriage_next_stop",
    name: "Carriage next-stop display",
    layout: "script_scene_v1",
    background_color: "#FF02070A",
    text_color: "#FFFFFFFF",
    accent_color: "#FF36E6FF",
    elements: [
      { type: "rect", x: 0.02, y: 0.08, width: 0.96, height: 0.84,
        color: "#FF061820" },
      { type: "text", x: 0.50, y: 0.18, align: "center", size: 1.5,
        color: "#FF36E6FF", text: "${line}  →  ${destination}" },
      { type: "line", x: 0.10, y: 0.55, x2: 0.90, y2: 0.55,
        size: 2.0, color: "#FF36E6FF" },
      { type: "text", x: 0.50, y: 0.66, align: "center", size: 1.25,
        color: "#FFFFFFFF", text: "Next: ${next}  ${eta}" }
    ],
    traffic: {
      line: "A1",
      destination: "Central",
      next: "Museum",
      eta: "2 min"
    }
  };
}
```

坐标、宽高使用 `0..1` 的画布比例。支持图元：

- `text`：`x`、`y`、`size`、`rotation`（角度）、`vertical`（竖排）、`bold`（粗体）、
  `color`、`align`、`text`；
- `rect`：`x`、`y`、`width`、`height`、`color`；
- `ellipse`：`x`、`y`、`width`、`height`、`color`；
- `line`：`x`、`y`、`x2`、`y2`、`size`、`color`。

文字绑定包括 `${line}`、`${destination}`、`${current}`、`${next}`、`${eta}`、`${status}`、
`${train_name}`、`${service_type}`、`${carriage_number}`、`${carriage_count}`、
`${upcoming_stops}`、`${direction}`、`${arrival_time}` 和 `${game_time}`。
`${direction}` 会动态生成为“终点站方向”（闭环为当前下一站方向），`${arrival_time}` 是随
ETA 和当前世界时间变化的预计到站世界时刻。`${game_time}` 由客户端渲染器从当前维度的 Minecraft
世界时间换算为 `HH:mm`，不会作为固定字符串写入模板或网络包。

车载模板不要直接写死“快速”“7号车”或示例终点站。模板可以只使用自己需要的任意一部分
占位符；MineScreen 不会再因为缺少某个固定占位符而抛弃整个样式并切换到内置版面。绑定由
Java 渲染器使用当前同步字段替换，JS 不会在每帧重新执行。该版面既能用于普通交通显示屏，
也能用于三棱柱吊顶显示屏的两个交通面。

第三方模板可增加 `"manifest_version"` 与任意未知顶层字段；导入时原始 JSON 会完整保留。
当前版本支持的图元仍为 `text/rect/ellipse/line`。由未来版本提供、但旧版可以安全忽略的装饰
图元应填写 `"optional": true`，这样旧客户端会跳过该层而保留其余样式。自定义静态占位符可
通过以下字段提供默认值：

```json
{
  "placeholder_defaults": {
    "operator_name": "Demo Railway",
    "custom_notice": "Please mind the gap"
  }
}
```

模板文字中的 `${operator_name}` 会使用该值。没有内置实现且没有默认值的未知占位符在游戏
画面中显示为空白，而不会把 `${future_field}` 原样暴露给乘客。

Manifest 的导入/保存采用无损往返规则：未知顶层字段、未知图元样式字段和显式 `null` 都会
保留；使用 `{ "manifest": { ... } }` 或 `{ "template": { ... } }` 包装时，外层的扩展字段
也会合并进实际清单，实际清单中的同名字段优先。建议第三方扩展使用带项目名前缀的键，例如
`"myaddon:font_feature"`，以免未来标准字段重名。

`line` 的线宽可使用 `stroke_width`、`line_width` 或兼容字段 `size`，范围为 `0.05..64`；
`text.size` 独立使用 `0.05..4` 的文字缩放范围。线宽不再套用文字字号上限，因此 LCD Studio
导出的粗线路（例如 `size: 24`）可以在本地渲染和多人同步中保持原样。旧客户端无法绘制的
全新图元必须提供 `"optional": true`；旧客户端会保留该定义供升级后使用，但不会猜测其
视觉含义。影响主要信息、不可安全忽略的新图元不应标记为可选，此时旧客户端会明确拒绝模板，
而不是静默显示一块不完整的版面。

完整可运行示例见 [JR-East-inspired 车内 LCD](examples/traffic_templates/jr_east_lcd/)：包含可导入
脚本、直接打开的浏览器预览、随机站点模拟和无 npm 依赖的 Node.js 状态服务端。

非环状线路优先使用
[线性双语车内 LCD](examples/traffic_templates/linear_dual_language_lcd/)：主/副语言分别配置，
显示当前站附近的有限站序窗口，并对已通过站、下一站、相对分钟数和换乘信息分层处理。
该目录的 `visual_editor.html` 是独立浏览器生成器，可编辑线路数据、路段时间、颜色、布局参数
与全部声明式图元，并能读取 RMP 中可识别的线路色、工程名和站点名称。
同目录的 `showcase.html` 是面向观众的无编辑器全屏展示页，适合直接打开或放入网页容器。

### LCD Studio 的方向、语言与到站前预报

线性 LCD 生成器把语言作为完整组合档轮换，避免主、副语言被独立轮换而混淆：

```json
"language_profiles": [
  {"id":"zh-en", "primary":"zh", "secondary":"en"},
  {"id":"ja-en", "primary":"ja", "secondary":"en"},
  {"id":"zh-ja", "primary":"zh", "secondary":"ja"}
]
```

页面类型为 `route`（线路窗口）、`next`（下一站）和 `arrival`（到站前预报）。`arrival` 在站名
主体区域显示下一站、换乘线路、换乘站号与到站提醒，顶部线路、车厢号和时间栏保持固定；自动切换只移动主体区域，并遵守
`prefers-reduced-motion`。英文长站名会按可用宽度压缩字号，不会挤出画面。

上下行可以在同一配置中独立保存：

```json
"active_direction": "up",
"directions": {
  "up":   {"label":"上行", "stations":[/* ... */]},
  "down": {"label":"下行", "stations":[/* ... */]}
}
```

每站的 `travel_minutes` 会生成 `station_times`，可用于车内报站预估。`coordinate_binding` 可保存
LCD Studio 手动输入的 Create 站台坐标；Mod 端只读取有限的维度、位置与半径字段，Create 内部站名
与 LCD 显示名仍由适配层甄别，不会直接覆盖显示文本。

## 脚本与资源安全边界

- JS 仅在玩家主动选择本地文件时执行一次；网页和服务器不能触发导入；
- 使用一次性子 JVM：64 MiB 堆、64 MiB 元空间、5 秒 guest 指令/时间预算和 15 秒启动/死锁看门狗；
- 清空子进程环境，不提供 Java、文件、网络、DOM、Canvas、`fetch`、`require` 或进程接口；
- 源脚本与输出各不超过 256 KiB，最多 256 个声明式元素；
- manifest 最大 256 KiB，RMP 最大 16 MiB，紧凑 RMP 图最大 2 MiB；
- RMP 最多 2048 个可见站点、4096 条边；
- PNG 最大 8 MiB、约 16.7 MP，并在解码分配前校验 PNG IHDR；
- 路径必须位于对应模板目录，阻止绝对路径和 `..` 穿越；
- 不执行 RMP Master、旧表达式、任意 SVG、`script` 或 `foreignObject`；
- 服务端不接收或执行 JS 源码；
- 多人模板同步只接受 `script_scene_v1`、最多 256 个图元的 UTF-8 JSON；使用 SHA-256 校验、
  固定分块长度、临时文件与原子替换，拒绝背景图片和 RMP 侧载文件引用。

即使具有上述限制，也只应导入你信任的本地脚本。若需要处理敌意第三方脚本，应使用未来的
独立 GraalJS isolate 组件，而不是核心模组中的轻量导入器。

## 许可证与归属

Rail Map Painter 与 RMP Designer 为 GPL-3.0-only；MineScreen 只实现数据互操作，不分发其代码
或素材。RMP 工程可能包含运营方版式、字体、OpenMoji 或用户图片，其使用许可仍由导入者负责。
Rhino 以 MPL-2.0 分发，版本与来源见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
