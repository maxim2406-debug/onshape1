#!/usr/bin/env bash
# Смоук-тест: установка offline APK, запуск и обход экранов через маршруты из уведомлений.
set -u
APK_DIR="$1"
OLD_DIR="${2:-}"
PKG=com.ration.app.debug
ACT="$PKG/com.ration.app.MainActivity"
APK=$(find "$APK_DIR" -name "*offline*debug*.apk" | head -1)
echo "APK: $APK"

crashed() {
  adb logcat -d > smoke-logcat.txt
  grep -E "FATAL EXCEPTION|ANR in $PKG" smoke-logcat.txt | grep -q . && return 0
  [ -z "$(adb shell pidof $PKG | tr -d '\r')" ] && return 0
  return 1
}

# Обновление: прежняя версия создаёт БД v1, новая ставится поверх (adb install -r) и мигрирует её.
if [ -n "$OLD_DIR" ]; then
  OLD=$(find "$OLD_DIR" -name "*offline*debug*.apk" | head -1)
  echo "Прежняя APK: $OLD"
  adb install -g "$OLD" || exit 1
  adb logcat -c
  adb shell am start -W -n "$ACT" || exit 1
  sleep 20
  if crashed; then echo "Падение прежней версии"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi
  for route in today pantry preps; do adb shell am start -W -n "$ACT" --es route "$route" > /dev/null; sleep 3; done
  adb shell am force-stop "$PKG"; sleep 1
  echo "== обновление поверх прежней версии"
fi

adb install -r -g "$APK" || { echo "Новая APK не ставится поверх прежней"; exit 1; }
adb logcat -c
adb shell am start -W -n "$ACT" || exit 1
sleep 20   # миграция и засев базы, построение плана дня
if crashed; then echo "Падение при запуске"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi
if [ -n "$OLD_DIR" ]; then
  VER=$(adb shell dumpsys package "$PKG" | grep -m1 versionName | tr -d '\r ')
  echo "Установлено: $VER"
  # 19.6, 20.8: копия базы v3 перед миграцией в приватной папке приложения
  adb shell run-as "$PKG" ls files/backup | tee backup-ls.txt
  grep -q "pre-migration-3.db" backup-ls.txt || { echo "Нет копии базы перед миграцией"; exit 1; }
fi

for route in today tomorrow shopping preps prep_checklist week health import library cook inventory nutrition workouts form; do
  echo "== экран $route"
  adb shell am start -W -n "$ACT" --es route "$route" > /dev/null
  sleep 5
  if crashed; then echo "Падение на экране $route"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi
done

# Сценарий раздела 19: шесть приёмов, две кнопки, автопропуск, «Изменить», пропуск
UI="python3 .github/scripts/ui.py"
step() {
  sleep 3
  if crashed; then echo "Падение: $1"; grep -A40 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi
}
record_first() {   # в конструкторе: первая позиция списка → Записать → закрыть диалоги
  $UI tap "+ Продукт или блюдо" || exit 1; step "открытие списка продуктов и блюд"
  $UI tap "ккал ·" --contains || exit 1; step "выбор позиции"
  $UI has "Итог:" --contains || exit 1
  $UI tap "Записать" || exit 1; step "запись приёма"
  $UI tap "Не нужно" || true; step "диалог своего блока"
  $UI tap "OK" || true; step "возврат на «Сегодня»"
}
adb shell am start -W -n "$ACT" --es route today > /dev/null; sleep 4
$UI top
for t in "З · Завтрак" "С · Перекус" "О · Обед" "П · Перекус" "У · Ужин" "Е · Перекус"; do
  $UI has "$t" || { echo "нет карточки $t"; exit 1; }
done
$UI hasnot "Тип дня" || exit 1
$UI hasnot "Съел по плану" || exit 1
$UI hasnot "В пути" || exit 1
$UI hasnot "Что приготовить" || exit 1
$UI top
$UI has "Вода +250 мл" || exit 1
$UI has "Свой продукт" || exit 1

