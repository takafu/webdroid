# API リファレンス

Android Browser Automation の完全なAPIリファレンスです。

## 基本情報

- **ベースURL**: `http://localhost:8765`
- **プロトコル**: HTTP/1.1
- **レスポンス形式**: JSON
- **リクエスト形式**: JSON（POSTメソッドの場合）

## 共通レスポンス形式

### 成功時

```json
{
  "success": true,
  "message": "操作の説明",
  "データキー": "データ値"
}
```

### エラー時

```json
{
  "success": false,
  "error": "エラーメッセージ"
}
```

---

## ナビゲーション API

### POST /navigate

指定したURLにページを移動します。

**リクエスト:**
```json
{
  "url": "https://example.com"
}
```

**レスポンス:**
```json
{
  "success": true,
  "message": "Navigating to https://example.com"
}
```

**使用例:**
```bash
curl -X POST http://localhost:8765/navigate \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'
```

---

### POST /back

履歴を戻ります。

**レスポンス:**
```json
{
  "success": true,
  "message": "Navigated back"
}
```

**使用例:**
```bash
curl -X POST http://localhost:8765/back
```

---

### POST /forward

履歴を進みます。

**レスポンス:**
```json
{
  "success": true,
  "message": "Navigated forward"
}
```

---

### POST /refresh

ページをリロードします。

**レスポンス:**
```json
{
  "success": true,
  "message": "Page refreshed"
}
```

---

## 情報取得 API

### GET /url

現在表示中のページのURLを取得します。

**レスポンス:**
```json
{
  "success": true,
  "message": "https://example.com/",
  "url": "https://example.com/"
}
```

**使用例:**
```bash
curl http://localhost:8765/url
```

---

### GET /title

現在表示中のページのタイトルを取得します。

**レスポンス:**
```json
{
  "success": true,
  "message": "Example Domain",
  "title": "Example Domain"
}
```

**使用例:**
```bash
curl http://localhost:8765/title | jq -r '.title'
```

---

### GET /html

ページのHTML全体を取得します。

**レスポンス:**
```json
{
  "success": true,
  "message": "HTML retrieved",
  "html": "\"<!DOCTYPE html><html>...</html>\""
}
```

**注意:** HTMLはJSON文字列としてエスケープされています。

**使用例:**
```bash
curl http://localhost:8765/html | jq -r '.html'
```

---

### GET /screenshot

ページのスクリーンショットをBase64エンコードで取得します。

**レスポンス:**
```json
{
  "success": true,
  "message": "Screenshot captured",
  "screenshot": "iVBORw0KGgoAAAANSUhEUgAA..."
}
```

**使用例:**
```bash
# PNGファイルとして保存
curl -s http://localhost:8765/screenshot | jq -r '.screenshot' | base64 -d > screenshot.png
```

---

## JavaScript実行 API

### POST /execute

JavaScriptを実行します（戻り値なし、高速）。

**リクエスト:**
```json
{
  "script": "console.log('Hello')"
}
```

**レスポンス:**
```json
{
  "success": true,
  "message": "Script executed"
}
```

**使用例:**
```bash
curl -X POST http://localhost:8765/execute \
  -H "Content-Type: application/json" \
  -d '{"script":"document.body.style.backgroundColor = \"yellow\""}'
```

---

### POST /eval

JavaScriptを実行して結果を取得します（最大5秒待機）。

**リクエスト:**
```json
{
  "script": "document.title"
}
```

**レスポンス:**
```json
{
  "success": true,
  "message": "\"Example Domain\"",
  "result": "\"Example Domain\""
}
```

**注意:**
- 結果はJSON文字列としてエスケープされます
- タイムアウト: 5秒

**使用例:**
```bash
# タイトルを取得
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}' | jq -r '.result'

# リンク数を取得
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.querySelectorAll(\"a\").length"}' | jq -r '.result'

# 配列を取得
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"Array.from(document.querySelectorAll(\"h1\")).map(h => h.textContent)"}' | jq -r '.result'
```

---

## ユーティリティ API

### GET /ping

サーバーの接続確認用。

**レスポンス:**
```json
{
  "success": true,
  "message": "pong",
  "status": "ok"
}
```

**使用例:**
```bash
# 接続確認
curl -s http://localhost:8765/ping | jq -r '.status'
# 出力: ok
```

