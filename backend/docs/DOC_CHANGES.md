# Правки в «Документация и ТЗ» и «Детализация требований» — только бэкенд

Сверено с кодом на 17.10.2026 (миграции V1–V5, `server`, `core/api`). Разделы про интерфейсы (4, 5) и ML-модель — не здесь.
Решено командой 17.10: клиент — приложение, оператор — веб, оба на Kotlin Multiplatform; бэкенд под это уже готов.

Формат: **было** — дословно из документа, **стало** — на что заменить.

Пометки в столбце «Стало»:
- «сделано» — так уже работает бэкенд;
- «этап N» — запланировано (план — в `docs/BACKEND_STRUCTURE.md`).

---

## 0. Решить командой до правок

| # | Вопрос | Что в документах | Что в бэкенде | Рекомендация |
|---|---|---|---|---|
| 1 | Формат ошибок | RFC 7807 (`type`, `title`, `status`, `detail`, `instance`) | `{"error": "КОД", "message": "текст"}` + HTTP-статус | Либо исправить документ (ниже), либо перевести бэкенд на RFC 7807 — это доработка примерно на час, коды ошибок сохранятся в `type`. Решить до того, как фронтенд начнёт разбирать ошибки |
| 2 | Идентификаторы | UUID v4 для всех сущностей | Числовые `bigint`, номер обращения `TS-0000001` | Оставить числа. Защита от подстановки чужого id — проверка владельца (чужое → 404), а не случайный id. Переход на UUID — смена всей схемы без пользы |

---

## Документ 1. «ДОКУМЕНТАЦИЯ И ТЗ»

### 1.2 Границы системы

| Было | Стало |
|---|---|
| Загрузка и валидация файлов (JPEG, PNG, PDF) | Загрузка и проверка файлов по содержимому (JPEG, PNG, PDF), удаление метаданных EXIF/геолокации, поиск дублей (SHA-256 и перцептивный хэш) |
| Уведомления клиенту (in-app, email-имитация) | Уведомления клиенту: центр уведомлений в приложении (in-app) + имитация SMS / email / push |
| Обезличивание лиц/номеров (опционально) | Автоматическое обезличивание лиц/номеров в системе. Демо-изображения обезличиваются заранее (п. 6.6) |

### 1.3 Участники и роли

| Было | Стало |
|---|---|
| Администратор: … управление справочниками, просмотр метрик | Администратор: Все действия оператора + переназначение, изменение приоритета, исправление ошибочного статуса (только с причиной), повторное открытие, просмотр метрик. Справочники (типы случаев, поля, документы) меняются миграциями БД, в интерфейсе не редактируются |
| Система: Проверка формата/комплектности, постановка в очередь, ML-анализ, повтор задач, отправка уведомлений | Система: Проверка формата файлов при загрузке, проверка комплектности при отправке, ML-анализ в фоне, маршрутизация, автоназначение оператора, контроль сроков в статусах, повтор задач, уведомления |

### 1.4 Типы страховых случаев

| Было | Стало |
|---|---|
| Базовый тип (обязательный): Повреждение автомобиля в результате ДТП. | Базовый тип (обязательный): Повреждение автомобиля в результате ДТП (`CAR_ACCIDENT`). Дополнительно реализованы: повреждение автомобиля без ДТП (`CAR_DAMAGE`), повреждение стекла (`CAR_GLASS`) |
| …через справочник правил (тип случая → набор обязательных полей + перечень документов + маршрут обработки) | …через справочники: `event_types` (типы), `event_type_fields` (обязательные и дополнительные поля), `required_documents` (документы и минимальное количество). Маршрут — правила маршрутизации (этап 4). Форма для клиента строится по `GET /api/mobile/event-types` |

### 1.5 Архитектурный контекст (схема)

