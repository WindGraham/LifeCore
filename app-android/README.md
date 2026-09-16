# LifeCore Android App

Material Components 原生 Android 客户端（Kotlin）。**只跟 BFF 说话**（docs/06 接口原则），不直连 Hermes。

## 功能（对应 BFF 端点）
| 页 | 能力 |
|---|---|
| 配对 | 扫二维码（zxing）/手输配对码；指纹核对与中间人告警 |
| 对话 | 会话列表 → 聊天；/model /personality /new 等 slash 命令原生透传；语音输入（MiniMax STT 代理）；消息朗读（MiniMax TTS 代理） |
| 通知 | notify 待决策卡片（单活动锁）+ 队列视图；是/否/稍后反馈 |
| 通道 | webhook 通道注册（7 原型/uplink 级别/汇报模式）、查看 ingest_url+secret、长按删除 |
| 任务 | cron/巡查任务：列表、新建、立即执行、暂停/恢复、删除 |
| 设置 | 网关状态（平台/版本/指纹）、设备身份、服务器地址、频道级 prompt 覆盖表单、MCP 增删、原始 JSON 配置编辑（保存自动重启网关）、退出登录 |

## 构建
```bash
# JDK 17 + Android SDK（platform-34, build-tools 34）
JAVA_HOME=/usr/lib/jvm/java-17-openjdk gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 部署
APK 发布：`scp app-debug.apk root@windgraham.art:/var/www/html/lifecore.apk`
下载地址：https://windgraham.art/lifecore.apk

## 已知限制（MVP）
- 设备 token 存 SharedPreferences（Android Keystore 加密为下一版）
- 聊天为非流式轮询（chat/stream SSE 待接）
- 包名带 .debug 后缀