---

## エラーハンドリング

### タイムアウト

`/eval`、`/screenshot`、`/html`、`/url`、`/title` は内部で最大1-5秒待機します。

タイムアウト時のレスポンス:
```json
{
  "success": false,
  "error": "Timeout"
}
```

### 不正なリクエスト

必須パラメータが欠けている場合:
```json
{
  "success": false,
  "error": "Missing 'url' parameter"
}
```

### 存在しないエンドポイント

```json
{
  "success": false,
  "error": "Not found"
}
```

---

## Bash クライアントライブラリ

### 関数リファレンス

#### browser_goto <url>

指定したURLに移動します。

```bash
browser_goto "https://example.com"
# 出力: Navigating to https://example.com
```

---

#### browser_url

現在のURLを取得します。

```bash
url=$(browser_url)
echo $url
# 出力: https://example.com/
```

---

#### browser_title

ページタイトルを取得します。

```bash
title=$(browser_title)
echo $title
# 出力: Example Domain
```

---

#### browser_html

HTML全体を取得します（JSON エスケープされた文字列）。

```bash
html=$(browser_html)
echo $html | head -c 100
```

---

#### browser_eval <script>

JavaScriptを実行して結果を取得します。

```bash
# 単純な値
result=$(browser_eval "2 + 2")
echo $result  # 4

# DOM操作
title=$(browser_eval "document.title")
echo $title

# 配列
links=$(browser_eval "document.querySelectorAll('a').length")
echo $links
```

---

#### browser_execute <script>

JavaScriptを実行します（結果を取得しない、高速）。

```bash
browser_execute "console.log('Hello')"
# 出力: Script executed
```

---

#### browser_screenshot [filename]

スクリーンショットをPNGファイルとして保存します。

```bash
browser_screenshot screenshot.png
# 出力: Screenshot saved to screenshot.png

# デフォルトファイル名
browser_screenshot
# 出力: Screenshot saved to screenshot.png
```

---

#### browser_back / browser_forward / browser_refresh

ブラウザの基本操作。

```bash
browser_back
browser_forward
browser_refresh
```

---

#### browser_ping

サーバー接続確認。

```bash
status=$(browser_ping)
echo $status  # ok
```

---

#### browser_help

ヘルプを表示します。

```bash
browser_help
```

---

## 高度な使用例

### ページ読み込み完了を待つ

```bash
browser_goto "https://example.com"

# タイトルが取得できるまで待機
while true; do
    title=$(browser_title)
    if [ -n "$title" ] && [ "$title" != "about:blank" ]; then
        echo "Page loaded: $title"
        break
    fi
    sleep 1
done
```

### データスクレイピング

```bash
source ~/android-browser-automation/client/browser.sh

# Hacker Newsのトップ記事を取得
browser_goto "https://news.ycombinator.com"
sleep 3

# 記事タイトルを抽出
browser_eval "
  Array.from(document.querySelectorAll('.titleline > a'))
    .slice(0, 10)
    .map(a => ({
      title: a.textContent,
      url: a.href
    }))
" | jq
```

### フォーム操作

```bash
# 検索ボックスに入力
browser_execute "document.querySelector('input[name=\"q\"]').value = 'test'"

# フォーム送信
browser_execute "document.querySelector('form').submit()"
```

### スクロール

```bash
# 下にスクロール
browser_execute "window.scrollTo(0, document.body.scrollHeight)"

# 上にスクロール
browser_execute "window.scrollTo(0, 0)"
```

---

## トラブルシューティング

### サーバーに接続できない

```bash
# アプリが起動しているか確認
adb shell pidof com.termux.browser

# 起動していない場合
adb shell am start -n com.termux.browser/.BrowserActivity

# サーバー起動確認
curl -s http://localhost:8765/ping
```

### ページが読み込まれない

```bash
# ログを確認
adb logcat -s AutomationService:D | grep "page_"

# エラーを確認
adb logcat -s chromium:E
```

### JavaScript実行結果が空

```bash
# ページが完全に読み込まれるまで待つ
browser_goto "https://example.com"
sleep 5  # 十分な待機時間

# その後実行
browser_eval "document.title"
```

詳細は [TROUBLESHOOTING.md](TROUBLESHOOTING.md) を参照してください。
