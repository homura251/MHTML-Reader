# Validation report

## Parser smoke test

Command:

```bash
./tools/run_parser_smoke_test.sh '/path/to/chrome-snapshot'
```

Validated against the supplied 11 MB Chrome/Blink MHTML snapshot. Result:

```text
title=Free & unlimited Avatar/World ripping! | RipperStore Forums
charset=UTF-8
root=https://forum.ripper.store/topic/112080/free-unlimited-avatar-world-ripping/6
parts=40
aliases=69
containsChinese=true
containsMojibake=false
```

The test explicitly checks that the decoded root HTML contains `你好！看起来您对这段对话很感兴趣` and does not contain the characteristic `ä½` / `ï¼` mojibake fragments.

## Source-level safety checks

- `AndroidManifest.xml` intentionally has no `android.permission.INTERNET`.
- WebView `allowFileAccess=false` and `allowContentAccess=false`.
- JavaScript is disabled by default.
- Resources not contained in the archive are returned as offline/404 for HTTP(S).
- Main-frame `file://`, `content://`, `intent://`, and custom-scheme navigation is blocked.
- Only explicit user action can hand an HTTP(S) link to an external browser.
- Archive reads are bounded to 256 MB.

## Build validation boundary

The parser core was compiled and executed with `kotlinc` in the generation environment. A full Android APK build was not run because this environment does not include an Android SDK or a Gradle installation/wrapper binary. The project pins AGP/Gradle/Compose versions in its build files so Android Studio can resolve and build it in a normal Android development environment.