$UI tapnear "З · Завтрак" "Собрать из продуктов" || exit 1; step "конструктор для завтрака"
record_first
$UI tapnear "О · Обед" "Собрать из продуктов" || exit 1; step "конструктор для обеда"
record_first
# С пустой между записанными З и О → пропущен автоматически
$UI top
$UI has "пропущен (авто)" || { echo "нет автопропуска"; exit 1; }
$UI tapnear "О · Обед" "Изменить" || exit 1; step "изменить обед"
$UI has "Изменить:" --contains || exit 1
$UI tap "Записать" || exit 1; step "сохранение изменённого обеда"
$UI tap "OK" || true; step "возврат после изменения"
$UI tapnear "П · Перекус" "Пропустить" || exit 1; step "пропуск перекуса"
$UI has "Отменить пропуск" || exit 1
$UI tapnear "П · Перекус" "Отменить пропуск" || exit 1; step "отмена пропуска"
# меню конструктора: «Что приготовить» открывается только отсюда
$UI tapnear "У · Ужин" "Собрать из продуктов" || exit 1; step "конструктор для ужина"
$UI tap "Меню" || exit 1; step "меню конструктора"
$UI tap "Что приготовить" || exit 1; step "Что приготовить из конструктора"
sleep 3; step "подбор вариантов"
# статистика калорий и белка по записанным приёмам
adb shell am start -W -n "$ACT" --es route nutrition > /dev/null; sleep 4; step "статистика питания"
$UI has "Средние за день" || exit 1
$UI has "Ккал по дням" || exit 1

# Раздел 20: «По форме» на «Сегодня» (рекомендация или список недостающего), тренировки, форма, защищённые разделы
adb shell am start -W -n "$ACT" --es route today > /dev/null; sleep 4; step "Сегодня"
$UI top
$UI has "По форме" --contains || $UI has "Для рекомендации не хватает" --contains || { echo "нет строки «По форме»"; exit 1; }
adb shell am start -W -n "$ACT" --es route workouts > /dev/null; sleep 4; step "тренировки"
$UI has "+ Бассейн" || exit 1
$UI tap "+ Бассейн" || exit 1; step "диалог тренировки"
$UI has "Рассчитать" || exit 1
adb shell input keyevent KEYCODE_BACK; step "закрытие диалога"
adb shell am start -W -n "$ACT" --es route form > /dev/null; sleep 4; step "форма"
$UI has "7 дней" || exit 1
adb shell am start -W -n "$ACT" --es route condition > /dev/null; sleep 4; step "состояние"
$UI tap "Открыть без защиты" || true; step "разблокировка раздела"
$UI has "Карта состояния" || exit 1
$UI has "Скопировать запрос для Claude" || exit 1
adb shell am start -W -n "$ACT" --es route documents > /dev/null; sleep 4; step "документы"
$UI tap "Открыть без защиты" || true; step "разблокировка документов"
$UI has "+ Документ" || exit 1

# Нижняя навигация: вкладки открываются через маршруты today/… выше; проверяем ещё возврат и повторный запуск
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am force-stop "$PKG"; sleep 1
adb shell am start -W -n "$ACT" > /dev/null; sleep 8
if crashed; then echo "Падение при повторном запуске"; grep -A30 "FATAL EXCEPTION" smoke-logcat.txt; exit 1; fi

adb logcat -d > smoke-logcat.txt

# Инструментальные тесты: миграции v2 → v3 → v4, шифрование документов (19.7, 20.9)
TEST_DIR="${3:-}"
if [ -n "$TEST_DIR" ]; then
  TAPK=$(find "$TEST_DIR" -name "*.apk" | head -1)
  echo "Тестовый APK: $TAPK"
  adb install -r -t "$TAPK" || exit 1
  adb shell am instrument -w -r com.ration.app.debug.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.txt
  grep -q "OK (" instrument.txt || { echo "Тест миграции не прошёл"; exit 1; }
fi
echo "Смоук-тест пройден"
