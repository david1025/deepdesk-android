# 图标与品牌资源出处档案（ICON-01）

> 设计文档 §7.3 要求：先核实官方资源、记录出处与哈希，再生成 Android 密度资源；
> 不得以占位图标交付发行版，不得谎称“已从源码提取”。

## 当前状态

- **启动图标 / 应用内品牌标**（2026-11-19）：用户提供的港湾鲸鱼 PNG
  （`docs/brand/dsh-harbor-logo.png`）。自适应图标前景为该标按 60/108 内缩，
  浅色背景 `#F4F6FB`、深色背景 `#0B1220`（`values` / `values-night`），
  并烘焙 `mipmap-*` 与 `mipmap-night-*` 圆角矩形。应用内欢迎区使用
  `core/ui/.../ic_brand_mark.png`。这是本客户端自有标识，不是官方 DeepSeek 鲸鱼。
- **界面图标**（2026-11-19）：从用户提供的官方 `deepseek.apk` 解码矢量 XML，
  放入 `core/ui/src/main/res/drawable/ic_*.xml`（加号、语音、键盘、发送、复制等）。
  **未提取** `ic_logo_deepseek_vertical` / `ic_launcher_foreground` 鲸鱼品牌图。
  通过 Compose `tint` 适配明暗主题。`ic_chart_outline_20` 为用量统计自绘 20dp 描边图标。

## 已核实事实（2026-11-17）

1. `deepseek-ai/chat-demo` 仓库 `main` 分支无独立 PNG/SVG/ICO 图标资源，
   `src/index.html` 未声明 favicon（与设计文档 §7.3 的核对结论一致）。
2. 官方在线 Demo 页面的真实 favicon / 品牌资源需在**真机联调阶段**用浏览器资源检查核实，
   并补记：原始资源 URL、取得日期、SHA-256、权利声明，写入本档案后方可生成正式启动图。

## 发行前必办

- [ ] 核实并记录官方图标来源（URL + SHA-256 + 日期）或取得用户确认的官方品牌资源
- [ ] 以原始资源生成自适应图标 foreground/background 与 `mipmap-*` 全密度资源
- [ ] 真机 Launcher 截图（圆形/方形/圆角 mask）核对裁切与清晰度（AT-20）
- [ ] 公开发布前确认商标/标识使用授权；应用名称与关于页已标注「第三方客户端」
