# JR-East-inspired 车内 LCD 示例

这是原创的 JR 东日本车内 LCD 风格演示，不包含 JR 东日本标志、官方字体、官方图标、视频
截图或生产屏幕素材，也不保证复刻实际列车显示逻辑。版式研究只截取了参考视频中的部分状态，
并结合用户补充的三张页面参考图重新绘制为声明式图元。

## 浏览器预览

直接双击 `preview.html`。页面支持：

- 完整 30 站环线数据，并可随机改变演示起点；
- 手动或自动进入下一站；
- 日英双语站名；
- 显示当前站、下一站、终点、ETA 和状态；
- 支持逐站线路条、日文双排全环线、英文圆角环线总览、弧形剩余时间与换乘信息；
- 英文、日文汉字和假名页面切换；
- 连接随附的 Node.js 服务端演示。

也可在地址末尾选择固定页面，便于浏览器截图或模板校对：

```text
preview.html?page=next&mode=english
preview.html?page=loop&mode=japanese
preview.html?page=loop&mode=english
preview.html?page=time&mode=japanese
```

仓库已附带四张静态校对图：

- `preview-overview-ja.png`：日文双排全环线；
- `preview-overview-en.png`：英文顶栏与圆角全环线；
- `preview-time-ja.png`：弧形剩余时间与换乘；
- `preview-next-en.png`：逐站下一站页。

圆角环线与大弧形由多段声明式线段组合，站点使用椭圆图元，日文站名使用竖排文字属性；没有
嵌入参考图作为背景，因此在 Minecraft 字体度量和不同屏幕比例下会存在少量排版差异。

## 导入 MineScreen

1. 打开交通显示屏或三棱柱吊顶显示屏的交通编辑器；
2. 点击“导入 RMP / 模板 / JS”；
3. 选择 `jr_east_lcd_generator.js`；
4. 保存设置。

MineScreen 调用固定种子的 `generate()`，默认导入英文圆角环线总览，因此所有使用同一生成
结果的客户端不会因随机数产生不同布局。若要更换默认页，可修改脚本末尾 `generate()` 调用的
`mode`/`page` 参数。运行中的站点变化由 `${current}`、`${next}` 等绑定字段完成，不会每帧执行
JS。

## Node.js 服务端演示

安装 Node.js 后在本目录运行：

```text
node server_demo.js
```

然后在 `preview.html` 中点击“连接本地服务端”，或者访问：

```text
http://127.0.0.1:8765/state
```

接口包括：

- `/health`
- `/template`
- `/state`

`server_demo.js` 只使用 Node.js 内置模块，无 npm 依赖。

## 移植到 Minecraft 服务器

推荐只移植 `updateState()` 的状态机思想，并将输出映射为 MineScreen 的结构化字段：

```text
line / destination / current / next / eta / status
```

服务器应同步这些字段、模板 ID 和未来的模板 SHA-256；不要把 JS 源码下发给客户端执行。
目前 MineScreen 的外部 HTTP 数据源适配尚未加入，因此这个 Node 服务是独立参考实现，不会自动
修改世界中的展示板。
