# root Pixel 一键授权清单（装完 App 后在 adb/root 终端执行一次）

```bash
PKG=art.windgraham.lifecore.debug   # release 包去掉 .debug

# 1) 电池优化白名单（最重要的一个，防 Doze 杀前台服务）
adb shell dumpsys deviceidle whitelist +$PKG

# 2) 后台运行/自启动放开
adb shell appops set $PKG RUN_IN_BACKGROUND allow
adb shell appops set $PKG RUN_ANY_IN_BACKGROUND allow

# 3) 锁屏全屏提醒（alert 级汇报锁屏弹出）
adb shell appops set $PKG USE_FULL_SCREEN_INTENT allow

# 4) 悬浮窗（预留：后续"全屏提醒卡片"用）
adb shell appops set $PKG SYSTEM_ALERT_WINDOW allow

# 5) 精确闹钟（snooze 定时唤醒用）
adb shell appops set $PKG SCHEDULE_EXACT_ALARM allow
```

有了 1)+2)+前台服务，App 事实上常驻：灭屏继续轮询（30s 间隔省电）、
开机自启（BootReceiver）、alert 级汇报锁屏弹全屏 + 自动朗读。
