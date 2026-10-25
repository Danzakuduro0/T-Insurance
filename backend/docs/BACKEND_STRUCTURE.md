# Структура бэкенда «Т-Страхование: сопровождение страхового случая»

Состояние на 25.10.2026: готовы авторизация, черновики и отправка обращений, проверка комплектности,
очередь и карточка оператора, загрузка файлов, фоновый анализ фото и маршрутизация после анализа,
подтверждение и исправление решений системы оператором, назначение обращений (вручную и автоматически).
Что дальше — в конце документа.

## Стек

| Что | Чем |
|---|---|
| Сервер | Ktor 3 (Netty), Kotlin 2.4, JDK 21 |
| База | PostgreSQL 16 (локально — `docker-compose.yml`) |
| Миграции | Flyway, применяются сами при старте сервера |
| Доступ к БД | Exposed (DSL) + HikariCP |
| Авторизация | JWT (HS256): access 15 мин + одноразовый refresh |
| Модели запросов/ответов | модуль `core`, общий с мобильным приложением (kotlinx.serialization) |
| Тесты | Ktor test host + встроенный PostgreSQL (Docker не нужен) |

Два клиента: **мобильное приложение клиента** (`/api/mobile`, вход по телефону) и **веб оператора** (`/api/operator`, вход по email).

## Модули репозитория

```
backend/             ← папка бэкенда в репозитории T-Insurance
├── server/            ← бэкенд (Ktor)
├── core/              ← общие DTO для сервера и клиентских приложений
├── docs/              ← документация (этот файл, ответы на вопросы, контракт ML)
├── .run/              ← конфигурации запуска IntelliJ: Server (dev-режим) и Server tests
├── postman/           ← коллекция Postman для ручной проверки API
├── docker-compose.yml ← PostgreSQL; сервер в контейнере — с профилем app
├── Dockerfile         ← образ сервера из собранного jar
└── .env.example       ← переменные окружения (сам .env в git не попадает)
```

## server: код

Пакет `ru.tinsurance.server`, путь `server/src/main/kotlin/…`. Каждый раздел делится по ролям:

| Слой | Что делает |
|---|---|
| `*Routes.kt` | только HTTP: разобрать запрос, вызвать сервис, отдать ответ |
| `*Service.kt` | сценарий: что за чем вызвать в одной транзакции `db.tx { }` |
| `*Repository.kt`, `*Queries.kt` | запросы Exposed; вызываются внутри транзакции |
| `*Validator.kt`, `*Rules.kt` | проверки и правила |

