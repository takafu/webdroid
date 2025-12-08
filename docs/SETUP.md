# Setup Guide

## Requirements

- Android 8.0+
- Termux (F-Droid version recommended)
- ~2GB storage
- PC with USB cable (initial setup only)

---

## Step 1: Termux Development Environment

### Install Packages

```bash
pkg update && pkg upgrade -y

pkg install -y \
  openjdk-17 \
  gradle \
  git \
  android-tools \
  jq \
  aapt2
```

### Setup Android SDK

```bash
mkdir -p ~/android-sdk
cd ~/android-sdk

wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip

mkdir -p cmdline-tools/latest
mv cmdline-tools/{bin,lib,source.properties,NOTICE.txt} cmdline-tools/latest/

cat >> ~/.bashrc << 'EOF'
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
EOF

source ~/.bashrc

echo "y" | sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

---

## Step 2: Wireless ADB Setup

### On Android Device

1. Enable Developer Options (tap Build Number 7 times)
2. Enable USB Debugging
3. Enable Wireless Debugging

### On PC

Connect device via USB:

```bash
adb devices
adb tcpip 5555
```

Disconnect USB cable.

### From Termux

```bash
# Replace with your device IP
adb connect 192.168.x.x:5555
adb devices
```

### Disable Phantom Process Killer

```bash
adb shell "settings put global settings_enable_monitor_phantom_procs false"
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"
adb shell cmd appops set com.termux RUN_IN_BACKGROUND allow
adb shell cmd deviceidle whitelist +com.termux
```

---

## Step 3: Project Setup

```bash
cd ~
git clone https://github.com/takafu/android-browser-automation
cd android-browser-automation

cat > local.properties << 'EOF'
sdk.dir=/data/data/com.termux/files/home/android-sdk
EOF

gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Step 4: Test

```bash
adb shell am start -n com.termux.browser/.BrowserActivity

source client/browser.sh
browser_ping
browser_goto "https://example.com"
browser_title
```

---

## Troubleshooting

### AAPT2 Error

Add to `gradle.properties`:
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

### ADB Protocol Fault

Use PC for initial setup (USB + `adb tcpip 5555`).

### Can't Connect to HTTP Server

```bash
adb shell am start -n com.termux.browser/.BrowserActivity
sleep 3
curl http://localhost:8765/ping
```