| Было | Стало |
|---|---|
| API Gateway / BFF │ Аутентификация (JWT), маршрутизация | *(блок убрать)* Отдельного шлюза нет: JWT проверяет сам сервер. Два канала: `/api/mobile` — клиент, `/api/operator` — оператор и администратор |
| Main Service (Java/.NET/Go) - Presentation - Domain - Repository | Main Service (Kotlin + Ktor 3): Routes → Services → Exposed (доступ к БД) |
| Notification Svc (NodeMailer/Mock) - Email mock - In-app notif. | Уведомления — модуль основного сервиса: запись создаётся в той же транзакции, что смена статуса; доставку (имитация SMS/email/push) выполняет фоновый воркер (этап 7) |
| ML Image Service (Python) | ML Image Service (Python). Вызывается фоновым воркером по HTTP (контракт — `docs/ML_CONTRACT.md`) |
| Message Queue (RabbitMQ / Kafka / Redis Streams) │ Очередь обращений │ ML-задачи │ Уведомления │ Retry-задачи | Очередь фоновых задач — таблица `background_jobs` в PostgreSQL: выборка `FOR UPDATE SKIP LOCKED`, повторы с паузой. Задача создаётся в одной транзакции с файлом или обращением и поэтому не теряется (AC-05). Для 5–10 обращений в минуту брокер не нужен |
| База данных (PostgreSQL) │ Object Storage (S3) │ claims, documents, users, audit_log │ Файлы вложений | PostgreSQL 16 │ Файлы — на диске сервера (интерфейс `FileStorage`), S3-совместимое хранилище — этап 9 |

### 1.5 Стек технологий

| Было | Стало |
|---|---|
| Backend: Java (Spring Boot) или .NET 8, или Go | Backend: Kotlin 2.4 + Ktor 3 (JDK 21), Exposed, Flyway (миграции), HikariCP |
| База данных: PostgreSQL 15+ | База данных: PostgreSQL 16 |
| Object Storage: MinIO (S3-совместимое) | Хранилище файлов: диск сервера; S3-совместимое — этап 9. Образ MinIO из Docker Hub скачать не удалось, поэтому выбрать другое S3-совместимое хранилище |
| Очередь: RabbitMQ или Redis Streams | Очередь: таблица `background_jobs` в PostgreSQL |
| Нотификации: NodeMailer (mock-режим) / In-app | Уведомления: in-app (таблица `notifications`) + имитация SMS / email / push |

### 2.1 Состав команды

| Было | Стало |
|---|---|
| Backend-разработчик: Main Service (API, Domain, Repository), Message Queue интеграция, Notification Service, Object Storage интеграция, seed тестовых данных | Backend-разработчик: Main Service (API, сервисы, доступ к БД), очередь фоновых задач в PostgreSQL и воркер, модуль уведомлений, хранилище файлов, демо-данные, Docker Compose |

### 2.2.3 Backend-разработчик (таблица модулей)

| Было (модуль — задача) | Стало |
|---|---|
| REST API — Claims: CRUD обращений, статусные переходы, валидация по типу случая | Черновик, редактирование, отправка, проверка комплектности (`GET …/completeness`), правила формы по типу (`GET /event-types`) — **сделано**. Действия оператора со статусами — этап 6 |
| REST API — Documents: Загрузка файлов, хранение в S3, проверка формата/размера, checksum дублей | Загрузка, проверка по содержимому, удаление EXIF, дубли (SHA-256 + перцептивный хэш), замена отклонённого файла, хранение на диске — **сделано**. S3 — этап 9 |
| REST API — Queue: Список с фильтрами, захват (lock), освобождение, переназначение | Список с фильтрами — **сделано**. Автоназначение, «Взять в работу», освобождение, переназначение — этап 5 |
| REST API — Audit: Запись и чтение журнала изменений | Запись в `claim_status_history`, `audit_log`, `claim_decisions` — **сделано**. Чтение: история и решения в карточке оператора — **сделано**, отдельный `GET …/audit` — этап 6 |
| REST API — Notifications: Формирование и хранение in-app уведомлений, mock email | Этап 7 |
| Конечный автомат (enforcement): Проверка допустимости перехода на уровне Domain-сервиса | Проверка в `ClaimStatusService` и триггером БД (двойная защита) — **сделано** |
| Message Queue — ML-задачи: Публикация задачи после загрузки файла, обработка результата, retry с backoff | Задача `ANALYZE_IMAGE` ставится при загрузке фото — **сделано**. Воркер, обработка результата, повторы — этап 3 |
| Message Queue — уведомления: Асинхронная отправка нотификаций | Фоновая доставка уведомлений через `background_jobs` — этап 7 |
| Object Storage интеграция: Загрузка/скачивание через presigned URL (TTL 15 мин) | Скачивание через API с проверкой прав (`GET …/files/{fileId}/content`, без кэширования) — **сделано**. Presigned URL — при переходе на S3 (этап 9) |
| Справочники / Seed: Типы случаев, правила, тестовые договоры и пользователи | **Сделано**: справочники в миграциях; вымышленные демо-данные — 3 клиента, 2 оператора, администратор, 6 обращений (только в режиме dev) |
| Тайм-ауты в статусах: Хранение status_entered_at, подсветка просроченных на уровне API | **Сделано**: `status_changed_at` + срок статуса `claim_statuses.sla_hours`; в API — `dueAt`, `isOverdue`, фильтр `overdue=true` |
| *(строки нет — добавить)* | Авторизация: вход по телефону (клиент) и email (оператор), JWT + одноразовый refresh-токен, отзыв сессии при выходе, защита от перебора паролей — P0, **сделано** |

