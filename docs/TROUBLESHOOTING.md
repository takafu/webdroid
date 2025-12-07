# トラブルシューティングガイド

Android Browser Automationでよくある問題と解決策。

## 目次

- [ビルドエラー](#ビルドエラー)
- [ADB接続問題](#adb接続問題)
- [HTTPサーバー問題](#httpサーバー問題)
- [ページ読み込み問題](#ページ読み込み問題)
- [Termuxセッション問題](#termuxセッション問題)

---

## ビルドエラー

### ❌ AAPT2 Daemon エラー

**症状:**
```
AAPT2 aapt2-8.7.3-12006047-linux Daemon #0: Unexpected error
Syntax error: "(" unexpected
```

**原因:**
GradleがダウンロードしたAApt2がx86_64バイナリで、ARM64で動作しない。

**解決策:**

`gradle.properties` に以下を追加:
```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

---

### ❌ MissingForegroundServiceTypeException

**症状:**
```
android.app.MissingForegroundServiceTypeException: Starting FGS without a type
```

**原因:**
Android 14以降では、フォアグラウンドサービスにタイプが必須。

**解決策:**

`AndroidManifest.xml` で設定済みか確認:
```xml
<service
    android:name=".AutomationService"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Browser automation HTTP server" />
</service>
```

権限も必要:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
```

---

## ADB接続問題

### ❌ error: protocol fault (couldn't read status message): Success

**症状:**
```bash
adb pair 192.168.1.100:12345 123456
error: protocol fault (couldn't read status message): Success
```

**原因:**
Termux版adbのARM64アーキテクチャにおける既知のバグ。

**解決策:**
PC経由で初回セットアップが必須:

```bash
# 1. PCにUSB接続
# 2. PCで実行
adb tcpip 5555

# 3. USB切断
# 4. Termuxから接続
adb connect 192.168.x.x:5555
```

参考: [GitHub Issue #24984](https://github.com/termux/termux-packages/issues/24984)

---

### ❌ failed to authenticate

**症状:**
```bash
adb connect 192.168.1.100:5555
failed to authenticate to 192.168.1.100:5555
```

**解決策:**

1. デバイスに認証ダイアログが表示されているか確認
2. 「このコンピュータからのUSBデバッグを常に許可する」をチェック
3. 「許可」をタップ
4. 再接続:
```bash
adb connect 192.168.x.x:5555
```

---

### ❌ adb: no devices/emulators found

**症状:**
```bash
adb devices
List of devices attached
# 空っぽ
```

**解決策:**

```bash
# 1. adbサーバーを再起動
adb kill-server
adb start-server

# 2. 再接続
adb connect 192.168.x.x:5555

# 3. 確認
adb devices
```

---

## HTTPサーバー問題

### ❌ curl: (7) Failed to connect to localhost

**症状:**
```bash
curl http://localhost:8765/ping
curl: (7) Failed to connect to localhost port 8765
```

**原因:**
アプリが起動していないか、サービスがクラッシュしている。

**解決策:**

```bash
# 1. アプリのプロセスを確認
adb shell pidof com.termux.browser

# 2. プロセスがない場合、起動
adb shell am start -n com.termux.browser/.BrowserActivity

# 3. 数秒待つ
sleep 3

# 4. 再試行
curl http://localhost:8765/ping
```

---

### ❌ curl: (52) Empty reply from server

**症状:**
リクエスト送信後にサーバーがクラッシュ。

**原因:**
- スレッド安全性の問題
- タイムアウト処理のバグ

**解決策:**

```bash
# 1. ログを確認
adb logcat -d -s AndroidRuntime:E

# 2. アプリを再起動
adb shell am force-stop com.termux.browser
adb shell am start -n com.termux.browser/.BrowserActivity

# 3. 最新版に更新
cd ~/android-browser-automation
git pull
./auto-dev.sh
```

---

## ページ読み込み問題

### ❌ Slackページが真っ白

**症状:**
Slackにアクセスすると白い画面になる。ログに大量の`net::ERR_FAILED`。

**原因:**
リソース読み込みの失敗、Mixed Content、CORS等。

**解決策:**

1. **ページが部分的に読み込まれているか確認:**
```bash
browser_goto "https://slack.com"
sleep 10  # 長めに待つ
browser_title
```

2. **ログでエラー確認:**
```bash
adb logcat -s chromium:E AutomationService:D | grep -i error
```

3. **別のURLを試す:**
```bash
# Slack ワークスペース直接
browser_goto "https://your-workspace.slack.com"
```

---

### ❌ ページが about:blank に戻る

**症状:**
ページ読み込み中に`about:blank`に戻ってしまう。

**原因:**
重大なJavaScriptエラーやセキュリティポリシー違反。

**解決策:**

```bash
# コンソールログを確認
adb logcat -s AutomationService:D | grep "Console:"

# エラーイベントを確認
adb logcat -s AutomationService:D | grep "error"
```

---

## Termuxセッション問題

### ❌ バックグラウンドで Termux が終了する

**症状:**
通知からTermuxに戻るとセッションが消えている。

**原因:**
Androidのファントムプロセスキラーがバックグラウンドプロセスを終了。

**解決策:**

```bash
# 1. ファントムプロセスキラーを無効化
adb shell "settings put global settings_enable_monitor_phantom_procs false"

# 2. 最大プロセス数を増やす
adb shell "/system/bin/device_config put activity_manager max_phantom_processes 2147483647"

# 3. バックグラウンド実行を許可
adb shell cmd appops set com.termux RUN_IN_BACKGROUND allow

# 4. バッテリー最適化から除外
adb shell cmd deviceidle whitelist +com.termux
```

**確認:**
```bash
adb shell settings get global settings_enable_monitor_phantom_procs
# false であればOK
```

参考: [GitHub Gist](https://gist.github.com/kairusds/1d4e32d3cf0d6ca44dc126c1a383a48d)

---

### ❌ Claude Code セッションがタイムアウト

**症状:**
長時間放置するとセッションが切れる。

**対策:**

CLAUDE.md の通知フックが設定されていることを確認:
```json
"Notification": [{
  "matcher": "idle_prompt",
  "hooks": [{
    "type": "command",
    "command": "termux-notification ..."
  }]
}]
```

---

## JavaScript実行問題

### ❌ browser_eval の結果が空

**症状:**
```bash
browser_eval "document.title"
# 何も返ってこない
```

**原因:**
- ページがまだ読み込まれていない
- JavaScriptがエラー
- タイムアウト

**解決策:**

```bash
# 1. 十分に待つ
browser_goto "https://example.com"
sleep 5  # 長めに待機

# 2. ページ読み込み完了を確認
browser_url  # URLが正しいか
browser_title  # タイトルが取得できるか

# 3. その後JavaScript実行
browser_eval "document.title"
```

---

### ❌ JSON parse エラー

**症状:**
```bash
browser_eval でシンタックスエラー
```

**原因:**
JavaScriptコード内のクォートがJSONとして正しくエスケープされていない。

**解決策:**

```bash
# ✅ 正しい
browser_eval "document.querySelector('h1').textContent"

# ✅ ダブルクォートをエスケープ
browser_eval "document.querySelector(\"h1\").textContent"

# ❌ 間違い - シェルのクォート処理に注意
browser_eval 'document.querySelector("h1").textContent'
```

---

## パフォーマンス問題

### 🐌 ビルドが遅い（1分以上）

**解決策:**

```bash
# 1. Gradleキャッシュを有効化（既定で有効）
# gradle.properties を確認
grep "caching" gradle.properties

# 2. Configuration Cacheを使用
gradle --configuration-cache assembleDebug

# 3. 不要な依存関係を削除
# build.gradle.kts の dependencies を最小限に
```

---

### 🐌 ページ読み込みが遅い

**症状:**
Slackなど大きなサイトの読み込みに10秒以上かかる。

**これは正常です:**
WebViewはフルブラウザより遅いことがあります。

**対策:**
```bash
# 十分な待機時間を確保
browser_goto "https://slack.com"
sleep 10  # 大きなサイトは長めに
```

---

## メモリ問題

### 💾 OutOfMemoryError

**症状:**
```
java.lang.OutOfMemoryError: Failed to allocate
```

**解決策:**

```bash
# 1. Gradleのメモリ設定を下げる
# gradle.properties
org.gradle.jvmargs=-Xmx1536m  # 2048m → 1536m

# 2. 他のアプリを終了
adb shell am kill-all

# 3. Termuxを再起動
exit
# Termuxアプリを完全終了して再起動
```

---

## デバッグのヒント

### ログの効果的な確認

```bash
# リアルタイムでページイベントを監視
adb logcat -s AutomationService:D | grep "page_"

# エラーのみ表示
adb logcat -s AndroidRuntime:E chromium:E

# 特定の文字列をフィルタ
adb logcat | grep -i "slack"
```

### ネットワークエラーの確認

```bash
# chromiumのネットワークログ
adb logcat -s chromium:I | grep -E "ERR_|Failed"
```

### WebView のデバッグ

BrowserActivity.kt に追加:
```kotlin
WebView.setWebContentsDebuggingEnabled(true)
```

再ビルド後、PCのChromeで `chrome://inspect` を開いてデバッグ可能。

---

## よくある質問

### Q: なぜ `/title` がタイムアウトする？

A: ページが完全に読み込まれる前にリクエストしている可能性があります。`/navigate`の後、十分な待機時間（3-5秒）を取ってください。

### Q: なぜSlackが表示できないことがある？

A: Slackは多くの外部リソースを読み込むため、一部がブロックされることがあります。ログで`net::ERR_FAILED`が大量に出ている場合、ページの重要な部分が読み込めていません。

### Q: HTTPサーバーが停止する

A: Android 12以降、バックグラウンドサービスは自動停止されます。フォアグラウンドサービスとして実装済みですが、通知が表示されているか確認してください。通知がない場合、アプリを再起動してください。

### Q: adb connect が失敗する

A: Wi-Fiネットワークが変わった、デバイスが再起動した、などの理由で接続が切れます。デバイスのIPアドレスを確認し、再接続してください。IPアドレスが変わっている可能性があります。

---

## エラーメッセージ一覧

| エラー | 原因 | 解決策 |
|--------|------|--------|
| `protocol fault` | Termux adb のARM64バグ | PC経由でセットアップ |
| `Empty reply from server` | サーバークラッシュ | ログ確認、アプリ再起動 |
| `Failed to connect` | サーバー未起動 | アプリ起動確認 |
| `Timeout` | 処理時間超過 | 待機時間を増やす |
| `Missing parameter` | リクエストパラメータ不足 | JSONを確認 |
| `Not found` | 存在しないエンドポイント | URLを確認 |

---

## それでも解決しない場合

### 1. 完全リセット

```bash
# アプリをアンインストール
adb uninstall com.termux.browser

# Gradleキャッシュをクリア
rm -rf ~/.gradle/caches/

# プロジェクトをクリーン
cd ~/android-browser-automation
gradle clean

# 再ビルド
./auto-dev.sh
```

### 2. ログファイルの保存

```bash
# 詳細ログを保存
adb logcat -d > ~/logcat.txt

# Issueに添付してGitHubに報告
```

### 3. 環境情報の確認

```bash
echo "=== System Info ==="
echo "Android: $(getprop ro.build.version.release)"
echo "Device: $(getprop ro.product.model)"
echo "Kernel: $(uname -r)"
echo ""
echo "=== Termux Info ==="
java -version
kotlinc -version
gradle --version
echo ""
echo "=== ADB Info ==="
adb devices
```

この情報をGitHub Issueに含めてください。

---

## 関連リンク

- [Termux Wiki - Phantom Process Killer](https://wiki.termux.com/wiki/Termux-packages-issues#android-12)
- [Android Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services)
- [WebView Mixed Content](https://developer.android.com/reference/android/webkit/WebSettings#setMixedContentMode(int))
