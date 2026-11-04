# Администрирование — шаг 8

GET /api/operator/dashboard требует JWT веб-канала с ролью ADMIN. Обычному оператору — 403; без входа или с мобильным JWT — 401. Ответ 200: DashboardDto из core/api/operator/Dashboard.kt. Путь задан в OperatorApi.DASHBOARD.

## Содержимое

- generatedAt: время формирования, ISO 8601.
- queues: directionCode, queued, queuedUrgent (приоритет 3), overdue, oldestQueuedAt, overloadWarning. Учитывается только QUEUED; срок определяется SLA текущего статуса и status_changed_at. Направления без очереди возвращаются с нулями и oldestQueuedAt=null.
- operators: существующий OperatorDto — активные сотрудники OPERATOR, смена, направления, activeClaims (ASSIGNED/IN_REVIEW) и maxActiveClaims. Неактивные пользователи и администраторы сюда не входят. При отсутствии индивидуального лимита используется assignment.defaultMaxActiveClaims.
- metrics: показатели за всё время по обращениям с submitted_at. Время — в секундах, доля — от 0 до 1. Периодический фильтр в этот этап не входит.
- overloadWarning: есть хотя бы одно перегруженное направление.
- recommendations: code, directionCode, message. Это подсказки; dashboard не меняет приоритеты, сотрудников и статусы.
- queuedThreshold / overdueThreshold: применённые пороги.

## Формулы

submittedClaims — число отправленных обращений, включая отменённые. Черновики без submitted_at исключены.

averageFirstAssignmentSeconds — среднее время от submitted_at до первого ASSIGNED в истории после отправки. Повторное назначение не сдвигает начало. assignedSamples — число обращений с такой записью. Старые записи без истории назначения исключены из среднего.

averageCompletionSeconds — среднее closed_at − submitted_at для текущих COMPLETED, с корректными датами. Отменённые и повторно открытые обращения исключены. completedSamples — размер выборки. Паузы ожидания материалов входят в общее время.

needsInfoClaims — количество отправленных обращений, которые хотя бы однажды были в NEEDS_INFO. Повторные запросы не удваивают число. needsInfoShare = needsInfoClaims / submittedClaims.

При пустой выборке средние и доля равны null, счётчики — 0. Все части ответа читаются в одной read-only транзакции REPEATABLE READ.

## Перегрузка и рекомендации

application.yaml: dashboard.queuedThreshold=20, dashboard.overdueThreshold=5. Оба значения должны быть положительными. Направление перегружено при queued >= queuedThreshold ИЛИ overdue >= overdueThreshold.

- REVIEW_OVERDUE: проверить просроченные обращения перегруженного направления.
- ASSIGN_AVAILABLE: есть ONLINE-оператор нужного направления ниже своего лимита — проверить назначение.
- ADD_CAPACITY: свободных подходящих сотрудников на смене нет — подключить специалиста направления.

Рекомендации по разным направлениям не резервируют места и не суммируют общую свободную ёмкость: один оператор может иметь несколько специализаций. Данные являются снимком на момент запроса.

## Архитектура и запуск

Пакет dashboard: DashboardRoutes → DashboardService → DashboardQueries; DashboardRules отвечает за предупреждения и рекомендации, DashboardConfig — за пороги. Очереди читаются из существующего queue_load. Операторы — через существующий OperatorRepository, чтобы совпадать с правилами назначения. Новых миграций и зависимостей нет.

Postman: папка «8. Администрирование» — вход администратора, затем dashboard. Используется отдельный adminToken. Тесты: DashboardTest и полный :server:test. Фронтенду нужно реализовать экран на основании DTO; собственный UI в этот шаг бэкенда не входит.
