-- =====================================================================
-- V4: приведение схемы к ответам на вопросы документа уточнений
-- Номера в комментариях — номера вопросов. Решения сведены в docs/OPEN_QUESTIONS.md.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Статусы (31, 32, 12, 34)
-- ---------------------------------------------------------------------

alter table claim_statuses
    add column visible_to_client boolean not null default true,   -- 12: что клиент видит в таймлайне
    add column sla_hours         integer check (sla_hours > 0);    -- 34: учебный срок нахождения в статусе

-- 12: очередь и назначение сотрудника — внутренние этапы
update claim_statuses set visible_to_client = false where code in ('QUEUED', 'ASSIGNED');
update claim_statuses set name_for_client = 'Обращение закрыто без рассмотрения' where code = 'CANCELLED';

-- 34: учебные пороги (часы). Финальные статусы и черновик без срока.
update claim_statuses set sla_hours = case code
    when 'SUBMITTED'   then 1
    when 'IN_ANALYSIS' then 1
    when 'NEEDS_INFO'  then 168
    when 'QUEUED'      then 4
    when 'ASSIGNED'    then 2
    when 'IN_REVIEW'   then 24
end;

-- История: подпись события для клиента, если она отличается от названия статуса
-- (повторная отправка после запроса — «Получены дополнительные материалы»)
alter table claim_status_history add column client_title text;

update claim_status_history set visible_to_client = false where to_status in ('QUEUED', 'ASSIGNED');

-- 31, 32: закрыть без обработки может оператор/администратор; администратор может исправить ошибочное состояние
update status_transitions set allowed_actors = array['CLIENT', 'SYSTEM', 'OPERATOR', 'ADMIN']
    where from_status = 'NEEDS_INFO' and to_status = 'CANCELLED';
update status_transitions set allowed_actors = array['SYSTEM', 'ADMIN']
    where from_status = 'IN_ANALYSIS' and to_status = 'QUEUED';

insert into status_transitions (from_status, to_status, allowed_actors) values
    ('QUEUED',      'CANCELLED', array['OPERATOR', 'ADMIN']),   -- закрыть без обработки (дубль, вне договора…)
    ('ASSIGNED',    'CANCELLED', array['OPERATOR', 'ADMIN']),
    ('IN_REVIEW',   'CANCELLED', array['OPERATOR', 'ADMIN']),
    ('IN_ANALYSIS', 'CANCELLED', array['ADMIN']),
    ('NEEDS_INFO',  'QUEUED',    array['ADMIN']),               -- исправление: материалы не нужны
    ('COMPLETED',   'IN_REVIEW', array['ADMIN']),               -- исправление: переоткрыть рассмотрение
    ('CANCELLED',   'QUEUED',    array['ADMIN']);               -- исправление: закрыто по ошибке

-- 32, 38: действия администратора и закрытие сотрудником — только с причиной (запись в журнале)
alter table claim_status_history add constraint claim_status_history_reason_chk check (
    (actor_type <> 'ADMIN' and (to_status <> 'CANCELLED' or actor_type = 'CLIENT'))
    or length(trim(coalesce(reason, ''))) > 0
);

-- 34: время входа в текущий статус; от него считается превышение срока
alter table claims add column status_changed_at timestamptz;
update claims c set status_changed_at = coalesce(
    (select max(h.created_at) from claim_status_history h where h.claim_id = c.id), c.created_at);
alter table claims alter column status_changed_at set not null,
                   alter column status_changed_at set default now();

-- ---------------------------------------------------------------------
-- Приоритет (27): обычный, повышенный, высокий
-- ---------------------------------------------------------------------

alter table claims drop constraint claims_priority_check;
alter table claims add constraint claims_priority_check check (priority between 1 and 3);  -- 1 обычный, 2 повышенный, 3 высокий

-- ---------------------------------------------------------------------
-- Направления (25): обычная обработка и ручная проверка; «запрос материалов» — статус NEEDS_INFO
-- ---------------------------------------------------------------------

insert into directions (code, name, description) values
    ('STANDARD', 'Обычная обработка', 'Комплект полный, автоматическая проверка без замечаний');

-- Перенос с прежних демо-направлений (данные вымышленные, промышленной эксплуатации ещё не было)
update claims set direction_code = 'STANDARD' where direction_code in ('AUTO_SIMPLE', 'AUTO_COMPLEX', 'GLASS');
insert into operator_directions (operator_id, direction_code)
    select distinct operator_id, 'STANDARD' from operator_directions
    where direction_code in ('AUTO_SIMPLE', 'AUTO_COMPLEX', 'GLASS')
    on conflict do nothing;
