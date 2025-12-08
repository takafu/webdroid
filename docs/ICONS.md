# Icons

This project uses **Feather Icons**.

## Feather Icons
- **Website**: https://feathericons.com/
- **GitHub**: https://github.com/feathericons/feather
- **License**: MIT License

## Icons in Use

| File | Feather Name | Usage |
|------|--------------|-------|
| ic_globe.xml | globe | Floating bubble |
| ic_lock.xml | lock | Auth button (password manager) |
| ic_minus.xml | minus | Minimize button |
| ic_trash.xml | trash-2 | Trash (stop service) |

## Adding New Icons

1. Find an icon at https://feathericons.com/
2. Get SVG from GitHub:
   ```
   https://raw.githubusercontent.com/feathericons/feather/master/icons/{icon-name}.svg
   ```
3. Convert SVG to Android VectorDrawable:
   - Android Studio: Right-click > New > Vector Asset > Local file
   - Or convert manually (SVG path to android:pathData)

4. Naming convention: `ic_{feather-icon-name}.xml`

## Conversion Reference

Feather Icons SVG attributes:
- viewBox: 0 0 24 24
- stroke-width: 2
- stroke-linecap: round
- stroke-linejoin: round
- fill: none

VectorDrawable equivalents:
- android:viewportWidth="24", android:viewportHeight="24"
- android:strokeWidth="2"
- android:strokeLineCap="round"
- android:strokeLineJoin="round"
- android:fillColor="#00000000"
