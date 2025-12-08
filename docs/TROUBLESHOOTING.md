# Troubleshooting

## Build Errors

### AAPT2 Daemon Error

```
AAPT2 aapt2-8.7.3-12006047-linux Daemon: Unexpected error
```

**Fix:** Add to `gradle.properties`:
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

---

## ADB Issues

### Protocol Fault Error

```
error: protocol fault (couldn't read status message): Success
```

**Cause:** Termux adb ARM64 bug.

**Fix:** Use PC for initial setup:
```bash
# On PC with USB connected
adb tcpip 5555
# Disconnect USB
# On Termux
adb connect 192.168.x.x:5555
```

### No Devices Found

```bash
adb kill-server
adb start-server
adb connect 192.168.x.x:5555
```

---

## HTTP Server Issues

### Can't Connect (port 8765)

```bash
# Check if app is running
adb shell pidof com.termux.browser

# Start app if not running
adb shell am start -n com.termux.browser/.BrowserActivity
sleep 3
curl http://localhost:8765/ping
```

### Empty Reply

Server crashed. Check logs and restart:
```bash
adb logcat -d -s AndroidRuntime:E
adb shell am force-stop com.termux.browser
adb shell am start -n com.termux.browser/.BrowserActivity
```

---

## Page Loading Issues

### Page is Blank

1. Wait longer (`sleep 5`)
2. Reload: `browser_refresh`
3. Check logs: `adb logcat -s chromium:E`

### JavaScript Returns Empty

Page not fully loaded. Wait and retry:
```bash
browser_goto "https://example.com"
sleep 5
browser_eval "document.title"
```

---

## Termux Issues

### Session Killed in Background

Run these commands:
```bash
adb shell "settings put global settings_enable_monitor_phantom_procs false"
adb shell cmd appops set com.termux RUN_IN_BACKGROUND allow
adb shell cmd deviceidle whitelist +com.termux
```

---

## Reset Everything

```bash
adb uninstall com.termux.browser
rm -rf ~/.gradle/caches/
cd ~/android-browser-automation
gradle clean
gradle assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