delete from operator_directions where direction_code in ('AUTO_SIMPLE', 'AUTO_COMPLEX', 'GLASS');
update claim_decisions set value = jsonb_set(value, '{direction}', '"STANDARD"')
    where kind = 'ROUTING' and value ->> 'direction' in ('AUTO_SIMPLE', 'AUTO_COMPLEX', 'GLASS');
delete from directions where code in ('AUTO_SIMPLE', 'AUTO_COMPLEX', 'GLASS');
update directions set description = 'Сомнительный результат: низкая уверенность модели, плохое качество, дубль, противоречия'
    where code = 'MANUAL_REVIEW';

-- ---------------------------------------------------------------------
-- Решения (36, 37, 38, 1): подтверждение без причины, исправление с причиной, итог рассмотрения
-- ---------------------------------------------------------------------

alter table claim_decisions
    add column action text not null default 'SET' check (action in ('SET', 'CONFIRM', 'OVERRIDE'));

-- прежние ручные исправления (демо) — это OVERRIDE
update claim_decisions set action = 'OVERRIDE' where source = 'OPERATOR' and supersedes_id is not null;

alter table claim_decisions drop constraint claim_decisions_check;
alter table claim_decisions add constraint claim_decisions_action_chk check (
    (source = 'SYSTEM' and action = 'SET')
    or (source = 'OPERATOR' and created_by is not null
        and (action = 'CONFIRM' or length(trim(coalesce(reason, ''))) > 0)   -- 38: причина для SET и OVERRIDE
        and (action = 'SET' or supersedes_id is not null))                    -- подтверждение/исправление ссылается на исходное
);

-- 1, 36: итог рассмотрения фиксирует оператор (это не решение о выплате)
alter table claim_decisions drop constraint claim_decisions_kind_check;
alter table claim_decisions add constraint claim_decisions_kind_check
    check (kind in ('COMPLETENESS', 'DUPLICATE', 'DAMAGE', 'ROUTING', 'PRIORITY', 'REVIEW_RESULT'));

-- ---------------------------------------------------------------------
-- Поля по типу события (6, 7, 2): общие поля едины, дополнительные — из справочника
-- ---------------------------------------------------------------------

-- Дополнительные сведения, специфичные для типа события (обстоятельства, вызывали ли ГИБДД, …)
alter table claims add column event_details jsonb not null default '{}'::jsonb;

create table event_type_fields (
    event_type  text     not null references event_types (code),
    field_code  text     not null,         -- для CORE — имя поля API (eventLocation…), для DETAIL — ключ в event_details
    title       text     not null,
    kind        text     not null check (kind in ('CORE', 'DETAIL')),
    value_type  text     not null default 'STRING' check (value_type in ('STRING', 'BOOLEAN', 'NUMBER', 'LIST')),
    required    boolean  not null,
    sort_order  smallint not null,
    primary key (event_type, field_code)
);

insert into event_type_fields (event_type, field_code, title, kind, value_type, required, sort_order)
select t.code, f.field_code, f.title, 'CORE', f.value_type, true, f.sort_order
from event_types t
cross join (values
    ('eventLocation', 'Место события',               'STRING', 10),
    ('description',   'Описание события',            'STRING', 20),
    ('contactPhone',  'Телефон для уведомлений',     'STRING', 30)
) as f (field_code, title, value_type, sort_order);

insert into event_type_fields (event_type, field_code, title, kind, value_type, required, sort_order) values
    ('CAR_ACCIDENT', 'participants',  'Участники происшествия',        'CORE',   'LIST',    true,  40),
    ('CAR_ACCIDENT', 'circumstances', 'Обстоятельства происшествия',   'DETAIL', 'STRING',  true,  50),
    ('CAR_ACCIDENT', 'policeCalled',  'Вызывали ГИБДД',                'DETAIL', 'BOOLEAN', false, 60),
    ('CAR_ACCIDENT', 'europrotocol',  'Оформлено по европротоколу',    'DETAIL', 'BOOLEAN', false, 70),
    ('CAR_DAMAGE',   'circumstances', 'Обстоятельства повреждения',    'DETAIL', 'STRING',  true,  50);

-- ---------------------------------------------------------------------
-- Документы (13, 14, 2)
-- ---------------------------------------------------------------------

-- 13: для ДТП — фотографии и извещение/заявление; сведения о ТС берутся из договора, обстоятельства — поле формы
delete from required_documents where event_type = 'CAR_ACCIDENT' and document_type in ('DRIVER_LICENSE', 'VEHICLE_REGISTRATION');
delete from required_documents where event_type in ('CAR_DAMAGE', 'CAR_GLASS') and document_type in ('VEHICLE_REGISTRATION', 'POLICE_REPORT');
update document_types set name = 'Извещение о ДТП / заявление' where code = 'ACCIDENT_NOTICE';

