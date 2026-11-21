#!/usr/bin/env bash
# Emulator smoke test (CI): install, cold start, click through the main screens, screenshot each, fail on a crash.
set -u
APK="$1"
OUT="${2:-shots}"
mkdir -p "$OUT"

center() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" > /dev/null 2>&1
  python3 - "$OUT/ui.xml" "$1" "$2" <<'PY'
import re, sys
xml, attr, value = sys.argv[1], sys.argv[2], sys.argv[3]
for m in re.finditer(r'<node [^>]*>', open(xml, encoding="utf-8").read()):
    n = m.group(0)
    a = re.search(attr + r'="([^"]*)"', n)
    if a and a.group(1) == value:
        x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n).groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
}

dismiss() {
  local w
  w=$(center text "Wait")
  if [ -n "$w" ]; then adb shell input tap $w; sleep 2; fi
}

tap() {
  local xy
  dismiss
  xy=$(center "$1" "$2")
  if [ -z "$xy" ]; then echo "not found: $1=$2"; return 1; fi
  adb shell input tap $xy
  sleep 3
}

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }

adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher > /dev/null 2>&1 || true
adb install -r "$APK"
adb logcat -c
adb shell am start -W -n app.eventborg/.MainActivity | tee "$OUT/start.txt"
sleep 12
dismiss
shot 01-feed

# Open the first event: tap the card row left of its first star button.
xy=$(center content-desc "Save event")
if [ -n "$xy" ]; then
  adb shell input tap $(( ${xy% *} / 2 )) "${xy#* }"
  sleep 4
  shot 02-detail
  adb shell input keyevent KEYCODE_BACK
  sleep 2
fi

tap content-desc "Filters" && { shot 03-filters; adb shell input keyevent KEYCODE_BACK; sleep 2; }
tap text "Now" && { shot 04-now; tap text "Now"; }
tap text "Saved" && shot 05-saved
tap text "Sources" && shot 06-sources
tap text "Settings" && shot 07-settings
adb shell input keyevent KEYCODE_BACK
sleep 1

adb shell cmd locale set-app-locales app.eventborg --locales da
sleep 3
adb shell am start -W -n app.eventborg/.MainActivity > /dev/null
sleep 5
shot 08-feed-da

adb logcat -d > "$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  exit 1
fi
echo "smoke ok"
