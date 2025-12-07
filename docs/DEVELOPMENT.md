# 開発者ガイド

Android Browser Automationの開発方法を説明します。

## プロジェクト構造

```
android-browser-automation/
├── app/
│   ├── build.gradle.kts           # アプリのビルド設定
│   └── src/main/
│       ├── AndroidManifest.xml    # アプリマニフェスト
│       └── java/com/termux/browser/
│           ├── BrowserActivity.kt        # WebView表示
│           └── AutomationService.kt      # HTTP Server
├── client/
│   └── browser.sh                 # Termux用クライアントライブラリ
├── docs/                          # ドキュメント
├── gradle.properties              # Gradle設定
├── settings.gradle.kts            # プロジェクト設定
├── local.properties               # ローカル設定（SDK パス）
└── auto-dev.sh                    # ビルド&インストールスクリプト
```

---

## 主要コンポーネント

### BrowserActivity.kt

WebViewを表示するメインアクティビティ。

**主な責務:**
- WebViewの初期化と設定
- デスクトップUserAgentの設定
- ページイベントのハンドリング
- スクリーンショット機能

**重要な設定:**
```kotlin
settings.apply {
    javaScriptEnabled = true
    domStorageEnabled = true

    // デスクトップモード
    useWideViewPort = true
    loadWithOverviewMode = true

    // デスクトップUserAgent
    userAgentString = "Mozilla/5.0 (X11; Linux x86_64) ..."

    // Mixed Content許可
    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
}
```

### AutomationService.kt

バックグラウンドでHTTPサーバーを実行するサービス。

**主な責務:**
- NanoHTTPDサーバーの起動・停止
- HTTPリクエストのルーティング
- WebViewへのスレッドセーフなアクセス
- フォアグラウンドサービスとして動作

**ポイント:**
- ポート: 8765（固定）
- フォアグラウンドサービスタイプ: `specialUse`
- 通知チャンネル: `browser_automation`

---

## 開発フロー

### 1. コード編集

```bash
cd ~/android-browser-automation

# Kotlinファイルを編集（Claude Code推奨）
# app/src/main/java/com/termux/browser/BrowserActivity.kt
# app/src/main/java/com/termux/browser/AutomationService.kt
```

### 2. ビルド＆インストール

```bash
# 自動化スクリプト使用
./auto-dev.sh
```

これで以下が自動実行されます:
1. Gradleビルド
2. ADB経由でインストール
3. アプリ自動起動

### 3. テスト

```bash
# クライアントライブラリでテスト
source client/browser.sh
browser_ping
browser_goto "https://example.com"
```

### 4. ログ確認

```bash
# リアルタイムログ
adb logcat -s AutomationService:D BrowserActivity:D

# エラーのみ
adb logcat -s AndroidRuntime:E

# ページイベント
adb logcat -s AutomationService:D | grep "page_"
```

---

## 新機能の追加

### API エンドポイントの追加

#### 1. AutomationService.kt の serve() にルートを追加

```kotlin
override fun serve(session: IHTTPSession): Response {
    return when {
        uri == "/your-endpoint" && method == Method.POST -> handleYourEndpoint(session)
        // ... 既存のルート
    }
}
```

#### 2. ハンドラー関数を実装

```kotlin
private fun handleYourEndpoint(session: IHTTPSession): Response {
    val params = parseBody(session)
    val param = params["param"] as? String

    return if (param != null) {
        // WebViewを操作する場合はメインスレッドで実行
        runOnMainThread {
            BrowserActivity.webView?.let { webView ->
                // 処理
            }
        }
        successResponse("Success")
    } else {
        errorResponse("Missing parameter")
    }
}
```

#### 3. クライアント関数を追加

`client/browser.sh` に関数を追加:

```bash
browser_your_function() {
    local param="$1"
    curl -s -X POST "$BASE_URL/your-endpoint" \
        -H "Content-Type: application/json" \
        -d "{\"param\":\"$param\"}" | jq -r '.message'
}
```

---

## WebView操作のベストプラクティス

### スレッドセーフなアクセス

WebViewは**必ずメインスレッドから操作**してください：

```kotlin
// ✅ 正しい
runOnMainThread {
    BrowserActivity.webView?.loadUrl("https://example.com")
}

// ❌ 間違い - クラッシュする
BrowserActivity.webView?.loadUrl("https://example.com")
```