### 2.3 Критический путь

| Было | Стало |
|---|---|
| [SA/ML] OpenAPI-контракт ├──► [BE] Реализация endpoints | [BE] Реализация endpoints + общие модели (`core/api`) + коллекция Postman ├──► [BE] OpenAPI-описание из кода (этап 9) ├──► [FE] |
| [SA/ML] ML Image Service (API-контракт: POST /analyze) └──► [BE] Message Queue → ML-задача → получение результата | [SA/ML] ML Image Service (контракт — `docs/ML_CONTRACT.md`, `POST /v1/analyze`) └──► [BE] фоновый воркер (`background_jobs`) → ML-задача → результат в `image_analyses` |

---

## Документ 2. «ДЕТАЛИЗАЦИЯ ТРЕБОВАНИЙ»

### 3.1 Общие принципы

| Параметр | Было | Стало |
|---|---|---|
| Архитектурный стиль | Layered (Presentation → Domain → Repository) | Layered: Routes → Services → Exposed (таблицы БД) |
| Протокол | REST over HTTPS, JSON | REST, JSON. HTTPS — на прокси при развёртывании (локально HTTP) |
| Аутентификация | JWT Bearer; тестовые пользователи создаются через seed | JWT Bearer (HS256); у клиента и оператора разные аудитории токена. Access — 15 мин, refresh — одноразовый, меняется при каждом обновлении. Тестовые пользователи — seed в режиме dev |
| Авторизация | Role-based: CLIENT / OPERATOR / ADMIN; проверка на каждый endpoint | Role-based: CLIENT / OPERATOR / ADMIN; проверка на каждый endpoint. Клиент видит только свои обращения: чужое → 404 |
| Идентификаторы | UUID v4 для всех сущностей | Числовые id; номер обращения `TS-0000001`. UUID — только для `clientRequestId` (защита от повторной отправки) и имён файлов в хранилище |
| Временны́е метки | ISO 8601 UTC во всех полях | ISO 8601 с часовым поясом (сервер отдаёт UTC). Фильтр очереди по датам принимает пояс оператора `tz` |
| Пагинация | cursor-based или offset/limit; поле total обязательно | offset/limit (limit 1–200, по умолчанию 50), поле `total` есть |
| Ошибки | RFC 7807 Problem Details: type, title, status, detail, instance | `{"error": "КОД", "message": "текст для пользователя"}` + HTTP-статус. Коды: `VALIDATION_ERROR`, `UNAUTHORIZED`, `NOT_FOUND`, `CONFLICT`, `ILLEGAL_TRANSITION`, `FILE_TOO_LARGE`, `FILES_LIMIT`, `TOO_MANY_ATTEMPTS`, `RULE_VIOLATION`… (или RFC 7807 — см. вопрос 0.1) |

### 3.2.1 Перечень статусов — заменить таблицу целиком

Статусы и видимость клиенту — по ответам заказчика на вопросы 12 и 31–33.

| Статус | Код | Видим клиенту | Описание |
|---|---|---|---|
| Черновик | `DRAFT` | Да | Создано, не отправлено |
| Отправлено | `SUBMITTED` | Да | Клиент нажал «Отправить»; сразу проверяется комплектность |
| Проверяем материалы | `IN_ANALYSIS` (вместо `VALIDATION`) | Да | Комплект полный, идёт фоновый анализ фото |
| Нужны дополнительные материалы | `NEEDS_INFO` (вместо `PENDING_INFO`) | Да | Возвращено клиенту со списком, что добавить; срок — 7 дней |
| Ожидает рассмотрения | `QUEUED` | Нет | В очереди, оператор не назначен |
| Назначен специалист | `ASSIGNED` (новый) | Нет | Назначен оператор (автоназначение или «Взять в работу»), рассмотрение не начато |
| На рассмотрении | `IN_REVIEW` | Да | Оператор рассматривает |
| Рассмотрение завершено | `COMPLETED` (вместо `RESOLVED`) | Да | Оператор зафиксировал итог |
| Закрыто без рассмотрения | `CANCELLED` (вместо `CLOSED`) | Да | Отозвано клиентом или закрыто сотрудником с причиной |

