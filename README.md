# Android Browser Automation

A WebView-based browser automation app controllable from Termux via HTTP API. Features a floating bubble interface for overlay browsing.

## Features

- **Floating Bubble UI** - Draggable overlay window with resize support
- **HTTP API** - Control browser from Termux on localhost:8765
- **Desktop Mode** - Desktop user agent for full-featured web apps
- **JavaScript Execution** - Run scripts and get results
- **Screenshot Capture** - Capture page as PNG
- **Password Manager Support** - Works with Bitwarden via auth dialog

## Quick Start

### 1. Build & Install

```bash
cd ~/android-browser-automation
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. Launch

```bash
adb shell am start -n com.termux.browser/.BrowserActivity
```

### 3. Control from Termux

```bash
source ~/android-browser-automation/client/browser.sh

browser_goto "https://example.com"
browser_title
browser_screenshot screenshot.png
browser_eval "document.querySelectorAll('a').length"
```

## API Endpoints

HTTP server runs on `localhost:8765`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/ping` | Health check |
| GET | `/url` | Get current URL |
| GET | `/title` | Get page title |
| GET | `/html` | Get page HTML |
| GET | `/screenshot` | Get screenshot (Base64) |
| POST | `/navigate` | Navigate to URL |
| POST | `/back` | Go back |
| POST | `/forward` | Go forward |
| POST | `/refresh` | Reload page |
| POST | `/execute` | Run JavaScript |
| POST | `/eval` | Run JavaScript and return result |

### Examples

```bash
# Navigate
curl -X POST http://localhost:8765/navigate \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com"}'

# Get title
curl http://localhost:8765/title

# Execute JavaScript
curl -X POST http://localhost:8765/eval \
  -H "Content-Type: application/json" \
  -d '{"script":"document.title"}'
```

## Client Library

```bash
source ~/android-browser-automation/client/browser.sh

browser_goto <url>          # Navigate to URL
browser_back                # Go back
browser_forward             # Go forward
browser_refresh             # Reload
browser_url                 # Get URL
browser_title               # Get title
browser_html                # Get HTML
browser_execute <script>    # Run JavaScript
browser_eval <script>       # Run JavaScript and return result
browser_screenshot [file]   # Take screenshot
browser_ping                # Health check
```

## Architecture

```
Termux (curl/browser.sh)
    |
    | HTTP (localhost:8765)
    v
AutomationService (NanoHTTPD)
    |
    v
FloatingBubbleService
    |
    v
WebView (floating overlay)
```

## Requirements

- Android 8.0+ (for overlay permissions)
- Termux with ADB access
- Display over other apps permission

## Tech Stack

- Kotlin
- Gradle 9.2.0
- NanoHTTPD 2.3.1
- Gson 2.10.1
- AndroidX WebKit

## License

MIT License
