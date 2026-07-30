# 线性双语车内 LCD 示例

该模板面向普通起点—终点线路，不假设线路首尾相接。默认一屏显示当前站附近的 8 个站，并
随运行位置向线路末端滑动。

## 主要能力

- 主语言与副语言独立配置，内置 `ja`、`en`、`zh`，默认中文主语言、英文副语言；
- 日文/中文使用大号竖排站名，英文使用倾斜横排；
- 副语言采用更小字号和较弱视觉权重；
- 已通过站、当前位置、下一站和后续站采用不同颜色；
- 分钟数按“距当前站”重新计算，而不是显示线路起点累计时间；
- 站号、服务种别、终点、车厢号和带线路色图标的换乘信息；
- `${game_time}` 在游戏内显示当前维度的 Minecraft 世界时间；
- 每一段路程使用独立的 `travelMinutes`，预计时间可以逐段调整；
- `route` 全线路窗口与 `next` 下一站强调页；
- 浏览器预览和无 npm 依赖的 Node.js 状态服务。

## 浏览器预览

直接打开 `preview.html`，或使用查询参数固定状态：

```text
preview.html?page=route&primary=zh&secondary=en&index=4&static=1
preview.html?page=next&primary=zh&secondary=en&index=4&static=1
preview.html?page=route&primary=en&secondary=ja&index=7&static=1
```

## 直接展示网页

如果只需要把屏幕展示给观众，直接打开 `showcase.html`。它没有编辑器面板，会自动轮换线路页
和下一站页，并在几秒后隐藏控制栏。也可以使用：

```text
showcase.html?primary=zh&secondary=en&page=auto&interval=9&langCycle=1&langInterval=8
showcase.html?primary=zh&secondary=en&page=route&index=4&autoplay=0&hud=1
```

快捷键：方向键切换站点、空格暂停、`P` 切换版面、`F` 全屏、`H` 显示/隐藏控制栏。
`langCycle=0` 可关闭语言轮换，`langInterval=8` 表示每 8 秒切换一次主/副语言组合；站点和版面切换会使用短距离滑入与淡入动画。
`showcase.html` 是由 `showcase.template.html`、`linear_lcd_generator.js` 和
`build_showcase.js` 生成的单文件版本；修改生成器后运行 `node build_showcase.js` 即可更新。

## 模组外可视化生成器

直接打开 `visual_editor.html`，这是一个无需安装 npm、无需启动 Minecraft 的本地可视化生成器。左侧编辑参数，右侧实时检查最终画面；所有数据只在浏览器本地处理，不会上传线路文件。

可以调整：

- 主/副语言、下一站、可见站数、车厢号和预览世界时间；
- 线路、种别、终点的中/日/英文本；
- 线路色、顶栏、背景、正文和已通过站颜色；
- 车厢号、游戏时间、预计时间、线路条、站名和换乘信息的位置；
- 主/副语言字号；
- 全部站点、每段 `travelMinutes` 与换乘线路图标；站点现在提供可视化编辑行，可直接改名、改路段分钟、调整顺序、添加或删除站点；
- 高级场景 JSON 中的任意图元坐标、字号、旋转、粗体和颜色。

编辑器可导出 MineScreen 可导入的 `manifest.json`，也可保存线路配置 JSON。导入 RMP JSON
或 `.rmp` 时，会列出检测到的线路供选择；选择线路后会带入线路颜色、线路缩写、站点名称和
共享站点换乘信息。RMP 没有统一的行车顺序，导入后仍需人工确认站点排序和路段时间。
编辑器提供“按图面方向 / 反向显示”选择，可修正地图绘制方向与列车运行方向不一致的情况。
对于只保存静态线路图、没有明确站点—线路关系的 RMP，编辑器会以线路边的连通分量、线路牌位置
和站点坐标进行保守匹配，并允许继续在站点编辑行中手动修正。

在“直接展示与网页嵌入”区域还可以生成两种成品，并分别调整站点轮换和语言轮换间隔：

