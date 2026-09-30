# Рацион — Android-приложение учёта питания

Однопользовательское офлайн-приложение: кладовая, заготовки, план дня из готовых блоков еды (разделы 7–9 ТЗ), напоминания, вес и давление. Без регистрации, сервера, аналитики и рекламы. Интерфейс на русском, 24-часовой формат, часовой пояс устройства, метрические единицы.

> «Рацион» не является медицинским приложением: он показывает цели и предупреждения по заданным правилам и не заменяет врача.

В репозитории также лежит несвязанный каталог `featurescript/` (Onshape) — к приложению отношения не имеет.

## Сборка и запуск

Требуется JDK 17+ и Android SDK (платформа 36). Путь к SDK — в `local.properties` (`sdk.dir=...`) или `ANDROID_HOME`.

```bash
./gradlew assembleOfflineDebug     # основная сборка для пользователя (без INTERNET)
./gradlew assembleApiDebug         # сборка с необязательным режимом Claude API
./gradlew assembleDebug            # обе
./gradlew test                     # юнит-тесты
./gradlew lint                     # статический анализ
./gradlew dependencyUpdates        # проверка версий зависимостей
./gradlew assembleOfflineRelease   # release: R8 + shrinkResources, debuggable=false
```

### Сборка на GitHub Actions

Workflow `.github/workflows/android.yml` запускается на каждый push (и вручную: Actions → Android APK → Run workflow). Он собирает debug APK обеих сборок, проверяет, что в offline нет `INTERNET` (`aapt dump permissions`), затем гоняет юнит-тесты и lint. Готовые APK — в артефакте `ration-apk-<sha>` на странице запуска (Summary → Artifacts), отчёты — в `reports-<sha>`. Для телефона нужен файл `app-offline-debug.apk` (разрешить установку из неизвестных источников).

Установка: `adb install app/build/outputs/apk/offline/debug/app-offline-debug.apk`.

### Сборки (product flavors)

| Flavor | INTERNET | Режим Claude API | applicationId |
|---|---|---|---|
| `offline` (по умолчанию, выдаётся пользователю) | нет | недоступен | `com.ration.app` |
| `api` | да, только для раздела 5.6 | включается вручную с ключом | `com.ration.app.api` |

## Структура

Один модуль `app`, пакеты по слоям:

- `domain` — чистая логика без Android: парсер импорта и промпты, списание FIFO, пороги 40/20, планировщик дня, пересчёт после съеденного, предупреждения, правила напоминаний, замены, недельные счётчики, резервная копия (JSON + AES-GCM), отчёт для врача.
- `data` — Room (сущности, DAO), DataStore (настройки одной JSON-строкой), EncryptedSharedPreferences (API-ключ), засев (`data/seed/SeedData.kt`), репозитории, чтение календаря.
- `notifications` — AlarmManager (`setExactAndAllowWhileIdle`, при отказе в точных будильниках — неточный `setAndAllowWhileIdle`), ресиверы, WorkManager-страховка каждые 6 часов.
- `ui` — Jetpack Compose + Material 3, Navigation Compose, Hilt ViewModel.

## Форматы импорта

### Строка покупки (5.1)

```
название | количество | единица | цена
```

- Разделители `|`, `;` или табуляция; CSV-файлы с запятыми конвертируются автоматически.
- Единицы: г, кг, мл, л, шт. `кг → г ×1000`, `л → мл ×1000`. Без единицы — шт. Цена необязательна.
- Десятичная запятая допустима, слитная запись `1.5кг` разбирается; допустима запись без разделителей `Картофель 1.5кг`.
- Строки с `#` и пустые игнорируются (строки `#` показываются как «неразборчивые»).
- Сопоставление с каталогом: точное → псевдоним → нечёткое. Новое название, связанное с продуктом, сохраняется как псевдоним.
- Лимиты недоверенного ввода: 1 МБ, 2000 строк, название ≤ 120 символов, проверка диапазонов.

