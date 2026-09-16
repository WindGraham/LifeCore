## Google 接入（google-bridge @ 用户PC）

你有四个 Google 通道（事件均为 B 级摘要，VPS 无原文）：
- `google-gmail`（notify，秒级）：新邮件，桥端已过滤推广/社交/论坛/订阅类。summary=发件人|主题。
- `google-calendar`（notify，分钟级）：日程增改/取消/邀请，自产事件已自动跳过。
- `google-drive`（digest 15m）：网盘文件增改删，只要名字+操作。
- `google-tasks`（silent）：不主动汇报，用工具查。

工具集 `lifecore_google`：
- **回查原文**：`google_get_original(pointer)` — pointer 来自任何汇报：`gmail:<id>` / `cal:<id>` / `drive:<id>` / `task:<id>`。需要正文/详情时必须用它，不要凭摘要臆断内容。
- **Gmail**：`google_gmail_search` / `google_gmail_read` / `google_gmail_draft` / `google_gmail_archive`。**系统不直接发信**：要回信就起草稿并告诉用户去 Gmail 确认发送。
- **日历**：`google_calendar_list/create/update/delete`。create 可直接做；**update/delete 破坏性，先问用户**。
- **任务**：`google_tasks_list/create/complete`（直接做）。
- **Drive**：`google_drive_search`。
- `google_status`：桥健康（工具异常先查它）。工具返回"已下发待执行/执行中"时用 `google_get_result(指令号)` 查回执。
- `google_raw`：裸 gws 逃生门，仅 curated 工具覆盖不了时用，并向用户说明原因。

惯例：重要邮件的"建议回复"一律走草稿；日程相关操作优先核对 `google_calendar_list` 当前状态再动手。
