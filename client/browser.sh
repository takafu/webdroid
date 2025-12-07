#!/data/data/com.termux/files/usr/bin/bash

# Browser Automation Client Library
# Termuxから Android Browser を制御するためのヘルパー関数

BASE_URL="http://localhost:8765"

# ページに移動
browser_goto() {
    local url="$1"
    curl -s -X POST "$BASE_URL/navigate" \
        -H "Content-Type: application/json" \
        -d "{\"url\":\"$url\"}" | jq -r '.message'
}

# JavaScriptを実行（結果なし）
browser_execute() {
    local script="$1"
    curl -s -X POST "$BASE_URL/execute" \
        -H "Content-Type: application/json" \
        -d "{\"script\":$(echo "$script" | jq -R .)}" | jq -r '.message'
}

# JavaScriptを実行（結果を取得）
browser_eval() {
    local script="$1"
    curl -s -X POST "$BASE_URL/eval" \
        -H "Content-Type: application/json" \
        -d "{\"script\":$(echo "$script" | jq -R .)}" | jq -r '.result'
}

# 現在のURLを取得
browser_url() {
    curl -s "$BASE_URL/url" | jq -r '.url'
}

# ページタイトルを取得
browser_title() {
    curl -s "$BASE_URL/title" | jq -r '.title'
}

# HTML全体を取得
browser_html() {
    curl -s "$BASE_URL/html" | jq -r '.html'
}

# スクリーンショットを取得（Base64）
browser_screenshot() {
    local output="${1:-screenshot.png}"
    curl -s "$BASE_URL/screenshot" | jq -r '.screenshot' | base64 -d > "$output"
    echo "Screenshot saved to $output"
}

# 戻る
browser_back() {
    curl -s -X POST "$BASE_URL/back" | jq -r '.message'
}

# 進む
browser_forward() {
    curl -s -X POST "$BASE_URL/forward" | jq -r '.message'
}

# リロード
browser_refresh() {
    curl -s -X POST "$BASE_URL/refresh" | jq -r '.message'
}

# サーバー接続確認
browser_ping() {
    curl -s "$BASE_URL/ping" | jq -r '.status'
}

# フローティングバブルを開始
browser_bubble_start() {
    curl -s -X POST "$BASE_URL/bubble/start" | jq -r '.message'
}

# フローティングバブルを停止
browser_bubble_stop() {
    curl -s -X POST "$BASE_URL/bubble/stop" | jq -r '.message'
}

# ヘルプ表示
browser_help() {
    cat << 'EOF'
Browser Automation Client - 使い方

基本操作:
  browser_goto <url>          指定URLに移動
  browser_back                戻る
  browser_forward             進む
  browser_refresh             リロード

情報取得:
  browser_url                 現在のURLを取得
  browser_title               ページタイトルを取得
  browser_html                HTML全体を取得

JavaScript実行:
  browser_execute <script>    スクリプトを実行（結果なし）
  browser_eval <script>       スクリプトを実行して結果を取得

その他:
  browser_screenshot [file]   スクリーンショットを保存（デフォルト: screenshot.png）
  browser_ping                サーバー接続確認
  browser_bubble_start        フローティングバブルを表示（Termuxと同時表示可能）
  browser_bubble_stop         フローティングバブルを閉じる
  browser_help                このヘルプを表示

使用例:
  browser_goto "https://example.com"
  browser_title
  browser_eval "document.querySelector('h1').textContent"
  browser_screenshot my-screenshot.png
EOF
}