- **独立展示 HTML**：包含线路数据、绘制代码和控制栏的单文件，双击即可播放，也可以直接上传到静态网站；
- **直接展示 JS**：不依赖框架或构建工具。把生成的 JS 与下面的容器放在同一页面即可自动显示：

```html
<div data-minescreen-lcd data-page="auto" data-controls="true"></div>
<script src="minescreen-generated-lcd.js"></script>
```

生成的 JS 也暴露 `MineScreenGeneratedLcd.mount(target, options)`，可在已有页面中手动挂载：

```javascript
const display = MineScreenGeneratedLcd.mount("#lcd", {
    page: "auto",       // route、next 或 auto
    index: 4,
    autoplay: true,
    controls: false
});
display.next();
display.setPage("next");
```

导出时可以选择默认版面、轮换间隔和是否显示控制栏。导出的 JS 将当前站点、路段时间、语言、颜色和图元全部固化，因此脱离编辑器仍可直接展示。

附带静态校对图：

- `preview-route-zh-en.png`：中文主语言、英文副语言；
- `preview-next-zh-en.png`：中文下一站强调页；
- `preview-route-en-ja.png`：英文主语言、日文副语言。
- `visual-editor.png`：模组外可视化编辑器。

## 线路标识规范

导入 RMP 后优先使用线路图中的线路编号作为圆形线路牌文字，并使用线路色作为底色；没有编号时才退回到线路缩写。线路名称会清理换行和内部节点 ID，不再把 `misc_node_*` 这类内部键显示给乘客。

常见线路类型会使用小图标提示：机场、航站楼和航空相关线路使用 `✈`，地铁使用 `M`，有轨电车使用 `T`，渡轮/港口使用 `≋`，公交使用 `B`。图标只作为辅助识别，线路编号和名称仍然保留。

## 自定义线路

编辑 `linear_lcd_generator.js` 顶部的 `ROUTE`：

```javascript
var ROUTE = {
    symbol: "MS",
    lineNames: {ja: "映界線", en: "MineScreen Line", zh: "映界线"},
    serviceNames: {ja: "快速", en: "Rapid", zh: "快速"},
    destinationNames: {ja: "中央港", en: "Central Harbor", zh: "中央港"},
    stations: [
        {
            code: "01",
            names: {ja: "北野", en: "Kitano", zh: "北野"},
            travelMinutes: 0,
            transfers: [
                {
                    symbol: "ML",
                    color: "#FF7E57C2",
                    names: {ja: "環状地下鉄", en: "Metro Loop", zh: "环线地铁"}
                }
            ]
        }
    ]
};
```

`travelMinutes` 是从上一站到本站的路段时间，首站填写 `0`。模板会自动累计并换算为距当前站
的预计时间。`transfers` 最多显示前两个项目，每项可以设置线路缩写、ARGB/RGB 颜色和多语言名。

## 导入 MineScreen

1. 打开交通显示屏或三棱柱吊顶显示屏的交通编辑器；
2. 选择“导入 RMP / 模板 / JS”；
3. 导入 `linear_lcd_generator.js`；
4. 保存。

默认 `generate()` 生成中文主语言、英文副语言、线路窗口页。若要更改默认值，修改文件末尾：

```javascript
LinearLcdDemo.createTemplateFromRoute(LinearLcdDemo.route, 4, "zh", "en", "route")
```

## Node.js 演示

```text
node server_demo.js
```

接口位于 `http://127.0.0.1:8766`：

- `/health`
- `/template`
- `/state`

当前 MineScreen 不会自动轮询该服务；它用于展示服务端如何生成同一结构化版面。正式多人同步
应传输线路状态、模板 ID/哈希和时间戳，不传输画面帧或远程 JavaScript 源码。

## TrainLCD 参考边界

本示例参考了 [jonhweider/TrainLCD](https://github.com/jonhweider/TrainLCD) 的公开产品思路：
局部站序窗口、多语言站名、通过站灰化、站号和动态字号。TrainLCD 使用 MIT License。

本目录没有复制其 React Native 组件、应用字体、图标、铁路数据库或运营方素材；绘制代码和
虚构线路数据均为 MineScreen 独立实现。