```
Application.kt              точка входа: проверка конфигурации, БД, плагины, маршруты, фоновый воркер
AppContainer.kt             создаёт все сервисы один раз (зависимости — через конструкторы)
│
├── plugins/                Http.kt — JSON, CORS, лимиты, ошибки → HTTP; Routing.kt — /health и маршруты
├── common/                 Config.kt (чтение настроек), Errors.kt, Transactions.kt (db.tx), Formats.kt, AppMode.kt
├── db/                     Database.kt (пул, Flyway) и таблицы Exposed по разделам:
│                           ReferenceTables, UserTables, ClaimTables, FileTables, JobTables, Views
├── users/                  UserRepository — пользователи, имена пачкой для карточек
│
├── auth/
│   ├── AuthService.kt      вход, обновление и отзыв токенов, защита от перебора
│   ├── AuthRoutes.kt       /auth/login, /auth/refresh, /auth/logout — одна функция для обоих каналов
│   ├── SessionRepository.kt, DeviceRepository.kt
│   ├── Tokens.kt           JWT, refresh-токены, bcrypt
│   ├── Security.kt         провайдеры AUTH_MOBILE / AUTH_OPERATOR, лимит частоты /auth
│   └── LoginThrottle.kt    блокировка логина после неудачных попыток
│
├── claims/                 логика обращений, общая для API и воркера
│   ├── ClaimRepository.kt  обращение клиента, создание черновика, изменение полей
│   ├── ClaimInput.kt       поля от клиента (общие для создания и изменения), JSON изменений для журнала
│   ├── ClaimInputValidator.kt  размеры, участники, доп. сведения, дата события
│   ├── ClaimUpdater.kt     изменение обращения (в черновике — всё, по запросу — только запрошенное)
│   ├── ClaimSubmission.kt  отправка: комплектность → NEEDS_INFO или IN_ANALYSIS
│   ├── ClaimIntake.kt      решение о комплектности, запрос материалов, переход к анализу
│   ├── ClaimStatusService.kt  смена статуса + история (единственное место, где меняется статус)
│   ├── ClaimTransitions.kt граф разрешённых переходов
│   ├── ClaimDecisionWriter.kt  решения системы в claim_decisions (история не перезаписывается)
│   ├── CompletenessChecker.kt, CompletenessResult.kt, ClaimRules.kt  что нужно по типу события и чего не хватает
│   ├── ParticipantRepository.kt, PolicyRepository.kt, InfoRequestRepository.kt, DirectionRepository.kt
│   ├── AuditWriter.kt      журнал действий (audit_log) — одна точка записи
│   └── Actor.kt, ClaimsConfig.kt
│
├── mobile/                 API клиента: MobileRoutes, MobileClaimService, MobileClaimQueries (список, карточка, таймлайн)
├── operator/               API оператора: OperatorRoutes, OperatorClaimService,
│                           QueueFilter (разбор фильтров), QueueRepository, ClaimCardQueries,
│                           DecisionReviewService (подтвердить / исправить решение), DecisionOverride (новое значение
│                           и его применение к обращению), ClaimWorkAccess (кто из сотрудников может менять обращение)
│
├── assignment/             назначение обращений операторам
│   ├── AssignmentService.kt  смена (ONLINE/BREAK/OFFLINE), «Взять в работу», начать рассмотрение, вернуть в очередь,
│   │                       назначение и переназначение администратором
│   ├── AutoAssigner.kt     автоназначение: прежний оператор или наименее загруженный на смене; SKIP LOCKED
│   ├── ClaimAssigner.kt    назначить / переназначить / снять — с историей и журналом
│   ├── OperatorRepository.kt  операторы, направления, нагрузка (блокировка строки оператора)
│   └── AssignmentRoutes.kt, AssignmentConfig.kt
│
├── files/
│   ├── UploadService.kt    сценарий загрузки: проверки → содержимое → сохранение
│   ├── UploadRules.kt      когда можно добавлять и заменять файлы
│   ├── UploadRecorder.kt   запись принятого / отклонённого / дубля, постановка фото на анализ
│   ├── DuplicateFinder.kt  тот же снимок в другом обращении (SHA-256 или перцептивный хэш)
│   ├── FileAccessService.kt  отдача содержимого клиенту и оператору
│   ├── FileRepository.kt   запросы к claim_files
│   ├── FileInspector.kt, ImageMetadata.kt  проверка JPEG/PNG/PDF, удаление EXIF/GPS, dHash
│   ├── MultipartUpload.kt  multipart во временный файл, безопасная отдача файла
│   └── FileStorage.kt, FilesConfig.kt, UploadModels.kt, Rejections.kt
│
├── jobs/                   JobQueue (background_jobs, SKIP LOCKED, повторы), JobWorker (цикл, параллельность)
├── analysis/               ImageAnalyzer, HttpImageAnalyzer (ML), LocalImageAnalyzer (без ML), AnalyzeImageHandler,
│                           ImageAnalysisRepository, AnalysisConfig, MlResponse, QualityHints
├── routing/                RoutingRules (чистые правила), RouteClaimHandler (задача ROUTE_CLAIM),
│                           RoutingDecisionWriter (решения и постановка в очередь), RoutingConfig
└── demo/                   DemoData, DemoSettings — вымышленные данные для пустой БД
```

### Как проходит запрос

```
HTTP → plugins/Http (лимит размера, JSON) → auth (JWT, канал) → *Routes → *Service → db.tx { Exposed } → PostgreSQL
                                                         ошибка ↘ ApiException → понятный JSON {code, message}
```

