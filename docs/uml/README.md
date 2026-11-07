# UML-диаграммы — Т-Страхование: сопровождение страхового случая

Формат: PlantUML. Все файлы самодостаточны и используют встроенную тему `!theme materia-outline` (подключать ничего не нужно).
Основа: «ДОКУМЕНТАЦИЯ И ТЗ» и «ДЕТАЛИЗАЦИЯ ТРЕБОВАНИЙ» в редакции команды (бэкенд: Kotlin + Ktor, префиксы `/api/mobile` и `/api/operator`, статусы NEEDS_INFO / COMPLETED / CANCELLED, очередь `background_jobs`).

## Как открыть
- онлайн: plantuml.com/plantuml (вставить содержимое файла);
- VS Code: расширение «PlantUML» (Alt+D — предпросмотр);
- IntelliJ IDEA / Android Studio: плагин «PlantUML integration»;
- экспорт: `java -jar plantuml.jar -tsvg -r .` (SVG / PNG / PDF).

## Состав
| № | Файл | Тип | Что показывает | Раздел документации |
|---|------|-----|----------------|---------------------|
| 01 | 1_context/01_use_case | Use Case | Клиент, оператор, администратор, система | 1.3 |
| 02 | 1_context/02_architecture | Component | Клиенты KMP, Ktor, ML-сервис, воркер, PostgreSQL, диск | 1.5 |
| 03 | 2_model/03_state_claim | State | Статусы и допустимые переходы с инициаторами | 3.2 |
| 04 | 2_model/04_er_model | ER | Ключевые таблицы и связи | 3.4 + описание бэкенда |
| 05 | 3_client_flows/05_seq_create_submit | Sequence | Черновик, комплектность, отправка | 3.3.1 |
| 06 | 3_client_flows/06_seq_upload_file | Sequence | Проверка файла, дубли, постановка ANALYZE_IMAGE | 3.3.2 |
| 07 | 3_client_flows/07_seq_needs_info_cycle | Sequence | Запрос уточнения и ответ клиента | 3.2, 3.3.5 |
| 08 | 3_client_flows/08_seq_timeline_notifications | Sequence | Временная шкала и уведомления | 3.3.5, 4.2 |
| 09 | 4_auto_processing/09_seq_ml_analysis | Sequence | Воркер, ML /analyze, повторы 30/90/270 с | 6.2, 6.5 |
| 10 | 4_auto_processing/10_activity_routing | Activity | Правила маршрутизации | 6.4 |
| 11 | 4_auto_processing/11_seq_timeouts_sla | Sequence | Таймаут ML, 7 дней в NEEDS_INFO, просрочка | 3.2, 3.3.3 |
| 12 | 5_operator_admin/12_seq_queue_acquire | Sequence | Очередь, захват, карточка, начало рассмотрения | 3.3.3, 4.3 |
| 13 | 5_operator_admin/13_seq_ml_override | Sequence | Исправление ML рядом с исходным результатом | 3.3.4 |
| 14 | 5_operator_admin/14_seq_complete_cancel_reopen | Sequence | Завершение, закрытие, повторное открытие | 3.2.2 |
| 15 | 5_operator_admin/15_seq_admin_reassign_priority | Sequence | Метрики, переназначение, смена приоритета | 4.4 |
| 16 | 6_security/16_seq_auth | Sequence | JWT 15 мин, одноразовый refresh, выход | 10.2 |

## Места, где документация неоднозначна (выбранный вариант)
1. **NEEDS_INFO после ответа клиента.** В таблице переходов — NEEDS_INFO → IN_ANALYSIS, в комментарии к уведомлениям — «NEEDS_INFO → SUBMITTED». Нарисован вариант из таблицы переходов.
2. **ASSIGNED.** В таблице переход QUEUED → IN_REVIEW, в описании статусов перед IN_REVIEW идёт ASSIGNED. Нарисовано через ASSIGNED.
3. **QUEUED для клиента.** В таблице статусов «Да», в описании — «клиенту не показывается». QUEUED и ASSIGNED показаны скрытыми.
4. **AUTO / STANDARD.** В правилах маршрутизации — AUTO, в фильтре очереди бэкенда — STANDARD. На диаграмме 10 указаны оба.
5. **Схема БД.** SQL в разделе 3.4 (UUID, documents, ml_results) расходится с описанием бэкенда (bigint, claim_files, image_analyses, claim_decisions). ER построена по описанию бэкенда; служебные поля `background_jobs` помечены «уточнить по миграциям Flyway».
6. **Пути, которых нет в документации:** вход и обновление токена, запрос метрик, изменение приоритета, отмена клиентом. На диаграммах 15 и 16 они описаны словами, без URL. Пути `/cancel`, `/reopen`, `/acquire`, `/release`, `/reassign` в префиксе `/api/operator` выведены по образцу start-review / request-info / complete.

Диаграммы не отрисованы в среде подготовки (нет PlantUML), проверена только парность блоков. Перед сдачей откройте каждую в предпросмотре.