-- 14: база — JPEG, PNG, PDF; HEIC не поддерживаем
update document_types set allowed_mime_types = array_remove(allowed_mime_types, 'image/heic');

-- ---------------------------------------------------------------------
-- Запрос материалов (15, 16, 22): различаем «нет файла», «неверный формат», «плохое качество»
-- ---------------------------------------------------------------------

alter table info_request_items
    add column issue   text not null default 'MISSING' check (issue in ('MISSING', 'INVALID_FORMAT', 'LOW_QUALITY')),
    add column hint    text,                                        -- 22: подсказка, как исправить
    add column file_id bigint references claim_files (id);          -- 16: какой файл заменить
create index info_request_items_file_idx on info_request_items (file_id) where file_id is not null;

-- ---------------------------------------------------------------------
-- Анализ изображений (19, 23, 51)
-- ---------------------------------------------------------------------

alter table image_analyses
    add column damage_detected boolean,     -- 19: есть ли видимая область повреждения (обязательный минимум)
    add column car_part        text,        -- 19: часть автомобиля (рекомендуется)
    add column damage_area     jsonb;       -- рамка области повреждения для показа оператору

-- 51: оператор видит, что анализ ещё не завершён
alter table claim_files
    add column analysis_status text not null default 'NOT_REQUIRED'
        check (analysis_status in ('NOT_REQUIRED', 'PENDING', 'DONE', 'FAILED'));

-- ---------------------------------------------------------------------
-- Уведомления (44): каналы кроме внутреннего центра — имитация
-- ---------------------------------------------------------------------

alter table notifications drop constraint notifications_channel_check;
alter table notifications add constraint notifications_channel_check
    check (channel in ('IN_APP', 'PUSH', 'EMAIL', 'SMS'));

-- ---------------------------------------------------------------------
-- Сроки (34): единственный срок sla_due_at заменён на «время входа в статус + срок статуса»
-- ---------------------------------------------------------------------

drop view operator_claim_list;
drop view queue_load;
drop view claim_client_timeline;
drop index claims_queue_idx;
alter table claims drop column sla_due_at;

create index claims_queue_idx on claims (direction_code, priority desc, status_changed_at) where status = 'QUEUED';

create view claim_client_timeline as
select h.claim_id,
       h.id                                        as history_id,
       h.created_at,
       h.to_status                                 as status,
       coalesce(h.client_title, s.name_for_client) as title,
       h.reason                                    as comment
from claim_status_history h
join claim_statuses s on s.code = h.to_status
where h.visible_to_client;

create view operator_claim_list as
select c.id,
       c.claim_number,
       c.status,
       c.direction_code,
       d.name                                    as direction_name,
       c.priority,
       c.requires_manual_review,
       c.event_type,
       et.name                                   as event_type_name,
       c.client_id,
       u.full_name                               as client_name,
       p.policy_number,
       c.assigned_operator_id,
       op.full_name                              as assigned_operator_name,
       c.submitted_at,
       c.created_at,
       c.status_changed_at,
       c.status_changed_at + make_interval(hours => s.sla_hours)                  as due_at,
       (s.sla_hours is not null
            and c.status_changed_at + make_interval(hours => s.sla_hours) < now()) as is_overdue,
       (select count(*) from claim_files f
         where f.claim_id = c.id and f.status = 'ACCEPTED')                      as files_count,
       (select count(*) from claim_files f
         where f.claim_id = c.id and f.analysis_status = 'PENDING')              as analyses_pending,
       c.duplicate_of_claim_id is not null                                      as has_duplicate,
       c.updated_at
from claims c
join claim_statuses s    on s.code = c.status
join users u             on u.id = c.client_id
left join policies p     on p.id = c.policy_id
left join directions d   on d.code = c.direction_code
left join event_types et on et.code = c.event_type
left join users op       on op.id = c.assigned_operator_id
where c.status <> 'DRAFT';

create view queue_load as
select d.code                                                    as direction_code,
       count(c.id)                                               as queued,
       count(c.id) filter (where c.priority >= 3)                as queued_urgent,
       count(c.id) filter (where c.status_changed_at + make_interval(hours => s.sla_hours) < now()) as overdue,
       min(c.status_changed_at)                                  as oldest_queued_at
from directions d
left join claims c on c.direction_code = d.code and c.status = 'QUEUED'
left join claim_statuses s on s.code = c.status
group by d.code;