### 3.2.2 Допустимые переходы — заменить таблицу целиком

| Из | В | Инициатор | Условие |
|---|---|---|---|
| DRAFT | SUBMITTED | CLIENT | Клиент отправил черновик |
| DRAFT | CANCELLED | CLIENT | Клиент отозвал черновик |
| SUBMITTED | NEEDS_INFO | SYSTEM | Не хватает полей или документов, есть отклонённые файлы |
| SUBMITTED | IN_ANALYSIS | SYSTEM | Комплект полный |
| IN_ANALYSIS | NEEDS_INFO | SYSTEM | Анализ: фото непригодно — нужно переснять |
| IN_ANALYSIS | QUEUED | SYSTEM, ADMIN | Анализ завершён или истёк срок ожидания ML |
| NEEDS_INFO | SUBMITTED | CLIENT | Клиент добавил запрошенное и отправил повторно |
| QUEUED | ASSIGNED | SYSTEM, OPERATOR, ADMIN | Автоназначение / «Взять в работу» / назначение администратором |
| ASSIGNED | IN_REVIEW | OPERATOR | Оператор начал рассмотрение |
| ASSIGNED | QUEUED | OPERATOR, ADMIN | Отказ от обращения / снятие назначения |
| IN_REVIEW | NEEDS_INFO | OPERATOR | Запрос дополнительных материалов |
| IN_REVIEW | QUEUED | OPERATOR, ADMIN | Освобождение без завершения |
| IN_REVIEW | COMPLETED | OPERATOR | Завершение рассмотрения |
| NEEDS_INFO | CANCELLED | CLIENT, SYSTEM, OPERATOR, ADMIN | Клиент отозвал / истёк срок 7 дней / закрытие сотрудником |
| QUEUED, ASSIGNED, IN_REVIEW | CANCELLED | OPERATOR, ADMIN | Закрытие без рассмотрения (дубль, ошибка) |
| IN_ANALYSIS | CANCELLED | ADMIN | Закрытие без рассмотрения |
| NEEDS_INFO | QUEUED | ADMIN | Исправление ошибочного статуса |
| COMPLETED | IN_REVIEW | ADMIN | Повторное открытие |
| CANCELLED | QUEUED | ADMIN | Исправление ошибочного закрытия |

Причина обязательна для любого перехода администратора и для закрытия сотрудником или системой. Это проверяет БД.

| Было | Стало |
|---|---|
| Инвариант: … Попытка недопустимого перехода возвращает HTTP 409 с type: "claim/invalid-transition". | Инвариант: переходы, не перечисленные в таблице, запрещены. Проверку выполняют сервис и триггер БД. Недопустимый переход → HTTP 409, `"error": "ILLEGAL_TRANSITION"` |

### 3.2.3 События журнала

| Было | Стало |
|---|---|
| При каждом переходе создаётся запись в audit_log | Переход статуса пишется в `claim_status_history`: `from_status`, `to_status`, `actor_type`, `actor_id`, `reason` (внутренняя причина), `client_comment` (текст для клиента), `visible_to_client`, `created_at`. Остальные изменения (поля обращения, файлы, назначение) — в `audit_log`. Решения системы и исправления оператора — в `claim_decisions` |
| "id": "uuid", "entity_id": "uuid", "actor_id": "uuid" | Числовые id |
| "action": "STATUS_TRANSITION" | Переходы — в `claim_status_history`; в `audit_log` действия `UPDATE`, `FILE_ADD`, `FILE_REPLACE`, `FILE_REJECTED`, `FILE_DUPLICATE`, `ASSIGN`… |
| "actor_role": "OPERATOR" | "actor_type": "OPERATOR" (значения: SYSTEM, CLIENT, OPERATOR, ADMIN) |
| "old_value": "QUEUED", "new_value": "IN_REVIEW" | `old_value` / `new_value` — JSON: изменённые поля со старыми и новыми значениями |
| comment обязателен при действиях: ML_OVERRIDE, PRIORITY_CHANGE, ROUTING_CHANGE, CLOSED, REOPENED | Причина обязательна (проверяет БД): у ручного исправления решения (`claim_decisions`, action `OVERRIDE` — ML, направление, приоритет), при закрытии сотрудником (`CANCELLED`), при любом переходе администратора (в т.ч. повторное открытие) |

