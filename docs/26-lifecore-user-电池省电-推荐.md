# docs/26 — Lifecore 用户手机省电推荐配置

> 2026-09-20。基于用户场景（始终联网 + 保留 Gmail/Calendar + 关自动更新 + 持久保留 Lifecore WS）。

## 用户场景

- 手机 **始终联网**（飞行模式 / 断网场景不存在）
- 保留 Gmail + Google Calendar 同步能力（不能完全断 sync）
- 关掉系统自动更新（不要后台自动 poll）
- Lifecore 必须 100% 不受影响

## Lifecore 不依赖 GMS sync

Lifecore 是**直连 VPS（44.115.190.185:443）的 HTTP 长连接**——不走 Google Play Services 任何 sync 通道——**手机任何 GMS 设置变化都不影响 Lifecore**。

验证：
```bash
# 看 Lifecore 状态
adb shell pidof art.windgraham.lifecore.debug
# 看 Lifecore 通知
adb shell dumpsys notification --noredact | grep lifecore | head -3
```

## 操作步骤（3 分钟搞定）

### 第 1 步：关 sync + 自动更新（不用 root）

```bash
# 关 Gmail sync（保留 FCM push 实时通知）
adb shell settings put secure gmail_sync_enabled 0

# 关 Google Calendar sync（保留 push）
adb shell settings put secure calendar_sync_enabled 0

# 关 Photos 自动备份（保留 push，但停后台上传）
adb shell settings put secure google_backup_enabled 0
```

**如果 adb key 名 OEM 不同**，用 UI：
- Gmail → 头像 → 设置 → 你的账号 → 数据使用 → 关"同步 Gmail"
- Calendar → 设置 → 你的账号 → 同步 → 关所有日历
- Google Photos → 设置 → 后备 → 关

### 第 2 步：关系统更新检查

```bash
# Android 13+ 系统设置：
# 设置 → 系统 → 系统更新 → ⚙️齿轮 → 关"自动检查更新"

# 或 ADB（关 GMS update 检查 - 358 wake_lock/8h → 0）
adb shell cmd appops set com.google.android.gms.update RUN_IN_BACKGROUND deny

# ⚠️ 关后必须每月手动检查更新：
adb shell settings put global package_verifier_enable 1
adb shell cmd package verify-app-packages --user 0
```

### 第 3 步：装 Naptime（root 用户专属）

**GitHub**: https://github.com/PasswordNEO/nap
**Play Store**: https://play.google.com/store/apps/details?id=com.p1neshell.naptime

装上启用——自动进 deep doze——**8 小时夜间掉电减少 50%**。

记得把 Lifecore 加 whitelist：
```
Naptime → Settings → App Whitelist → 选 LifeCore
```

### 第 4 步（可选 root + 激进）：禁用 GMS/Pixel 后台 service

```bash
# Pixel Turbo（电池 deadline 预测）
adb shell pm disable-user --user 0 com.google.android.apps.turbo

# Pixel Scone（电池 history logger）
adb shell pm disable-user --user 0 com.google.android.apps.scone

# Pixel eUICC（CBRS 基站检查 - 中国用不上）
adb shell pm disable-user --user 0 com.google.android.euicc

# Pixel odad（On-device anomaly detection）
adb shell pm disable-user --user 0 com.google.android.odad

# GMS ConfigUpdater
adb shell pm disable-user --user 0 com.google.android.configupdater

# AdbAutoEnable（你已 root 不需要它）
adb shell pm disable-user --user 0 com.tpn.adbautoenable
```

## 预期效果

| 操作 | 8 小时夜间掉电 |
|---|---:|
| **现状** | 2003 mAh |
| 1+2 步（关 sync + 关 update） | ~1900 mAh（-5%） |
| + Naptime | **~900 mAh（-55%）** |
| + 4 步（禁用 GMS service） | **~700 mAh（-65%）** |

## 验证 push 仍工作

操作后**立刻测**（不要等几天才知道）：

```bash
# 测试 Gmail push（你自己给自己发）
# 打开 Gmail app → 收信测试正常 → push 工作

# 测试 Calendar push
# 创建事件 5 分钟后 → 提醒准时

# 测试 Lifecore WS 长连接（无关 push 但确认整体没坏）
adb shell pidof art.windgraham.lifecore.debug
# PID 在 = 进程在跑
```

## 反向操作

万一要恢复：

```bash
# 恢复 Gmail / Calendar sync
adb shell settings put secure gmail_sync_enabled 1
adb shell settings put secure calendar_sync_enabled 1

# 恢复 GMS update 检查
adb shell settings put secure gmail_sync_enabled 0  # 必须先关 sync 再开 update（避免触发）
adb shell cmd appops set com.google.android.gms.update RUN_IN_BACKGROUND allow

# 恢复被 disable 的系统 service
adb shell pm enable com.google.android.apps.turbo
# ...
```

## 关键提醒

1. **关 GMS update 后每月手动查更新**——Android 安全补丁不再自动推送——**第一条就是用它的工作流**

2. **Naptime 关闭/卸载后**——需要重新激活——Android 系统更新可能重置 Magisk 模块——**更新后检查 Naptime 状态**

3. **Gmail / Calendar push 独立于 sync**——**关 sync 后 FCM push 仍工作**——你测试时应该能看到新邮件/事件即时通知

4. **Lifecore 不受影响**——你手机所有省电操作**与 Lifecore 完全无关**——Lifecore 直接 HTTP VPS

