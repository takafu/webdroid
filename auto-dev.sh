#!/data/data/com.termux/files/usr/bin/bash

# 完全自動化開発フロー：ビルド→自動インストール→自動起動

set -e

APP_PACKAGE="com.termux.browser"
APP_ACTIVITY="BrowserActivity"

echo "🔨 Building APK..."
gradle assembleDebug

echo "📱 Installing APK via ADB..."
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo "🚀 Launching app..."
adb shell am start -n "$APP_PACKAGE/.$APP_ACTIVITY"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "✅ 完全自動化完了！"
echo "   ビルド → インストール → 起動"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
echo "💡 ログを見るには："
echo "   adb logcat | grep -i '$APP_PACKAGE'"
echo ""
echo "💡 アプリを停止するには："
echo "   adb shell am force-stop $APP_PACKAGE"
