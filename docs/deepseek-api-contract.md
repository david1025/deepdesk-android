# DeepSeek API 契约（A0 产出，2026-11-17 核对）

> 依据官方文档逐项核对：create-message / build_a_chatbot / web_tools / files_api / document_parsing / asr / tts / ocr / search / safety / news261113。
> 本文档是 `core/network`、`core/model`、`feature/chat` 的实现依据；协议演进时先改这里。

## 1. 主聊天（Messages，SSE）

- **端点**：`POST https://api.deepseek.com/anthropic/v1/messages`
- **头**：`x-api-key: <USER_KEY>`、`anthropic-beta: files-api-2025-04-14`（引用 file_id 时必需，本客户端固定携带）、`Accept: text/event-stream`
- **请求**（`RequestFactory` 统一生成）：
  - `model`: 默认 `deepseek-flash`。设置里的专业模式关闭时固定这个值；开启后从 `GET /models` 列出模型并写入所选 id。展示名用返回的 `name`。`input_modalities` 含 `image` 才支持视觉，否则客户端拒绝图片
  - `system`: 「你是由 DeepSeek 模型提供能力的 AI 助手。本应用是独立第三方客户端。」
  - `messages`: 活跃分支历史 + 新消息。文档附件为 `[{type:"text"},{type:"file",file_id}]`；图片必须是 `{type:"image",source:{type:"file",file_id}}`，不能用顶层 `type:"file"`
  - `thinking`: `{type: enabled|disabled}`（深度思考开关）
  - `output_config`: 仅专业模式且深度思考开启时发送 `{effort: low|high|max}`。关闭专业模式时不出现该字段
  - `tools`: `deepseek_web_search` / `deepseek_read_url`（按开关与 URL 检测决定）
  - `safety_check`: `{level: "high"}`（强制开启，§8）
  - `title`: `{enabled: true}`（仅第一轮）
  - `stream`: true
- **事件流**：`message_start → content_block_start → content_block_delta* → content_block_stop → … → message_delta → message_stop`，穿插 `ping`；**无** `data: [DONE]`。
- **块类型**：`text`（含 `citations`）、`thinking`（`signature` 必须保留）、`server_tool_use`（name ∈ web_search/deepseek_web_search/deepseek_read_url；input.action ∈ search/open_page/find_in_page/read_link）、`web_search_tool_result`（content 为 `web_search_result` 数组或 `{error_code}`）、`web_fetch_tool_result`（content.data 恒为空，read_link 正文在 signature）、`citations`。
- **Delta 类型**：`text_delta` / `thinking_delta` / `signature_delta` / `input_json_delta`（拼接后于 stop 解析）/ `citations_delta`。
- **终态语义**：
  - 成功 = 收到 `message_stop` 且无 failure；
  - `stop_reason: end_turn | max_tokens | stop_sequence | tool_use | refusal`（refusal 附 `stop_details{type,category,explanation}`）；
  - 断流无 `message_stop` → `INTERRUPTED`（不记成功，不自动重发）；
  - 用户取消 → `STOPPED`，保留已收内容。
- **错误结构**：HTTP 非 2xx `{"type":"error","error":{"type":...,"message":...}}`；SSE 内 error 事件同理。401→引导设置；402→余额；429→退避。

## 2. 文件（Files）

- **上传**：`POST /anthropic/v1/files`，multipart；**purpose 字段必须位于 file 字段之前**；`anthropic-beta: files-api-2025-04-14`；图片可省 purpose（本客户端显式传）。
- **purpose 表**（§5.6）：图片/jpeg/png/gif/webp → `user_data`；PDF → `rich_layout_recognition`（图文混排）；Office → `text_recognition`；文本/表格/电子书 → `user_data`。
- **大小上限**：图片 64 MiB；PDF/Office 100 MiB；文本/表格/电子书 32 MiB。本地预检（AttachmentTypeResolver），超限不上传。
- **删除**：`DELETE /anthropic/v1/files/{file_id}`（显式远端清理）。
- **消息引用**：文档 `{"type":"file","file_id":"..."}`；图片 `{"type":"image","source":{"type":"file","file_id":"..."}}`。附件记录持久化 `apiProtocol=ANTHROPIC_FILES`。

## 3. ASR（F11）

