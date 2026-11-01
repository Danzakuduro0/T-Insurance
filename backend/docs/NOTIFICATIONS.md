# Уведомления — шаг 7

Уведомления создаются в ClaimStatusService в той же транзакции, что статус и история. При откате не остаются ни уведомления, ни задачи доставки. Скрытые переходы не создают уведомлений. Тексты берутся только из clientTitle/clientComment и клиентского названия статуса; внутренний reason не передаётся.

## Политика

NotificationPolicy отдельно задаёт события и каналы:

| Статус | Каналы |
| --- | --- |
| SUBMITTED (включая повторную отправку) | IN_APP, PUSH |
| NEEDS_INFO | IN_APP, PUSH, SMS |
| IN_REVIEW, COMPLETED, CANCELLED | IN_APP, PUSH |

DRAFT, IN_ANALYSIS, QUEUED, ASSIGNED не создают уведомлений. Временная шкала остаётся независимой. Настройки предпочтений клиента не входят в этот этап.

## API клиента

JWT мобильного канала обязателен. DTO находятся в core/api/mobile/Notifications.kt; пути — MobileApi.

- GET /api/mobile/notifications?limit=20&beforeId=123&unreadOnly=false — только IN_APP текущего пользователя. limit: 1–100; beforeId: положительное целое; unreadOnly: true/false. Порядок id по убыванию. Ответ: items и nextBeforeId (null — конец). Для следующей страницы передать nextBeforeId в beforeId.
- POST /api/mobile/notifications/{id}/read — 200 с NotificationDto. Повтор сохраняет прежний readAt. Чужой, служебный или отсутствующий id — 404. Чужой канал JWT — 401. Некорректные параметры — 400.

Поля NotificationDto: id, claimId, type, title, body, createdAt, readAt. Даты — ISO 8601, readAt=null означает непрочитанное. Для перехода в карточку использовать claimId.

## Доставка

IN_APP доступен сразу. Для PUSH/SMS ставится SEND_NOTIFICATION с notificationId в той же транзакции. SendNotificationHandler вызывает NotificationSender вне транзакции БД. В приложении установлен только SimulatedNotificationSender: он пишет id и канал в лог, не отправляет сообщений людям и не пишет тексты/телефоны в лог. sent_at означает успешную имитацию, а не реальную доставку.

При временной ошибке — до 5 запусков с задержкой 30 × номер попытки секунд. После исчерпания задача FAILED, sent_at остаётся null; IN_APP остаётся доступным. Состояние доставки диагностируется через background_jobs. Обработанная запись с sent_at не отправляется повторно. Между внешней отправкой и сохранением sent_at возможен повтор после сбоя: будущий провайдер должен использовать notificationId как ключ идемпотентности. Гарантии exactly-once нет.

Новые миграции и секреты не нужны: используются существующие notifications и background_jobs. Старые события задним числом не рассылаются. Запуск воркера управляется jobs.enabled.

## Архитектура и проверка

Пакет notifications: NotificationPolicy, StatusNotifications, NotificationRepository, NotificationService, NotificationRoutes, SendNotificationHandler и адаптер NotificationSender. Сборка компонентов — AppContainer. Проверки — NotificationsTest и полный :server:test.

Postman: войти клиентом, отправить обращение, открыть папку «7. Уведомления». Список сохраняет notificationId; следующий запрос отмечает его прочитанным. При пустом списке запрос прочтения пропускается.
