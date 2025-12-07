# Android Browser Automation

Termuxから制御可能なAndroidブラウザ自動化アプリ。WebViewベースで、HTTPサーバー経由でブラウザ操作が可能。

## 特徴

- ✅ **デスクトップモード対応** - Slack等のデスクトップサイトを表示可能
- ✅ **HTTP API** - Termuxから簡単に制御
- ✅ **フォアグラウンドサービス** - バックグラウンドで停止されない
- ✅ **完全自動化** - ビルド→インストール→起動まで全自動
- ✅ **JavaScript実行** - ページ内でスクリプト実行可能
- ✅ **スクリーンショット** - ページのキャプチャ可能

## クイックスタート

### 1. アプリのビルド＆インストール

```bash
cd ~/android-browser-automation
./auto-dev.sh
```

### 2. ブラウザの制御

```bash
# クライアントライブラリを読み込み
source ~/android-browser-automation/client/browser.sh

# ページに移動
browser_goto "https://example.com"

# ページ情報取得
browser_title
browser_url

# JavaScript実行
browser_eval "document.querySelectorAll('a').length"

# スクリーンショット
browser_screenshot screenshot.png
```

## API エンドポイント

HTTPサーバーは`localhost:8765`で動作します。

### ナビゲーション

**POST** `/navigate` - ページに移動
```bash
curl -X POST http://localhost:8765/navigate \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

**POST** `/back` - 戻る
**POST** `/forward` - 進む
**POST** `/refresh` - リロード

### 情報取得

**GET** `/url` - 現在のURL
```bash
curl http://localhost:8765/url
# {"success":true,"message":"https://example.com/","url":"https://example.com/"}
```

**GET** `/title` - ページタイトル
**GET** `/html` - HTML全体
**GET** `/screenshot` - スクリーンショット（Base64）

### JavaScript実行

**POST** `/execute` - スクリプトを実行（結果なし）
```bash
curl -X POST http://localhost:8765/execute \
  -H "Content-Type: application/json" \
  -d '{"script":"console.log(\"Hello\")"}'
```

**POST** `/eval` - スクリプトを実行して結果を取得
```bash
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}'
```

### ヘルスチェック

**GET** `/ping` - サーバー接続確認
```bash
curl http://localhost:8765/ping
# {"success":true,"message":"pong","status":"ok"}
```

## クライアントライブラリ

Bash関数で簡単に操作できます：

```bash
source ~/android-browser-automation/client/browser.sh

# 使用可能な関数
browser_goto <url>          # ページに移動
browser_back                # 戻る
browser_forward             # 進む
browser_refresh             # リロード
browser_url                 # URL取得
browser_title               # タイトル取得
browser_html                # HTML取得
browser_execute <script>    # JavaScript実行
browser_eval <script>       # JavaScript実行＆結果取得
browser_screenshot [file]   # スクリーンショット
browser_ping                # 接続確認
browser_help                # ヘルプ表示
```

## 使用例

### Slackの自動操作

```bash
source ~/android-browser-automation/client/browser.sh

# Slackを開く（デスクトップモード）
browser_goto "https://slack.com"

# ページが読み込まれるまで待つ
sleep 5

# リンク数を確認
browser_eval "document.querySelectorAll('a').length"

# ログインボタンを探す
browser_eval "document.querySelector('a[href*=\"signin\"]')?.textContent"
```

### スクレイピング

```bash
source ~/android-browser-automation/client/browser.sh

# ページに移動
browser_goto "https://news.ycombinator.com"
sleep 3

# トップ記事のタイトルを取得
browser_eval "Array.from(document.querySelectorAll('.titleline > a')).slice(0, 5).map(a => a.textContent)"
```

### スクリーンショット取得

```bash
source ~/android-browser-automation/client/browser.sh

browser_goto "https://example.com"
sleep 2
browser_screenshot ~/storage/downloads/example.png
```

## 環境構築

詳細な環境構築手順は [docs/SETUP.md](docs/SETUP.md) を参照してください。

### 必要な環境

- **Android**: 11以上（ワイヤレスADB用）
- **Termux**: 最新版
- **初回のみ**: PC + USBケーブル（ADBセットアップ用）

### 簡易セットアップ

```bash
# 1. Termux環境構築（初回のみ）
# 詳細は docs/SETUP.md 参照

# 2. このリポジトリをクローン
git clone https://github.com/takafu/android-browser-automation
cd android-browser-automation

# 3. ビルド＆インストール
./auto-dev.sh

# 4. 使用開始
source client/browser.sh
browser_goto "https://example.com"
```

## ドキュメント

- [SETUP.md](docs/SETUP.md) - 完全な環境構築ガイド
- [API.md](docs/API.md) - API リファレンス
- [DEVELOPMENT.md](docs/DEVELOPMENT.md) - 開発者ガイド
- [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) - トラブルシューティング

## アーキテクチャ

```
Termux (localhost:8765)
    ↕ HTTP/JSON
Android App
    ├─ BrowserActivity (WebView)
    └─ AutomationService (HTTP Server)
```

### コンポーネント

- **BrowserActivity**: WebViewを表示するメインアクティビティ
  - デスクトップUserAgent設定
  - JavaScript有効化
  - Mixed Content許可

- **AutomationService**: フォアグラウンドサービス
  - NanoHTTPDベースのHTTPサーバー
  - ポート8765でリスン
  - WebViewへのスレッドセーフなアクセス

- **client/browser.sh**: Termux用クライアントライブラリ
  - Bash関数でAPIを簡単に利用
  - jqでJSON処理

## 制限事項

- WebView実装のため、完全なChrome互換性はない
- 一部のサイトでリソース読み込みエラーが発生する可能性
- ポップアップやダウンロードは未対応（今後実装予定）

## 今後の予定

- [ ] waitForSelector実装
- [ ] クリック・タップ機能
- [ ] テキスト入力機能
- [ ] Cookie管理
- [ ] 複数タブ対応
- [ ] MCP Server化

## 技術スタック

- **言語**: Kotlin
- **ビルド**: Gradle 9.2.0
- **HTTP Server**: NanoHTTPD 2.3.1
- **JSON**: Gson 2.10.1
- **WebView**: AndroidX WebKit 1.8.0

## ライセンス

MIT License

## 関連プロジェクト

- [termux-android-dev](https://github.com/takafu/termux-android-dev) - Termux内でのAndroid開発環境構築

## 貢献

Issue報告やPull Requestを歓迎します！

## 参考リンク

- [Termux](https://termux.dev/)
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd)
- [Android WebView](https://developer.android.com/reference/android/webkit/WebView)