### Строка этикетки (5.2)

```
название | ккал на 100 г | белок на 100 г | размер порции г | ккал на порцию | белок на порцию
```

Обязательны название и значения на 100 г; если указана только порция — значения на 100 г пересчитываются. Результат сохраняется как «Свой продукт» (CustomFood).

### Промпты

Кнопки «Скопировать промпт для чека/этикетки» (Закупки → Импорт). Промпт для чека подставляет актуальный каталог (кроме приправ-«мелочей»).

## Режим Claude API (только сборка `api`)

Выключен по умолчанию. Ключ вводится в Настройках, хранится в EncryptedSharedPreferences (Android Keystore), не логируется и не попадает в резервную копию. Перед первым запросом — предупреждение о платности. Запрос отправляется только по кнопке «Распознать по фото»: изображение (уменьшено до 1568 px, JPEG) и промпт 5.3/5.4. Ответ проходит тот же парсер и экран проверки. Используется официальный Anthropic Java SDK. Модель по умолчанию — `claude-opus-5-5`, выбирается в Настройках. Без ключа или при ошибке сети — копирование промпта.

## Резервная копия

Настройки → Резервная копия. Экспорт/импорт через системный выбор файла (SAF).

```json
{
  "format": "ration-backup",
  "schemaVersion": 1,
  "encrypted": false,
  "payload": "<JSON BackupData>"
}
```

Зашифрованный вариант: `encrypted: true`, `kdf: "PBKDF2WithHmacSHA256"`, `iterations: 210000`, `salt`, `iv` (base64), `payload` — base64 шифротекста AES-256-GCM (AAD = `ration-backup`). Пароль нигде не хранится.

`BackupData`: `schemaVersion, exportedAtMillis, settings, products, stock, purchases, purchaseLines, blocks, blockIngredients, prepTemplates, preps, mealLogs, customFoods, substitutions, dayPlans, plannedSlots, quickLogs, weights, bp`. Импорт: лимит 20 МБ, строгая схема (лишние поля отклоняются), проверка диапазонов и ссылочной целостности, замена базы одной транзакцией — при ошибке данные не меняются. API-ключ в схеме отсутствует.

## Что редактирует пользователь

- Настройки: цели ккал/белка, время слотов для типов А и Б и батончика в дороге, пороги закупки (40/20), задержка напоминания об отметке, вечерний прогноз, проверка закупок, тихие часы, день/время взвешивания и заготовки, кофейный лимит, подсказка по календарю, блокировка, режим Claude API. Остальные параметры раздела 6 (контрольные суммы, недельные лимиты, пороги предупреждений) лежат в `AppSettings` и переносятся резервной копией.
- Кладовая: остаток, партии со сроком, нормальный запас (ручной или по последней закупке), «не отслеживать».
- Блоки: ккал и белок, замены ингредиентов (постоянные и разовые).
- Продукты из импорта: создание, псевдонимы; свои продукты с этикеток.

## Какие данные хранятся и как их удалить

| Данные | Где |
|---|---|
| Продукты, партии, покупки, блоки, заготовки, журнал питания, план, вода/кофе/алкоголь, вес, давление | Room `databases/ration.db` |
| Настройки, состояние порогов, отправленные предупреждения | DataStore `datastore/settings.preferences_pb` |
| API-ключ (сборка api) | EncryptedSharedPreferences `secure_prefs` |
| Временные отчёты для врача | `cache/reports/` (удаляются при следующем экспорте и через час) |

Полное удаление: Настройки → «Удалить все данные» (база, настройки, ключ, кэш отчётов; справочники засеиваются заново) или удаление приложения. Облачный бэкап Google и перенос на новое устройство отключены.

## Зависимости (версии зафиксированы в `gradle/libs.versions.toml`)

