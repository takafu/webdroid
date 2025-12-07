# セットアップガイド

Android Browser Automationの環境構築手順を説明します。

## 前提条件

### 必須

- **Android**: 11以上
- **Termux**: 最新版（F-Droid版推奨）
- **ストレージ**: 約2GB以上の空き容量
- **RAM**: 4GB以上推奨

### 初回のみ必要

- **PC**: Mac/Windows/Linux（ワイヤレスADBセットアップ用）
- **USBケーブル**: デバイスとPCを接続

---

## ステップ1: Termux Android開発環境の構築

### 1-1. 基本パッケージのインストール

```bash
# パッケージ更新
pkg update && pkg upgrade -y

# 必要なパッケージをインストール
pkg install -y \
  openjdk-17 \
  openjdk-21 \
  kotlin \
  gradle \
  git \
  wget \
  unzip \
  aapt \
  aapt2 \
  dx \
  ecj \
  android-tools \
  jq \
  coreutils
```

**所要時間**: 約5-10分
**ディスク使用量**: 約1GB

### 1-2. Android SDK のセットアップ

```bash
# SDKディレクトリ作成
mkdir -p ~/android-sdk
cd ~/android-sdk

# Command Line Tools ダウンロード
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip commandlinetools-linux-11076708_latest.zip

# ディレクトリ構造整理
mkdir -p cmdline-tools/latest
mv cmdline-tools/{bin,lib,source.properties,NOTICE.txt} cmdline-tools/latest/

# 環境変数設定
cat >> ~/.bashrc << 'EOF'

# Android SDK
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
EOF

source ~/.bashrc

# SDK パッケージインストール
echo "y" | ~/android-sdk/cmdline-tools/latest/bin/sdkmanager \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0"
```

**所要時間**: 約3-5分
**ディスク使用量**: 約500MB

### 1-3. 動作確認

```bash
# Java確認
java -version
# openjdk version "17.0.17"

# Kotlin確認
kotlinc -version
# info: kotlinc-jvm 2.2.21

# Gradle確認
gradle --version
# Gradle 9.2.0

# Android SDK確認
ls ~/android-sdk/platforms/android-34/android.jar
# android.jar が存在すればOK
```

---

## ステップ2: ワイヤレスADB のセットアップ

### 2-1. Androidデバイス側の設定

1. **開発者向けオプションを有効化**（まだの場合）
   - 設定 → デバイス情報 → ビルド番号を7回タップ

2. **USBデバッグとワイヤレスデバッグを有効化**
   - 設定 → システム → 開発者向けオプション
   - 「USBデバッグ」をON
   - 「ワイヤレスデバッグ」をON

### 2-2. PC側の準備

**Mac:**
```bash
brew install android-platform-tools
```

**Windows:**
1. [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)をダウンロード
2. 展開してPATHに追加

**Linux:**
```bash
sudo apt install adb
```

### 2-3. USB経由でワイヤレスモードに切り替え

1. デバイスをUSBでPCに接続
2. PCで以下を実行:

```bash
# デバイス認識確認
adb devices
# List of devices attached
# XXXXXXXXXX    device

# ワイヤレスモードに切り替え
adb tcpip 5555
# restarting in TCP mode port: 5555
```

3. **USBケーブルを抜く**

### 2-4. Termuxから接続

1. デバイスのIPアドレスを確認
   - 設定 → Wi-Fi → 接続中のネットワークをタップ
   - IPアドレスをメモ（例: 192.168.1.100）

2. Termuxで接続:

```bash
# IPアドレスを置き換えてください
adb connect 192.168.1.100:5555
```

3. 初回は認証ダイアログが表示されるので「許可」をタップ

4. 接続確認:

```bash
adb devices
# List of devices attached
# 192.168.1.100:5555    device
```

### 2-5. ファントムプロセスキラーの無効化

Termuxがバックグラウンドで強制終了されないようにします：

```bash
# ファントムプロセスキラーを無効化
adb shell "settings put global settings_enable_monitor_phantom_procs false"

# 最大プロセス数を増やす
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"

# バックグラウンド実行を許可
adb shell cmd appops set com.termux RUN_IN_BACKGROUND allow

# バッテリー最適化から除外
adb shell cmd deviceidle whitelist +com.termux
```

**⚠️ 注意:** これらの設定はバッテリー消費が増える可能性があります。

---

## ステップ3: このプロジェクトのセットアップ

### 3-1. リポジトリのクローン

```bash
cd ~
git clone https://github.com/takafu/android-browser-automation
cd android-browser-automation
```

### 3-2. local.properties の作成

```bash
cat > local.properties << 'EOF'
sdk.dir=/data/data/com.termux/files/home/android-sdk
EOF
```

### 3-3. ビルド＆インストール

```bash
./auto-dev.sh
```

**期待される出力:**
```
🔨 Building APK...
BUILD SUCCESSFUL in 43s
📱 Installing APK via ADB...
Success
🚀 Launching app...
✅ 完全自動化完了！
```

### 3-4. 動作確認

```bash
# クライアントライブラリを読み込み
source client/browser.sh

# 接続確認
browser_ping
# 出力: ok

# テストページを開く
browser_goto "https://example.com"
sleep 3

# 情報取得
browser_title
# 出力: Example Domain

browser_url
# 出力: https://example.com/
```

---

## トラブルシューティング

### ビルドエラー: AAPT2 エラー

**症状:**
```
AAPT2 aapt2-8.7.3-12006047-linux Daemon: Unexpected error
```

**解決策:**
`gradle.properties` に以下が含まれているか確認:
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

### ADB接続エラー: protocol fault

**症状:**
```
error: protocol fault (couldn't read status message): Success
```

**原因:**
Termux版adbのARM64での既知の問題。

**解決策:**
PC経由でUSB接続してセットアップ（上記手順2-3参照）。

### アプリがインストールできない

**症状:**
```
There was a problem parsing the package
```

**解決策:**
```bash
# 既存アプリをアンインストール
adb uninstall com.termux.browser

# 再インストール
./auto-dev.sh
```

### HTTPサーバーに接続できない

**症状:**
```bash
curl http://localhost:8765/ping
# curl: (7) Failed to connect
```

**解決策:**
```bash
# アプリが起動しているか確認
adb shell pidof com.termux.browser

# 起動していない場合
adb shell am start -n com.termux.browser/.BrowserActivity

# 数秒待ってから再試行
sleep 3
curl http://localhost:8765/ping
```

### Termuxセッションが消える

**症状:**
通知からTermuxに戻るとセッションが終了している。

**解決策:**
ファントムプロセスキラーの無効化（手順2-5参照）。

---

## 接続の永続化

ワイヤレスADB接続は以下の条件で保持されます:

- ✅ デバイス再起動後も有効
- ✅ Termux再起動後も有効
- ✅ Wi-Fi接続を維持している限り有効

接続が切れた場合:
```bash
# 再接続（IPアドレスを確認）
adb connect 192.168.x.x:5555

# 接続確認
adb devices
```

---

## 次のステップ

1. [API.md](API.md) - APIリファレンス
2. [DEVELOPMENT.md](DEVELOPMENT.md) - 開発者向けガイド
3. [TROUBLESHOOTING.md](TROUBLESHOOTING.md) - 詳細なトラブルシューティング

---

## 環境のリセット

完全に最初からやり直したい場合:

```bash
# Gradleキャッシュをクリア
rm -rf ~/.gradle/caches/

# ビルド生成物をクリア
cd ~/android-browser-automation
gradle clean

# アプリをアンインストール
adb uninstall com.termux.browser

# 再ビルド
./auto-dev.sh
```