- **Маршруты** только читают параметры и вызывают сервис.
- **Сервисы** проверяют права и бизнес-правила и работают с БД в транзакции `db.tx { }`.
- **Доступ к чужим данным:** клиентские запросы всегда фильтруются по `clientId` из токена. Чужое обращение даёт 404, а не 403, чтобы не раскрывать, что такой id существует.
- **Ошибки БД** клиенту отдаются общими сообщениями, подробности пишутся только в лог.
- **Токен после выхода:** на каждый запрос проверяется, что сессия токена не отозвана и пользователь активен. Поэтому выход и блокировка действуют сразу, а не через 15 минут.

### Конфигурация — `server/src/main/resources/application.yaml`

| Раздел | Что настраивает | Переменные окружения |
|---|---|---|
| `app.mode` | dev / prod (пусто → dev при запуске из IDE/gradle, иначе prod) | `APP_MODE` |
| `app.timeZone` | пояс для фильтров очереди «с даты по дату», если веб не передал `tz` | `APP_TIME_ZONE` |
| `db.*` | подключение к PostgreSQL | `DB_URL`, `DB_USER`, `DB_PASSWORD` |
| `jwt.*` | секрет и сроки жизни токенов | `JWT_SECRET` (в prod обязателен, 32+ символа) |
| `demo.*` | заливка демо-данных | `DEMO_SEED`, `DEMO_PASSWORD` |
| `storage.localDir` | папка с файлами (по умолчанию `~/.tstrah/files`) | `STORAGE_DIR` |
| `files.*` | лимиты: 15 файлов и 100 МБ на обращение, 50 Мп на фото; одновременная обработка загрузок; порог похожести фото | — |
| `security.*` | лимиты входа и размер JSON-запроса | — |
| `analysis.*` | адрес ML-сервиса, таймаут, паузы повторов, сколько ждать анализ | `ML_URL` |
| `routing.confidenceThreshold` | порог уверенности модели (0.65) | — |
| `jobs.*` | фоновый воркер: включён ли, опрос, параллельность, lease | — |
| `assignment.*` | автоназначение и лимит обращений в работе по умолчанию (5) | — |
| `claims.infoRequestDueDays` | срок ответа на запрос материалов (7 дней) | — |
| `cors.allowedHosts` | откуда разрешены запросы веба оператора | — |

## core: общие модели

`core/src/commonMain/kotlin/ru/tinsurance/api/` — всё, что ходит по сети, общее для сервера, приложения клиента и веба оператора:

| Файл | Что внутри |
|---|---|
| `ApiPaths.kt` | адреса эндпоинтов `MobileApi.*`, `OperatorApi.*`; параметры: `MobileApi.CLAIM.path("id" to 42)` |
| `Enums.kt` | статусы и коды: `ClaimStatus`, `FileStatus`, `AnalysisStatus`, `InfoRequestStatus`, `MaterialIssue`, `DecisionKind`… — совпадают с кодами в БД |
| `Common.kt` | `ErrorResponse`, `Page<T>` |
| `Auth.kt` | вход, токены, `UserDto` |
| `Claims.kt` | общие для обоих клиентов: полис, участники, файлы, запросы материалов |
| `mobile/` | черновик, карточка клиента, список, таймлайн, комплектность, форма по типу события |
| `operator/` | очередь, карточка оператора, решения, история, анализ фото |

`core` собирается под JVM (сервер и Android-приложение), JS и Wasm (веб оператора на Compose), iOS — без Android SDK.
Первая синхронизация в IDEA скачивает компилятор Kotlin/Native (один раз).

Сервер регистрирует маршруты теми же константами из `ApiPaths`, поэтому адрес на клиенте и сервере не разойдётся. Фронтенд из папки `frontend/` подключит модуль так: `includeBuild("../backend")` и зависимость от `core`. Если добавляешь поле в DTO, давай ему значение по умолчанию — тогда старая версия приложения не сломается.

## База данных

Миграции лежат в `server/src/main/resources/db/migration`:

| Версия | Что делает |
|---|---|
| V1 | Базовая схема: пользователи, полисы, обращения, файлы, история, аудит, задачи |
| V2 | Справочники: статусы, переходы, типы событий и документов, направления |
| V3 | Разделение на мобильного клиента и веб оператора: сессии, устройства, запросы материалов, представления |
| V4 | Приведение к ответам на вопросы: видимость статусов клиенту, SLA, правила полей и документов по типу события, решения по маршрутизации |
| V5 | Отдельный комментарий для клиента в истории статусов (внутренняя причина клиенту не показывается) |
| V6 | Удалено неиспользуемое представление `claim_client_timeline` |
| V7 | Статус файла `DELETED` (удаление из черновика); `dedup_key` фоновых задач уникален только среди активных |