### 3.3.1 Claims API — заменить таблицу

| Метод | Endpoint | Роль | Описание | Готовность |
|---|---|---|---|---|
| GET | `/api/mobile/event-types` | CLIENT | Типы случаев: поля и документы для формы | сделано |
| GET | `/api/mobile/policies` | CLIENT | Договоры клиента | сделано |
| POST | `/api/mobile/claims` | CLIENT | Создать черновик | сделано |
| GET | `/api/mobile/claims` | CLIENT | Мои обращения | сделано |
| GET | `/api/mobile/claims/{id}` | CLIENT | Обращение (своё) | сделано |
| PATCH | `/api/mobile/claims/{id}` | CLIENT | Изменить: в DRAFT — любые поля, в NEEDS_INFO — только запрошенные | сделано |
| GET | `/api/mobile/claims/{id}/completeness` | CLIENT | Чего не хватает до отправки | сделано |
| POST | `/api/mobile/claims/{id}/submit` | CLIENT | Отправить (DRAFT или NEEDS_INFO → SUBMITTED) | сделано |
| GET | `/api/mobile/claims/{id}/timeline` | CLIENT | Временная шкала для клиента | сделано |
| GET | `/api/operator/queue` | OPERATOR, ADMIN | Очередь (п. 3.3.3) | сделано |
| GET | `/api/operator/claims/{id}` | OPERATOR, ADMIN | Карточка: данные, файлы с анализом, решения, история | сделано |
| POST | `/api/operator/claims/{id}/start-review`, `/request-info`, `/complete`, `/cancel`, `/reopen` | OPERATOR, ADMIN | Действия со статусом — у каждого своё тело и проверки | этап 6 |
| GET | `/api/operator/claims/{id}/audit` | OPERATOR, ADMIN | Полный журнал | этап 6 |

| Было | Стало |
|---|---|
| POST /api/v1/claims/{id}/transitions — Выполнить переход статуса | Общего endpoint переходов нет. Каждое действие — отдельный endpoint (строка «этап 6» выше): у запроса материалов свой список, у завершения — итог. Фронтенду не нужно знать граф переходов |

Тело `POST /api/mobile/claims` — заменить пример:

| Было | Стало |
|---|---|
| "incident_type_id": "uuid" | "eventType": "CAR_ACCIDENT" |
| "contract_id": "uuid" | "policyId": 1 |
| "incident_date": "2026-10-15", "incident_time": "14:30" | "eventAt": "2026-10-15T14:30:00+03:00" |
| "location": … | "eventLocation": … |
| "description": … | "description": … *(без изменений)* |
| "contact_channel": "EMAIL", "contact_value": "test@example.com" | "contactPhone": "+79001234567" *(если не указан — телефон клиента)* |
| "vehicle": { "plate", "make", "model", "year" } | *(убрать)* Сведения об автомобиле клиента берутся из договора (`policies.insured_object`) |
| "participants": [ { "role": "SECOND_PARTY", "name": …, "contact": … } ] | "participants": [ { "role": "OTHER_DRIVER", "fullName": …, "phone": …, "vehicle": {…}, "insurer": …, "note": … } ]; роли: `APPLICANT_DRIVER`, `OTHER_DRIVER`, `VICTIM`, `WITNESS` |
| *(нет)* | "details": { "circumstances": "…", "policeCalled": false } — дополнительные поля типа из `GET /event-types` |
| *(нет)* | "clientRequestId": "uuid" — повтор запроса при плохой сети не создаст второе обращение |

### 3.3.2 Documents API