- **端点**：`POST /v1/tools/asr`（Bearer）；multipart `file`。
- **格式**：16-bit PCM WAV（8k/16k/22.05k/24k/32k/44.1k/48k）或 Ogg Opus；单声道/立体声（立体声取左声道）。
- **限制**：≤120 秒、≤8 MiB；本客户端采集 16 kHz/mono/16-bit，设备不支持时高采样率采集 + 线性降采样。
- **响应**：`{request_id, text, duration_ms}`；`text` 为空 → 提示未识别，不自动发送。
- **错误**：400 格式、413 超大、429 并发、503/504；失败保留临时 WAV 供重试，成功后立即删除。

## 4. TTS（F12）

- **端点**：`POST /v1/tools/tts`（Bearer），JSON `{text, voice_id: stella|tide, format: "pcm", filter_markdown: true}`。
- **音色**：公开接口只有 `stella`（海星）和 `tide`（暗潮）。客户端只提供这两项。
- **响应**：24 kHz / 16-bit / mono 裸 PCM 流式回包；HTTP 200 后中途断开按失败处理（不得宣称完整朗读）。
- **限制**：单次 ≤10,000 计费字符（汉字 2 字符）——长答案由 `TtsPlayer.segmentText` 分段连续播放。
- **焦点**：AudioFocusRequest；LOSS/LOSS_TRANSIENT 立即停止；同时最多朗读一条。

## 5. OCR（F14）

- **端点**：`POST /v1/tools/ocr`（Bearer），multipart `file`。
- **语义**：独立提取文字，不经过聊天模型；响应为逐页文本（`OcrResponse.pages[].text`，宽松解析）。
- **注意**：**不因普通聊天已上传文档而自动调用**；仅附件菜单「提取文字」显式触发；HTTP 200 + 业务 error 按失败处理。

## 6. 独立 Search / Fetch（F15）

- `POST /v1/tools/search`（Bearer，JSON `{query, ...}`）；`POST /v1/tools/fetch`（Bearer，JSON `{url, ...}`）。
- 响应结构以联调为准：`SearchApiResponse.data/results`、`FetchApiResponse.{url,title,content,text}` 保留原始 JSON 展示在诊断页。
- **使用边界**：用户聊天搜索优先由模型内置 `deepseek_web_search` 触发；独立接口仅供诊断页/未来工作流，避免重复计费。

## 7. 模型验证（F01）

- `GET https://api.deepseek.com/models`（Bearer）→ `{data:[{id,name,input_modalities,...}]}`。`name` 原样展示；`input_modalities` 含 `image` 即支持视觉。401/402/429 区分提示。

## 7.1 账户余额与用量

- `GET https://api.deepseek.com/user/balance`（Bearer）→ `{is_available, balance_infos:[{currency,total_balance,granted_balance,topped_up_balance}]}`。
- 无官方按日用量接口：本客户端把 Messages SSE 的 `usage`（input / output / cache_read）落到本地 `usage_records`，按日历日与会话汇总。
- 花费为本地估算：官方人民币定价（https://api-docs.deepseek.com/zh-cn/quick_start/pricing ，Flash / Pro，高峰/空闲，缓存命中/未命中/输出）；每条 `usage_records` 写入时记下 `isPeak`，界面用高峰/闲时标签标识时段，花费按合计展示；费用以官方账单为准。

## 8. 本实现与官方 Demo 的已知待联调项

| 项 | 状态 | 说明 |
|---|---|---|
| 自动标题事件字段 | ⚠ 待联调 | `title:{enabled:true}` 的下发位置（message_start/delta/独立事件）已做三处宽松解析，字段名以联调修正 |
| 独立 Search/Fetch 响应字段 | ⚠ 待联调 | 保留原始 JSON，诊断页可直验 |
| OCR 逐页字段名 | ⚠ 待联调 | `pages[].{page_no,text}` 宽松解析（page/page/content 别名） |
| ZIP v3 会话 JSON 精确字段 | ⚠ 待联调 | 导出/导入按官方 history/README 行为规范实现；与 format.ts 的字段名对齐需用官方导出样本验证 |
| Anthropic Files purpose 省略行为 | ⚠ 待联调 | 当前显式传 purpose；如与官方行为冲突按联调修正 |