| Библиотека | Версия |
|---|---|
| Android Gradle Plugin | 8.10.1 |
| Kotlin / Compose compiler plugin / serialization plugin | 2.1.21 |
| KSP | 2.1.21-2.0.1 |
| Hilt (dagger) / androidx.hilt (navigation-compose, work) | 2.56.2 / 1.2.0 |
| Room | 2.7.1 |
| Compose BOM (ui, material3, material-icons-core) | 2025.05.01 |
| Navigation Compose | 2.9.0 |
| Lifecycle (runtime-compose, viewmodel-compose) | 2.9.0 |
| Activity Compose / Fragment KTX / Core KTX | 1.10.1 / 1.8.6 / 1.16.0 |
| WorkManager | 2.10.1 |
| DataStore Preferences | 1.1.7 |
| Security Crypto (EncryptedSharedPreferences) | 1.1.0-alpha07 |
| Biometric | 1.1.0 |
| kotlinx.serialization-json / coroutines | 1.8.1 / 1.10.2 |
| Anthropic Java SDK (только `api`) | 2.66.0 |
| JUnit | 4.13.2 |
| gradle-versions-plugin | 0.52.0 |

## Тесты

`app/src/test` — JVM-тесты доменной логики (65): засев совпадает с таблицами раздела 7 и сырыми весами раздела 8; парсер (разделители, запятая, `1.5кг`, комментарии, кг→г, л→мл, CSV, лимит 1 МБ); списание (заготовка FIFO по сроку, сырьё FIFO, множитель, пересчёт шт↔г, нехватка, альтернатива «или», отмена возвращает остаток); пороги (один раз, перевзвод после покупки, не чаще раза в день, единый список); планировщик (суммы Б и А в диапазонах 6.2, дорога, батончики, красное мясо, F, жирная рыба, разнообразие, пересчёт после переедания утром, добор белка); напоминания (+120 мин, отмена отметкой, перенос времени, единственный повтор «Через 30 минут», тихие часы); резервная копия (круговой экспорт/импорт, шифрование, неверный пароль, диапазоны, ссылки, версия схемы); замены, предупреждения дня, подсказка календаря, темп веса.

## Статус проверки

- Доменная логика (`domain`, сущности, засев) и все 65 тестов проверены на JVM (Kotlin 2.1.21, JUnit 4) — зелёные.
- Код Claude API (`src/api/.../sdk/ClaudeVisionClient.kt`) скомпилирован против anthropic-java 2.66.0.
- Android-сборка (`assembleDebug`, `lint`, запуск на устройстве, `aapt dump permissions`) в среде разработки не выполнялась: Google Maven и Android SDK там недоступны. Эти пункты приёмки (10.3.1, 10.3.3, 10.3.4, 11.4.3–11.4.4) нужно пройти на машине с Android SDK.

## Отклонения и разрешённые противоречия ТЗ

- Пищевая ценность продуктов хранится на 100 г для всех единиц (для «шт» пересчёт через `gramsPerPiece`) — так записана таблица 9.1.
- Лимоны отслеживаются (раздел 4), хотя 9.1 относит лимон к мелочам.
- Подсказка по календарю: порог 11:00 (разделы 6.2 и 11.3; в разделе 3 — 12:00), событие длиннее 30 минут; порог настраивается.
- Неделя счётчиков — с понедельника, сброс после воскресенья (6.5 и раздел 3 согласованы так).
- «Субботняя партия» (в 9.2 названа «воскресный шаблон») запускает курицу, картофель с бататом и яйца.
- Раннее переедание проверяется по обоим правилам: 70% до 15:00 (раздел 4) и 60% до 12:00 (6.5).
- «Поделиться» принимается главной Activity (единственный экспортируемый компонент), а не отдельной.

## Аудит безопасности (11.1–11.3)