| Было | Стало |
|---|---|
| POST /api/v1/claims/{id}/documents — Загрузить файл (multipart/form-data) | `POST /api/mobile/claims/{id}/files` — multipart: `documentType`, `file`, необязательные `clientRequestId`, `replacesFileId`. Ответ: 201 принят / 200 дубль или повтор / 422 отклонён (`rejectionReason`, `hint`) / 413 — **сделано** |
| GET /api/v1/claims/{id}/documents — Список документов | Список файлов входит в `GET /api/mobile/claims/{id}` и в карточку оператора — **сделано** |
| GET …/documents/{doc_id}/download — OPERATOR, ADMIN — presigned URL | `GET /api/mobile/claims/{id}/files/{fileId}/content` (владелец) и `GET /api/operator/claims/{id}/files/{fileId}/content` (оператор) — файл отдаётся сервером с проверкой прав — **сделано** |
| DELETE …/documents/{doc_id} — CLIENT (только DRAFT/PENDING_INFO) | `DELETE /api/mobile/claims/{id}/files/{fileId}` — только в черновике (DRAFT): запись остаётся в журнале со статусом `DELETED`, содержимое удаляется. После отправки файл заменяют через `replacesFileId` — **сделано** |
| Максимальный размер файла: 15 MB | Размер — по типу документа (`document_types.max_size_bytes`): фото и извещение — 20 МБ, удостоверение и СТС — 10 МБ (или привести к 15 МБ — см. «Что поменять в бэкенде») |
| Максимум файлов на обращение: 20 — HTTP 422 | Максимум 15 файлов и 100 МБ на обращение — HTTP 409, `"error": "FILES_LIMIT"` |
| Файл читаем (не повреждён) — HTTP 422 | + изображение декодируется целиком, слишком большое по пикселям отклоняется, PDF со скриптами отклоняется — HTTP 422 с подсказкой |
| SHA-256 дубль в рамках того же обращения — HTTP 409 с type: "document/duplicate" | SHA-256 дубль в том же обращении — HTTP 200, файл со статусом `DUPLICATE` и ссылкой на оригинал. Повтор при плохой сети не должен выглядеть ошибкой. То же фото в другом обращении (в т.ч. пересжатое) — пометка для оператора |
| *(нет)* | Из фото удаляются EXIF и геолокация, ориентация снимка сохраняется |
| После успешного сохранения файла в S3 → публикация задачи ML_ANALYZE в очередь | После сохранения фото → задача `ANALYZE_IMAGE` в `background_jobs` (в той же транзакции). Загрузка не ждёт ML |

### 3.3.3 Queue API

| Было | Стало |
|---|---|
| GET /api/v1/queue | `GET /api/operator/queue` — **сделано** |
| POST /api/v1/queue/{id}/acquire — Захватить обращение (оптимистичная блокировка) | `POST /api/operator/claims/{id}/take` («Взять в работу», QUEUED → ASSIGNED), `POST /api/operator/claims/{id}/start-review` (ASSIGNED → IN_REVIEW). Вместо «взять следующее» — автоназначение: оператор на смене (`PUT /api/operator/me/availability`) сразу получает обращения своего направления с учётом нагрузки — **сделано** |
| POST /api/v1/queue/{id}/release | `POST /api/operator/claims/{id}/release` `{"reason"}` — **сделано** |
| POST /api/v1/queue/{id}/reassign — ADMIN | `POST /api/operator/claims/{id}/assign` `{"operatorId","reason"}` — ADMIN: назначить из очереди или передать другому оператору; список операторов с нагрузкой — `GET /api/operator/operators` — **сделано** |
| priority — enum[] — NORMAL, HIGH, CRITICAL | `minPriority` — число 1–3 (1 обычный, 2 повышенный, 3 высокий) |
| incident_type — uuid | `eventType` — код (`CAR_ACCIDENT`) |
| assigned_to — uuid / me | `assigned=me` \| `none`, `assignedTo={id}` |
| created_from / created_to — date | `createdFrom` / `createdTo` — дата + `tz` (пояс оператора, например `Asia/Novosibirsk`) |
| overdue — boolean | `overdue=true` |
| q — string | `search` — номер обращения, номер договора или имя клиента |
| limit — 10–100, default 20 / cursor | `limit` 1–200, по умолчанию 50 / `offset` |
| *(нет)* | `status` (можно несколько), `direction` (`STANDARD` / `MANUAL_REVIEW`), `manualReview=true` |
| UPDATE claims SET assigned_to = …, locked_at = NOW() WHERE id = … AND assigned_to IS NULL AND status = 'QUEUED' — 0 rows → 409 "claim/already-acquired" | Строка обращения блокируется (`FOR UPDATE`), второй запрос видит, что обращение уже взято → 409 `"error": "CONFLICT"`; автоназначение берёт обращения с `FOR UPDATE SKIP LOCKED`. Тест на два одновременных запроса есть — **сделано** |

### 3.3.4 ML Override API

