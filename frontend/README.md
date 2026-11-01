# Т-Страхование · клиентское приложение

Клиентские экраны на Kotlin и Compose Multiplatform для Android, iOS и Web.
Общая вёрстка находится в `shared/src/commonMain`, платформенные точки входа используют один `App`.

[Макет в Figma](https://www.figma.com/design/s6nhRji3xk5E3It2m9SskY/)

## Запуск

Нужен JDK 21. Для Android установите SDK 37, для iOS — Xcode на macOS.
Команды выполняются из папки `frontend` (в Windows используйте `gradlew.bat`).

```bash
./gradlew :androidApp:assembleDebug
./gradlew :webApp:wasmJsBrowserDevelopmentRun
# Другой вариант Web:
./gradlew :webApp:jsBrowserDevelopmentRun
```

Для iOS откройте `iosApp/iosApp.xcodeproj` в Xcode.

### Backend

Приложение ходит в `/api/mobile` на `http://localhost:8080` (из эмулятора Android — `10.0.2.2:8080`),
Web-версия работает на `localhost:3000` — этот адрес разрешён в CORS backend.
Модели API (`ru.tinsurance.api`) подключены из `../backend/core` через `includeBuild`, поэтому папка `backend`
должна лежать рядом.

Запускайте backend не через Gradle: `./gradlew :server:run` держит блокировку сборки `backend/`,
и Gradle фронтенда ждёт её. Удобнее контейнер (из папки `backend`):

```bash
./gradlew :server:buildFatJar
docker compose --profile app up -d --build
```

В dev-режиме сервер создаёт демо-учётки: клиенты `+79000000001…03`, пароль — `DEV_DEMO_PASSWORD`
в `backend/server/src/main/kotlin/ru/tinsurance/server/demo/DemoData.kt`.

## Экраны

[Предпросмотр всех 12 экранов](docs/client-screens.png)

| № | Экран | Функция | Ссылка в Web |
|---|---|---|---|
| 01 | Мои обращения | `ClaimsScreen` | `#Claims` |
| 02 | Договор и тип события | `ContractScreen` | `#Contract` |
| 03 | Обстоятельства и контакты | `EventScreen` | `#Event` |
| 04 | Фото и документы | `MaterialsScreen` | `#Materials` |
| 05 | Проверка качества фото | `PhotoQualityScreen` | `#PhotoQuality` |
| 06 | Проверка перед отправкой | `ReviewScreen` | `#Review` |
| 07 | Обращение отправлено | `SentScreen` | `#Sent` |
| 08 | Ход обработки | `ProgressScreen` | `#Progress` |
| 09 | Запрос дополнительных материалов | `RequestScreen` | `#Request` |
| 10 | Ответ на запрос | `ReplyScreen` | `#Reply` |
| 11 | Обработка завершена | `CompletedScreen` | `#Completed` |
| 12 | Центр уведомлений | `NotificationsScreen` | `#Notifications` |

Добавьте нужный фрагмент к адресу открытого Web-приложения, например `/#Review`.
В `client/ClientPreviews.kt` также есть отдельный `@Preview` 390 × 844 для каждого экрана.

## Как устроен код

- `App.kt` — переходы между экранами и локальное состояние сценария.
- `data/` — `MobileApiClient` (Ktor, JWT с автообновлением) и адрес backend для каждой платформы.
- `client/ClientViewModel.kt` — вход, выход и список обращений из API.
- `client/` — обычные Compose-функции экранов, черновик и проверка полей.
- `ui/` — тема, кнопки, поля, карточки, шапка и нижнее меню.
- `PlatformFiles.*.kt` — камера, сохранение PDF и системная кнопка «Назад».
- `composeResources/` — изображения и иконки из Figma, шрифт Inter и два учебных PDF.

Поля, вкладки, выбор договора, чекбоксы, просмотр обращений и уведомления работают.
Черновик хранится через `rememberSaveable`: введённые значения не теряются при переходах между экранами.
На узком экране содержимое прокручивается, нижняя кнопка остаётся доступной; учитываются системные отступы и клавиатура.

FileKit открывает системный выбор файлов на всех платформах. Камера работает на Android/iOS;
в браузере эта кнопка открывает выбор изображения. Принимаются JPEG, PNG и PDF: до 20 МБ на файл,
10 файлов и 100 МБ на обращение. Повторно выбранные файлы сравниваются по содержимому.

## Тестовые данные

Вход, профиль, полис, список «Мои обращения», а также «Ход обработки» и «Обработка завершена»
для обращений из списка берутся из backend. Токены хранятся в памяти:
после перезапуска приложения нужно войти снова. Остальные экраны пока работают по локальному демонстрационному
сценарию: отправка заявления и дополнения меняет состояние интерфейса, но не отправляет запросы в backend. Статусы и предупреждение о тёмном фото взяты из макета;
новые фото не проходят ML-проверку. Вкладки «Полисы» и «Профиль» открывают диалоги, поскольку отдельных
клиентских экранов для них в макете нет.

Вложения с устройства хранятся в памяти текущего запуска. После перезапуска их нужно выбрать заново.
Скачиваемые PDF — помеченные учебные образцы, а не документы, сформированные сервером.
Inter распространяется по SIL Open Font License; текст лицензии находится в `composeResources/files/inter_OFL.txt`.

## Проверка

```bash
./gradlew :shared:compileKotlinJs :shared:compileKotlinWasmJs
./gradlew :shared:jsBrowserTest
./gradlew :androidApp:assembleDebug
```

Для браузерных тестов нужен Chrome/Chromium. `ClaimValidationTest` проверяет календарные даты,
обязательные поля, адрес почты и ограничения вложений.