| Пункт | Выполнено | Где |
|---|---|---|
| 11.1.1 Данные только на устройстве, нет аналитики/рекламы/краш-репортеров | да | зависимости выше; сетевой код только в `src/api` |
| 11.1.2 Флейворы offline/api; INTERNET только в api | да | `app/build.gradle.kts` (productFlavors), `src/api/AndroidManifest.xml` |
| 11.1.3 HTTPS, `usesCleartextTraffic=false`, запрос только по кнопке, только фото/текст + промпт, ответ через парсер | да | `AndroidManifest.xml`, `src/api/.../RecognizerModule.kt`, `ClaudeVisionClient.kt`, `ImportViewModel.recognizePhoto` |
| 11.1.4 Ключ в EncryptedSharedPreferences, не логируется, не в бэкапе | да | `data/settings/SecureKeyStore.kt`, `BackupData` |
| 11.2.1 `allowBackup=false`, dataExtractionRules и fullBackupContent исключают всё | да | манифест, `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` |
| 11.2.2 Экспорт через SAF, предупреждение, AES-GCM + PBKDF2, пароль не хранится | да | `ui/settings/SettingsScreen.kt`, `domain/backup/BackupCodec.kt` |
| 11.2.3 Отчёт через FileProvider с FLAG_GRANT_READ_URI_PERMISSION, файлы в кэше и удаляются | да | `ui/health/HealthScreens.kt`, `res/xml/file_paths.xml`, `MaintenanceWorker` |
| 11.2.4 Блокировка BiometricPrompt / PIN | да | `ui/nav/AppRoot.kt` (LockScreen) |
| 11.2.5 FLAG_SECURE на экранах веса, давления и отчёта | да | `SecureScreen` в `HealthScreen`, `ReportScreen` |
| 11.3.1 Только разрешённые разрешения; календарь по включению подсказки; только время событий | да, с оговоркой | манифест; `CalendarReader.kt`. Оговорка ниже |
| 11.3.2 Все компоненты `exported=false`, кроме главной Activity; PendingIntent FLAG_IMMUTABLE | да, с оговоркой | манифест; `AppNotifier`, `ReminderScheduler` |
| 11.3.3 Уведомления без значений здоровья, VISIBILITY_PRIVATE для деликатных | да | `notifications/AppNotifier.kt` |
| 11.3.4 Импорт — недоверенный ввод: лимиты, проверка, без частичной записи; запросы Room параметризованы | да | `ImportParser`, `BackupCodec.validate`, `BackupRepository.import`, DAO |
| 11.3.5 Нет WebView, динамической загрузки кода, Runtime.exec | да | поиск по исходникам |
| 11.4.1 R8 + shrinkResources, debuggable=false, секретов нет | да | `app/build.gradle.kts` |
| 11.4.2 Логи без данных пользователя, в release ниже WARN вырезаны | да | `proguard-rules.pro` (`-assumenosideeffects`), единственный `Log.w` без данных |

Оговорки:
- Библиотеки подмешивают в манифест: `USE_BIOMETRIC` и `USE_FINGERPRINT` (biometric — нужны для требования 11.2.4), `RECEIVE_BOOT_COMPLETED` (есть в списке), `${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (core-ktx, signature-разрешение внутри приложения). `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE` от WorkManager удалены через `tools:node="remove"` (сетевые ограничения, foreground-работы и SystemAlarmScheduler не используются; на API 26+ WorkManager работает через JobScheduler).
- `androidx.work.impl.background.systemjob.SystemJobService` объявлен библиотекой как exported и защищён `BIND_JOB_SERVICE` (привязываться может только система) — это требование JobScheduler. Экспортируемые отладочные ресиверы `ProfileInstallReceiver` и `DiagnosticsReceiver` удалены из манифеста.

Проверка разрешений собранного APK:

```bash
$ANDROID_HOME/build-tools/36.0.0/aapt dump permissions app/build/outputs/apk/offline/release/app-offline-release-unsigned.apk
```

Ожидаемо для offline: `POST_NOTIFICATIONS, READ_CALENDAR, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, USE_BIOMETRIC, USE_FINGERPRINT, com.ration.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` и без `INTERNET`.