**Применённую миграцию не редактировать никогда.** Любое изменение схемы делается новым файлом `V8__…sql`, `V9__…sql`. Затем нужно обновить таблицы в `db/` и номер версии в `ApplicationTest`.

### Таблицы

| Группа | Таблицы |
|---|---|
| Справочники | `claim_statuses`, `status_transitions`, `event_types`, `event_type_fields`, `document_types`, `required_documents`, `directions` |
| Люди и доступ | `users` (роль CLIENT / OPERATOR / ADMIN), `operator_directions`, `auth_sessions` (refresh-токены, хранится хэш), `user_devices` |
| Обращения | `policies`, `claims`, `claim_participants`, `claim_status_history`, `claim_comments` |
| Материалы | `claim_files`, `image_analyses`, `info_requests`, `info_request_items` |
| Маршрутизация | `claim_decisions` (только добавление записей: SET / CONFIRM / OVERRIDE) |
| Служебные | `audit_log`, `notifications`, `background_jobs` |

- **Представления (view):** `client_claim_list`, `operator_claim_list`, `operator_load`, `queue_load`.
- **Триггеры:**
  - `claims_before_update` не даёт выполнить переход статуса, которого нет в `status_transitions`;
  - `status_history_check_actor` проверяет, что переход делает разрешённая роль.

### Статусы обращения

```
DRAFT ──client──▶ SUBMITTED ──system──▶ IN_ANALYSIS ──system──▶ QUEUED ──▶ ASSIGNED ──operator──▶ IN_REVIEW ──operator──▶ COMPLETED
  │                  │                      │                     ▲   ◀──────────┘                │
  │                  └──system──▶ NEEDS_INFO ◀──system──┘          └──────────────── operator ─────┤
  │                                  │  ▲───────────────────────── operator ──────────────────────┘
  │                                  └──client──▶ SUBMITTED
  └──client──▶ CANCELLED ◀── закрытие без обработки (сотрудник — только с причиной)
```

Граф хранится в двух местах: в таблице `status_transitions` (V2, V4) и в `claims/ClaimTransitions.kt`. Тест `ClaimTransitionsTest` проверяет, что они совпадают, поэтому при изменении графа правь оба места. Статус меняется только через `ClaimStatusService.transition(...)`: он же пишет историю, проставляет время и проверяет обязательную причину.

**Система не принимает решение о выплате.** `COMPLETED` означает «рассмотрение завершено оператором», и итог ставит человек.

## API

| Канал | Префикс | Вход |
|---|---|---|
| Мобильное приложение клиента | `/api/mobile` | телефон + пароль |
| Веб оператора | `/api/operator` | email + пароль |
| Служебное | `/health` | без авторизации, отдаёт версию схемы БД |

