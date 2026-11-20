# 官方 Demo 交互对齐清单（A0/A9 产出）

> 参考 [deepseek-ai/chat-demo](https://github.com/deepseek-ai/chat-demo)（MIT）的**交互行为与数据语义**；
> 源码为 TypeScript/React，不复用其 Web 组件，全部按 Compose 原生重建（AT-19）。

## 页面与导航

| Demo 行为 | 本实现 | 状态 |
|---|---|---|
| 左侧抽屉：历史列表/搜索/新建 | `SessionsDrawer`（ModalNavigationDrawer） | ✅ |
| 单页聊天 + 底部输入 | `ChatScreen` + `InputBar` | ✅ |
| 设置页（Key/主题/语言/音色/数据） | `SettingsScreen` | ✅ |
| 移动端布局 + IME 适配 | `imePadding`/`WindowInsets` 原生 | ✅ |

## 输入区

| Demo 行为 | 本实现 | 状态 |
|---|---|---|
| 工具开关：深度思考 / 联网搜索 | `ToggleChip` 两组开关 | ✅ |
| 附件：照片/拍照/文件，粘贴与拖拽 | Photo Picker / TakePicture+FileProvider / SAF（拖拽/粘贴 V2） | ✅（拖拽未做） |
| 麦克风：点击录音，移动端首行实时频谱 | `RecordingPanel` + `Waveform`（音量驱动） | ✅ |
| 发送/停止生成 | 同按钮切换 | ✅ |

## 消息流

| Demo 行为 | 本实现 | 状态 |
|---|---|---|
| 用户靠右 / 助手靠左 | ✅ | ✅ |
| 思考块折叠展开、流式更新 | `ThinkingBlock` | ✅ |
| 搜索任务进度 + 结果来源卡 | `ServerToolUseBlock` + `SourceCard`（横向滚动） | ✅ |
| 链接阅读状态与来源 | `WebFetchResultBlock` | ✅ |
| 引用角标 + 可点开 | `CitationsRow`（外部浏览器） | ✅ |
| Markdown/代码块/表格 | mikepenz renderer（Compose 原生） | ✅ |
| 数学公式（KaTeX） | 源码样式呈现 | ⚠ 差异项 |
| Mermaid 图表 | 源码代码块呈现 | ⚠ 差异项（文档已声明） |
| 长按复制 | 用户气泡长按复制；助手复制按钮 | ✅ |
| 编辑提问 → 新分支、旧答案保留 | `editMessage` 分支树 | ✅ |
| 重新生成、切换分支 ‹1/2› | `regenerate`/`switchVersion` | ✅ |
| 自动标题、重命名、删除 | F10 + 抽屉操作 | ✅ |
| 流式跟随末尾，上滑停止追尾 | `MessageList` followBottom | ✅ |
| 朗读按钮/停止 | 消息操作行 | ✅ |
| 停止保留已收内容 | STOPPED 状态 | ✅ |
| 断流标记中断 | INTERRUPTED | ✅ |

## 语音

| Demo 行为 | 本实现 | 状态 |
|---|---|---|
| ASR：录音→转写→回填输入框（不自动发送） | `RecordingController`→`AsrExecutor` | ✅ |
| 120s/8MiB 上限自动停止 | ✅ | ✅ |
| TTS：整段合成、两种音色、焦点/来电处理 | `TtsPlayer` | ✅ |

## 历史

| Demo 行为 | 本实现 | 状态 |
|---|---|---|
| ZIP 导入导出（v3，fragments） | `HistoryZipFormat` + `HistoryImporterExporter` | ✅ 官网 REQUEST/RESPONSE/THINK；兼容旧 content/thinking |
| 导入替换确认、失败回滚 | 设置/侧栏确认后单事务替换 | ✅ |
| 导出不含 Key/非图片原件 | ✅ | ✅ |

## 明确不做 / V2

- WebSocket 流式 TTS（边生成边读）——文档 §3.5 明确 V1 不强制
- 实时逐字 ASR —— 非公开 API 能力
- 拖拽/粘贴附件、KaTeX/Mermaid 原生排版 —— 差异项，见上表
- 官方账户同步 —— 不在 V1 边界
