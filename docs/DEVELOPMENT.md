# Development Guide

## Project Structure

```
android-browser-automation/
├── app/src/main/java/com/termux/browser/
│   ├── BrowserActivity.kt        # Entry point, starts services
│   ├── FloatingBubbleService.kt  # Floating UI and WebView
│   ├── AutomationService.kt      # HTTP server
│   └── AuthDialogActivity.kt     # Password manager dialog
├── client/
│   └── browser.sh                # Bash client library
└── docs/
```

---

## Components

### FloatingBubbleService

- Floating bubble overlay
- Resizable browser window
- WebView with desktop user agent
- Screenshot capture

### AutomationService

- NanoHTTPD server on port 8765
- Routes HTTP requests to WebView
- Foreground service (won't be killed)

### AuthDialogActivity

- Native dialog for password manager
- Works with Bitwarden via autofill

---

## Development Workflow

```bash
# Edit code
# Build and install
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Restart app
adb shell am force-stop com.termux.browser
adb shell am start -n com.termux.browser/.BrowserActivity

# Test
source client/browser.sh
browser_ping
```

---

## Adding API Endpoints

### 1. Add route in AutomationService.kt

```kotlin
override fun serve(session: IHTTPSession): Response {
    return when {
        uri == "/your-endpoint" && method == Method.POST -> handleYourEndpoint(session)
        // ...
    }
}
```

### 2. Implement handler

```kotlin
private fun handleYourEndpoint(session: IHTTPSession): Response {
    val params = parseBody(session)
    // WebView operations must run on main thread
    runOnMainThread {
        BrowserActivity.webView?.doSomething()
    }
    return successResponse("Done")
}
```

### 3. Add client function

```bash
# In client/browser.sh
browser_your_function() {
    curl -s -X POST "$BASE_URL/your-endpoint" \
        -H "Content-Type: application/json" \
        -d "{}" | jq -r '.message'
}
```

---

## Debugging

### View logs

```bash
adb logcat -s AutomationService:D FloatingBubbleService:D
```

### Chrome DevTools

WebView debugging is enabled. On PC Chrome, open `chrome://inspect`.