| Было | Стало |
|---|---|
| POST /api/v1/claims/{id}/ml-result/override | `POST /api/operator/claims/{id}/decisions/override` и `/decisions/confirm` — **сделано** |
| Тело: "damage_type", "quality_acceptable", "comment" | Тело: `{"kind": "DAMAGE", "damageType": "DENT", "reason": "…"}`; так же исправляются направление (`"kind": "ROUTING", "direction"`) и приоритет (`"kind": "PRIORITY", "priority"`). `reason` обязателен. Подтверждение: `{"kind": "DAMAGE"}` без причины |
| Ответ включает original_result и override_result — оба сохраняются | Исходное решение системы не меняется. Исправление — новая запись в `claim_decisions` (action `OVERRIDE`, ссылка `supersedes_id` на исходную), результат модели остаётся в `image_analyses`. В карточке видны оба |

### 3.3.5 Notifications API

| Было | Стало |
|---|---|
| GET /api/v1/notifications — CLIENT, OPERATOR, ADMIN | `GET /api/mobile/notifications` (клиент), `GET /api/operator/notifications` (сотрудники) — этап 7 |
| PATCH /api/v1/notifications/{id}/read | `POST …/notifications/{id}/read` — этап 7 |
| Триггеры: SUBMITTED, PENDING_INFO, QUEUED (после PENDING_INFO), IN_REVIEW, RESOLVED | Триггеры: `SUBMITTED`, `NEEDS_INFO`, повторная отправка после запроса («Получены дополнительные материалы»), `IN_REVIEW`, `COMPLETED`, `CANCELLED`. Клиент не видит `QUEUED`, поэтому уведомление «материалы получены» отправляется при повторной отправке |
| «По обращению №{number} требуются дополнительные материалы: {reason}» | …: `{client_comment}` — текст для клиента; внутренняя причина клиенту не показывается |

### 3.4 Модель данных — заменить SQL ссылкой и таблицей соответствия

| Было | Стало |
|---|---|
| Весь блок SQL `CREATE TABLE …` | Схема — в миграциях `server/src/main/resources/db/migration` (V1–V5), описание — `docs/BACKEND_STRUCTURE.md`. Таблица соответствия — ниже |

| В документе | В бэкенде | Отличия |
|---|---|---|
| `users` (username) | `users` | Вход: клиент — по `phone`, сотрудники — по `email`. Есть `is_active`, `max_active_claims`, `availability` (для автоназначения) |
| `incident_types` (required_fields / required_doc_types / routing_rules — JSONB) | `event_types` + `event_type_fields` + `required_documents` + `document_types` | Правила — отдельными строками, а не JSON: проще проверять и менять |
| `contracts` | `policies` | `product` (OSAGO/KASKO/PROPERTY), `insured_object` — данные ТС |
| `claims` | `claims` | `claim_number` `TS-0000001`; `priority` 1–3; `direction_code` STANDARD / MANUAL_REVIEW; `assigned_operator_id`; `event_at` (дата и время одним полем); `event_details` JSONB; `contact_phone`; `status_changed_at` (= `status_entered_at`); `requires_manual_review`; `duplicate_of_claim_id`; `version` |
| *(нет)* | `claim_participants` | Участники события |
| `documents` | `claim_files` | + `status` (ACCEPTED / REJECTED / DUPLICATE / REPLACED), `rejection_reason`, `phash`, `analysis_status`, `replaced_by_file_id`, `duplicate_of_file_id`, `client_request_id` |
| `ml_results` (+ поля override_*) | `image_analyses` + `claim_decisions` | Результат модели не изменяется; исправления — отдельные записи в `claim_decisions` |
| `audit_log` | `audit_log` + `claim_status_history` | См. 3.2.3 |
| `notifications` (is_read) | `notifications` | `read_at`, `sent_at`, `channel` (IN_APP / PUSH / EMAIL / SMS) |
| *(нет)* | `info_requests`, `info_request_items` | Запрос материалов: что именно нужно (поле, документ, замена файла), подсказка, срок |
| *(нет)* | `auth_sessions`, `user_devices` | Сессии (хэш refresh-токена), устройства |
| *(нет)* | `background_jobs` | Очередь фоновых задач |
| *(нет)* | `claim_statuses`, `status_transitions`, `directions`, `operator_directions` | Справочники автомата и направлений |

### 6. ML-модуль — со стороны бэкенда