### 非同期処理の結果待ち

結果を返す必要がある場合:

```kotlin
private fun handleGetSomething(): Response {
    var result: String? = null
    val lock = Object()

    runOnMainThread {
        synchronized(lock) {
            result = BrowserActivity.webView?.something
            lock.notify()
        }
    }

    synchronized(lock) {
        try {
            lock.wait(1000) // タイムアウト1秒
        } catch (e: InterruptedException) {
            return errorResponse("Timeout")
        }
    }

    return successResponse(result ?: "default", "key" to result)
}
```

---

## ビルド設定

### gradle.properties

```properties
# Termux専用設定
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2

# パフォーマンス最適化
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true

# メモリ設定
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

### build.gradle.kts

依存関係:
```kotlin
dependencies {
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.gson:gson:2.10.1")
}
```

---

## デバッグ

### Chrome DevTools を使用

WebViewのデバッグを有効化:

```kotlin
// BrowserActivity.kt の onCreate() に追加
WebView.setWebContentsDebuggingEnabled(true)
```

その後:
1. PCのChromeで `chrome://inspect` を開く
2. デバイスを選択
3. WebViewをインスペクト

### ログレベルの調整

```kotlin
// AutomationService.kt
companion object {
    private const val TAG = "AutomationService"
    private const val DEBUG = true  // デバッグモード

    fun log(message: String) {
        if (DEBUG) {
            Log.d(TAG, message)
        }
    }
}
```

---

## テスト

### 基本機能テスト

```bash
#!/data/data/com.termux/files/usr/bin/bash
# test.sh

source client/browser.sh

echo "Testing browser automation..."

# Ping test
assert_equals "$(browser_ping)" "ok" "Ping test"

# Navigation test
browser_goto "https://example.com"
sleep 3
assert_contains "$(browser_title)" "Example" "Title test"

echo "All tests passed!"
```

### 継続的インテグレーション

```bash
# 自動テストスクリプト
./auto-dev.sh && ./test.sh
```

---

## パフォーマンス最適化

### ビルド時間の短縮

```bash
# Configuration Cacheを有効化
gradle --configuration-cache assembleDebug
```

### メモリ使用量の削減

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx1536m  # 2048m → 1536m
```

### 不要な依存関係の削除

最小限の依存関係で開発:
```kotlin
dependencies {
    // androidx.webkit は不要な場合削除可能
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.gson:gson:2.10.1")
}
```

---

## リリースビルド

### 署名鍵の生成

```bash
keytool -genkey -v \
  -keystore ~/browser-automation.keystore \
  -alias browser \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### build.gradle.kts に署名設定追加

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("/data/data/com.termux/files/home/browser-automation.keystore")
            storePassword = "your-password"
            keyAlias = "browser"
            keyPassword = "your-password"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
}
```

### リリースビルド実行

```bash
gradle assembleRelease
```

---

## 貢献ガイドライン

1. **Issue作成** - バグ報告や機能リクエスト
2. **Fork** - リポジトリをフォーク
3. **ブランチ作成** - `feature/your-feature` or `fix/your-fix`
4. **開発** - コード変更
5. **テスト** - 動作確認
6. **Pull Request** - 詳細な説明と共に提出

---

## FAQ

### Q: なぜNanoHTTPDを使うのか？

A: 軽量で依存関係が少なく、Gradleで簡単に導入できるため。

### Q: Playwrightとの違いは？

A: PlaywrightはChrome等のフルブラウザを制御しますが、このプロジェクトはWebViewベース。軽量で、Androidネイティブ統合が容易です。

### Q: ヘッドレスモードは可能？

A: WebViewは画面表示が必須です。完全なヘッドレスは現時点では未対応。

### Q: 複数ブラウザインスタンスは？

A: 現在は単一WebViewのみ。複数タブは今後の実装予定。

---

## 参考資料

- [WebView Documentation](https://developer.android.com/reference/android/webkit/WebView)
- [NanoHTTPD GitHub](https://github.com/NanoHttpd/nanohttpd)
- [Android Foreground Services](https://developer.android.com/develop/background-work/services/foreground-services)
- [Termux Wiki](https://wiki.termux.com/)
