# TELIQ — Android (телефон, планшет, Android TV)

Одно приложение для **Android TV, телефонов и планшетов** в том же неоновом дизайне, что и десктопная версия.

## Дизайн и адаптивность
- Тема: тёмно-синий фон с неоновыми волнами, градиентные плитки, голубо-фиолетовые акценты (`res/values/colors.xml`, `res/drawable`).
- **Телефон (портрет):** нижнее меню, 2 колонки плиток, баннер «Продолжить просмотр» вертикальный.
- **Телефон (ландшафт) / планшет / ТВ:** боковое меню слева (`layout-w600dp`), 4–7 колонок.
- Размеры и шрифты подбираются по ширине экрана и типу устройства: `values`, `values-w600dp`, `values-w600dp-h480dp`, `values-w960dp`, `values-w600dp-television`, `values-w960dp-television`.
- **Касание:** тап — открыть, долгий тап или ☆ — избранное; в плеере тап — показать карточку, свайп вверх/вниз справа — громкость, слева — яркость, свайп влево/вправо — смена канала.
- **Пульт (D-pad):** фокус подсвечивается неоном; в плеере ↑↓ — канал, ←→ — громкость, OK — список каналов.
- Одинаковые страницы везде: Главная, Телеканалы, Избранное, История, Настройки.

## Сборка
Gradle wrapper уже в проекте:
```
gradlew.bat assembleDebug
```
Готовый установочный файл: `TELIQ-debug.apk` (ставится на телефон и на приставку через `adb install -r TELIQ-debug.apk`).
Путь к Android SDK задаётся в `local.properties` (`sdk.dir=...`) или переменной `ANDROID_HOME`.

---

# TELIQ — Android TV

Нативное Android TV приложение на Kotlin + Leanback + Media3 (ExoPlayer).
Та же функциональность, что и в десктопной PyQt-версии, но с D-pad навигацией.

## Стек
- **Kotlin** 1.9
- **AndroidX Leanback** — карточки каналов, навигация пультом
- **Media3 / ExoPlayer 1.4** — родной Android-плеер для HLS, DASH, RTSP
- **OkHttp** — скачивание плейлистов и EPG
- **Coil** — асинхронная загрузка логотипов
- **Coroutines** — фоновая загрузка / парсинг

## Что портировано из PyQt-версии
- `data/M3uParser.kt` ← `core/m3u_parser.py` (EXTINF, EXTGRP, двойные/одинарные кавычки, авто-кодировка)
- `data/EpgParser.kt` ← `core/epg.py` (XMLTV, gzip, fallback по display-name)
- `data/Settings.kt` ← `core/settings.py` (SharedPreferences вместо JSON)
- `MainFragment.kt` ← `ui/grid_view.py` (Leanback BrowseSupportFragment, ряды по категориям)
- `PlayerActivity.kt` ← `ui/player_view.py` (ExoPlayer вместо VLC, переключение каналов ← →)
- `ui/SettingsActivity.kt` ← `ui/main_window.py::_show_settings_menu` (GuidedStepFragment)
- 3 пресета буферизации те же: 500 / 1500 / 3000 мс

## Сборка

1. **Установить Android Studio** (Hedgehog или новее) — https://developer.android.com/studio
2. Открыть в Android Studio эту папку (`teliq_android`).
3. Android Studio сам скачает Gradle 8.7 и зависимости. **Gradle wrapper нужно сгенерировать один раз** — открой Terminal в Android Studio и выполни:
   ```
   gradle wrapper
   ```
   (либо просто нажми Sync Project — Studio предложит).
4. Подключить Android TV эмулятор (AVD → Television) или физическую приставку по ADB.
5. Run ▶.

## Запуск на физическом Android TV
1. На приставке: Настройки → О телевизоре → 7 раз нажми «Сборка» (включит разработку).
2. Настройки → Для разработчиков → включи USB-отладку и/или Отладку по сети.
3. На компьютере:
   ```
   adb connect <IP-приставки>:5555
   ```
4. Запусти из Android Studio — приложение поставится и появится на главном экране TV.

## Управление
- **D-pad ← ↑ → ↓** — навигация по карточкам и категориям.
- **Enter / OK** — открыть канал.
- **Back** — назад из плеера / категории.
- В плеере: **← →** — переключить канал, **Back** — выйти.

## Структура
```
teliq_android/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/
        │   ├── drawable/   (banner.xml, ic_launcher.xml)
        │   ├── layout/     (activity_main.xml, activity_player.xml)
        │   ├── values/     (strings, colors, themes)
        │   └── xml/        (network_security_config.xml)
        └── java/com/teliq/tv/
            ├── TeliqApp.kt
            ├── MainActivity.kt
            ├── MainFragment.kt           ← Leanback BrowseSupportFragment
            ├── PlayerActivity.kt         ← ExoPlayer
            ├── data/
            │   ├── Channel.kt
            │   ├── M3uParser.kt
            │   ├── EpgParser.kt
            │   └── Settings.kt
            └── ui/
                ├── ChannelCardPresenter.kt
                └── SettingsActivity.kt   ← GuidedStepFragment
```

## TODO для прод-релиза
- Заменить SVG `banner.xml` / `ic_launcher.xml` на PNG из `assets/teliq.png` десктопной версии (320×180 и 108×108).
- Добавить экран-заставку (Splash Screen API 12+).
- Кастомный `ChannelCardPresenter` с показом «Сейчас / Далее» прямо на карточке (Media3 уже поддерживает контент-карточки с двумя строками).
- Сохранять избранное.
- Подписать APK для публикации в Play Store.

---

## Производительность на Android TV (правки от 2026-11-23)
- **Плитки:** на ТВ нет теней и «свечения» у плиток (`tile_elevation` = 0dp в `values-*-television`), фокус виден рамкой и увеличением; слушатели плитки ставятся один раз, а не при каждом bind; Coil без `crossfade`.
- **Фон:** затемнение — цветовой фильтр самой картинки (`tint`), а не отдельный полноэкранный слой; собственный фон окна убран.
- **Запуск:** логотип 1024×1024 декодируется в фоне и уменьшенным; расписание (EPG) применяется к каналам в фоне.
- **Плеер:** один общий `LibVLC` (создаётся заранее в фоне, `TeliqApp.sharedLibVlc()`); освобождение плеера — в фоне (выход без зависания); быстрое листание ↑↓ открывает поток один раз на канале, где остановились (`SWITCH_DEBOUNCE_MS`); смена канала без блокирующего `stop()`.
- **Список каналов в плеере:** под окошком плеера — карточка играющего канала (логотип, название, передача, шкала, «Далее», ★) и ряд «Избранное» (касанием — смотреть), как в ПК-версии. **Ряд «Избранное» с мини-просмотром:** в каждой ячейке — кадр (или логотип) канала и что на нём идёт сейчас; в списке «→» переходит в ряд, «←»/«→» между ячейками, OK — переключиться на канал, «←» с первой ячейки — назад в список; на ячейке под выделением кадры обновляются (второй плеер без звука снимает кадр раз в 1,5 с и закрывается). С избранным окошко плеера чуть меньше, чтобы хватило места на ячейки; категории листаются «←» по кругу.
- Резервная копия исходников до правок: `../teliq_android_src_backup_2026-11-23.zip`.