Полный список эндпоинтов — в [README.md](../README.md#backend-api), примеры запросов — в `postman/TStrah.postman_collection.json`.

Коды ответов, которые стоит обрабатывать на клиенте:

| Код | Когда |
|---|---|
| 400 | Ошибка в данных |
| 401 | Нет токена или он истёк — обнови через `/auth/refresh` |
| 404 | Не найдено или чужое |
| 409 | Недопустимый переход или конфликт |
| 413 | Слишком большой запрос или файл |
| 422 | Файл не принят; в ответе есть `rejectionReason` и `hint` |
| 429 | Слишком много попыток; смотри заголовок `Retry-After` |

## Docker

Сервер в контейнер собирается из готового jar.

| Что сделать | Команда |
|---|---|
| Поднять только базу (сервер — из IntelliJ) | `docker compose up -d` |
| Собрать jar сервера | `./gradlew :server:buildFatJar` |
| Поднять базу и сервер в контейнере | `docker compose --profile app up -d --build` |
| Состояние контейнеров | `docker compose --profile app ps` |
| Логи сервера (Ctrl+C — выйти) | `docker compose logs -f server` |
| Логи базы | `docker compose logs -f postgres` |
| Пересобрать сервер после изменений в коде | `./gradlew :server:buildFatJar && docker compose --profile app up -d --build server` |
| Остановить сервер в контейнере (база работает) | `docker compose stop server` |
| Остановить всё (данные сохраняются) | `docker compose --profile app down` |
| Консоль SQL | `docker compose exec postgres psql -U tstrah -d tstrah` |
| Резервная копия базы | `docker compose exec postgres pg_dump -U tstrah tstrah > backup.sql` |
| **Удалить все данные** (база и файлы с нуля) | `docker compose --profile app down -v` |

- Сервер в контейнере по умолчанию работает в dev: демо-учётки, случайный JWT-секрет. Для показа задайте в `.env` `APP_MODE=prod` и `JWT_SECRET`.
- Файлы обращений из контейнера лежат в томе `files`, а не в `~/.tstrah/files`.
- Сервер из IntelliJ и сервер в контейнере используют один порт 8080, поэтому запускайте что-то одно.

## Тесты

Лежат в `server/src/test/kotlin/…`. Запуск: `./gradlew :server:test` (90 тестов, Docker не нужен).

| Файл | Что проверяет |
|---|---|
| `TestDatabase.kt`, `ApiTestSupport.kt` | Встроенный PostgreSQL, тестовый конфиг, вход демо-пользователями |
| `ApplicationTest` | Запуск, `/health`, версия схемы |
| `SchemaTest` | Ограничения и триггеры БД |
| `ClaimTransitionsTest` | Граф в коде совпадает с таблицей в БД |
| `AuthTest` | Вход, refresh, logout, разделение каналов |
| `MobileApiTest`, `OperatorApiTest` | Основные сценарии обоих API, недоступность чужих данных |
| `SubmitTest`, `AnswersAlignmentTest` | Комплектность, отправка, запрос материалов, правила из ответов на вопросы |
| `FilesTest` | Загрузка, отказы, удаление EXIF, дубли (точные и пересжатые копии), замена, лимиты, path traversal, доступ к фото, отклонённым анализом |
| `SecurityTest` | Режим prod, блокировка входа, rate limit, утечки ошибок БД, лимиты размеров |
| `JobWorkerTest` | Воркер на крайних случаях: сбой при завершении задачи, «зависшая» задача без попыток, повторная постановка по `dedup_key` |
| `ClaimFlowSupport.kt` | Общий сценарий «обращение прошло анализ»: поддельный ML-сервис, тестовые фото, запуск воркера |
| `AssignmentTest` | Шаг 5: два одновременных «Взять в работу» — успешен один, направление и лимит, начало рассмотрения и возврат в очередь, автоназначение после анализа и при выходе на смену, возврат к прежнему оператору, назначение администратором |
| `OperatorDecisionsTest` | Шаг 4: подтверждение и исправление решений, обязательная причина, права оператора и администратора, чистка старых сессий |
| `AnalysisTest` | Шаг 3 с поддельным ML-сервисом: уверенный результат → STANDARD, низкая уверенность → MANUAL_REVIEW, плохое фото → запрос переснять, ML недоступен → повторы и ручная проверка, «зависшие» задачи, локальная проверка качества, правила маршрутизации |

## Документы

- `docs/OPEN_QUESTIONS.md` — ответы заказчика и где каждый реализован.

## Что ещё не сделано

| Шаг | Что | Решения, принятые заранее |
|---|---|---|
| 6 | Действия оператора | Запрос материалов, итог рассмотрения, закрытие |
| 7 | Уведомления | Создаются в `ClaimStatusService.transition` в той же транзакции, что и переход. Какие статусы уведомляют, задаётся отдельно: не каждый видимый клиенту переход стоит push. Доставку выполняет воркер |
| 8 | Администрирование | `GET /api/operator/dashboard` по `queue_load` / `operator_load`: признак перегрузки по порогу, рекомендации, средние сроки |
| 9 | OpenAPI-описание, хранилище S3 | |
| 10 | Нагрузочный профиль, сценарий демо | |

Таблицы для шагов 3–8 (`background_jobs`, `image_analyses`, `claim_decisions`, `notifications`) уже есть в схеме.