| Было | Стало |
|---|---|
| 6.1: 1. Загрузка файла из S3 по storage_key | Бэкенд передаёт само изображение в запросе (multipart): ML-сервису не нужен доступ к хранилищу, работает и с диском, и с S3 |
| 6.2 Request: "document_id", "claim_id", "storage_key", "model_version" | `image` (файл), `requestId` (`ANALYZE_IMAGE:file:42`), `documentType`, `eventType`. Данные клиента в ML не передаются |
| 6.2: Response (422) — неприемлемое качество | Предложение бэкенда: плохое качество — 200 с `"acceptable": false` (это результат анализа, а не ошибка); 422 — изображение не удалось прочитать. Если ML оставит 422 с телом — бэкенд обработает и так |
| 6.4: Все фото OK, damage_confidence ≥ threshold → AUTO | → `STANDARD` (обычная очередь) |
| 6.4: quality_acceptable = false / документ отсутствует → ADDITIONAL_DOCS | → статус `NEEDS_INFO` с запросом материалов (отдельного направления нет) |
| 6.4: Порог уверенности threshold: настраиваемый параметр в конфигурации сервиса | …в конфигурации бэкенда (`routing.confidenceThreshold` в `application.yaml`), чтобы менять без новой версии модели |
| 6.5: Статус PENDING_RETRY / ML_FAILED | `claim_files.analysis_status`: `PENDING` (в т.ч. ожидание повтора) / `DONE` / `FAILED` |

### 10.2 Безопасность

| Было | Стало |
|---|---|
| JWT (HS256 / RS256); время жизни access token — 1 час, refresh — 24 часа | JWT HS256; access — 15 мин; refresh одноразовый: телефон — 60 дней, веб оператора — 12 часов. После выхода, обновления пары или блокировки пользователя токен перестаёт действовать сразу |
| *(нет)* | Защита от перебора: 5 неудачных входов за 15 мин → блокировка логина на 5 мин (429); не больше 30 запросов в минуту к `/auth` с одного адреса |
| Доступ к файлам S3: Только через presigned URL (TTL 15 мин); прямого доступа нет | Файлы отдаёт сервер после проверки прав (`Cache-Control: no-store`, `nosniff`); прямого доступа к хранилищу нет |
| *(нет)* | Ограничения размера запроса: JSON — 256 КБ, загрузка — 21 МБ; секреты — только из переменных окружения, в prod без `JWT_SECRET` сервер не запускается; ошибки БД наружу не отдаются |

### 11. Глоссарий

| Было | Стало |
|---|---|
| Маршрутизация … AUTO, MANUAL_REVIEW, ADDITIONAL_DOCS | Маршрутизация — автоматическое определение направления обработки: `STANDARD`, `MANUAL_REVIEW`; при нехватке материалов — запрос клиенту (`NEEDS_INFO`) |
| Presigned URL — Временная ссылка для прямого доступа к файлу в S3 … | *(оставить с пометкой «после перехода на S3, этап 9»)* |
| *(добавить)* | Перцептивный хэш — «отпечаток» изображения, который почти не меняется при пересжатии и уменьшении; по нему находится то же фото в другом обращении |
| *(добавить)* | Идемпотентность (`clientRequestId`) — повтор того же запроса не создаёт второй объект |

---

## Что в документах разумнее, чем у нас, — поменять бэкенд (не сделано, жду решения)

| Что | В документе | Сейчас в бэкенде | Предложение |
|---|---|---|---|
| Повторы ML | 10 с таймаут, 3 попытки, паузы 30 / 90 / 270 с | В черновике контракта — 15 с и 5 попыток | Принять как в документе (шаг 3). Черновик `ML_CONTRACT.md` уже приведён к этому |
| Классы повреждений | SCRATCH, DENT, BROKEN_GLASS, BUMPER_DAMAGE, HEADLIGHT_DAMAGE | Свой список в черновике контракта | Принять как в документе (это зона ML). Черновик уже приведён |
| Проблемы качества | BLURRY, LOW_LIGHT, OVEREXPOSED, LOW_RESOLUTION, NO_DAMAGE_VISIBLE | Свой список | Принять как в документе. Черновик уже приведён |
| Размер файла | 15 МБ | 20 МБ (10 МБ у удостоверения и СТС) | Решить командой: либо 15 МБ в бэкенде (новая миграция), либо 20 МБ в документе |
| Файлов на обращение | 20 | 15 | То же |
| Удаление документа | В DRAFT/PENDING_INFO | В черновике — удаление (пометка `DELETED`), после отправки — замена | Сделано; в документе уточнить: после отправки удаления нет, только замена по запросу |
| Формат ошибок | RFC 7807 | Свой | См. вопрос 0.2 |
