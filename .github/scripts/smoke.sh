#!/usr/bin/env bash
# Смоук-тест: установка offline APK, запуск и обход экранов через маршруты из уведомлений.
set -u
APK_DIR="$1"
PKG=com.ration.app.debug
ACT="$PKG/com.ration.app.MainActivity"
APK=$(find "$APK_DIR" -name "*offline*debug*.apk" | head -1)
echo "APK: $APK"
adb install -r -g "$APK" || exit 1
adb logcat -c

crashed() {
  adb logcat -d > smoke-logcat.txt
  grep -E "FATAL EXCEPTION|ANR in $PKG" smoke-logcat.txt | grep -q . && return 0
  [ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && return 0
  return 1
}

adb shell am start -W -n "$ACT" || exit 1
sleep 20   # засев базы и построение плана дня
if crashed; then echo "Падение при запуске"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi

for route in today tomorrow shopping preps prep_checklist week health import; do
  echo "== экран $route"
  adb shell am start -W -n "$ACT" --es route "$route" > /dev/null
  sleep 5
  if crashed; then echo "Падение на экране $route"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi
done

# Нижняя навигация: вкладки открываются через маршруты today/… выше; проверяем ещё возврат и повторный запуск
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am force-stop "$PKG"; sleep 1
adb shell am start -W -n "$ACT" > /dev/null; sleep 8
if crashed; then echo "Падение при повторном запуске"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi

adb logcat -d > smoke-logcat.txt
echo "Смоук-тест пройден"
