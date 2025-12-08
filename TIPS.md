# Tips

## Page Loading

### Complex Sites (Slack, etc.)

Reload after initial load for better resource loading:
```bash
browser_goto "https://slack.com"
sleep 3
browser_refresh
sleep 5
```

### Wait for Element

```bash
while true; do
    exists=$(browser_eval "!!document.querySelector('#target')")
    if [ "$exists" = "true" ]; then
        break
    fi
    sleep 1
done
```

---

## Form Interaction

### Input Text

```bash
browser_execute "
const input = document.querySelector('input[name=\"email\"]');
input.value = 'test@example.com';
input.dispatchEvent(new Event('input', { bubbles: true }));
"
```

### Click Button

```bash
browser_execute "document.querySelector('button[type=\"submit\"]').click()"
```

---

## Performance

### Use execute Instead of eval

When you don't need return value:
```bash
# Slower
browser_eval "console.log('test')"

# Faster
browser_execute "console.log('test')"
```

---

## Debugging

### Take Screenshots at Each Step

```bash
browser_goto "https://example.com"
browser_screenshot step1.png

browser_execute "document.querySelector('button').click()"
sleep 2
browser_screenshot step2.png
```

### Watch Logs

```bash
adb logcat -s AutomationService:D | grep "page_"
```

---

## Limitations

- No file upload
- No popup windows
- Google OAuth blocked in WebView
- No downloads
