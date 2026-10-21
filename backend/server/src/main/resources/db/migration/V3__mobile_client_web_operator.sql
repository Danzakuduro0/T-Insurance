-- =====================================================================
-- V3: разделение каналов — клиент в мобильном приложении, оператор в веб-интерфейсе
-- =====================================================================

-- ---------------------------------------------------------------------
-- Пользователи: клиент входит по телефону, сотрудник — по email
-- ---------------------------------------------------------------------

alter table users alter column email drop not null;

create unique index users_phone_uq on users (phone) where phone is not null;

alter table users
    add column availability text check (availability in ('ONLINE', 'BREAK', 'OFFLINE')),  -- только для сотрудников
    add column last_seen_at timestamptz,
    add constraint users_login_by_role_chk check (
        (role = 'CLIENT' and phone is not null)
        or (role <> 'CLIENT' and email is not null)
    ),
    add constraint users_availability_staff_only_chk check (
        role <> 'CLIENT' or availability is null
    );

-- ---------------------------------------------------------------------
-- Устройства клиента (push через FCM / APNs)
-- ---------------------------------------------------------------------

create table user_devices (
    id            bigint generated always as identity primary key,
    user_id       bigint  not null references users (id) on delete cascade,
    platform      text    not null check (platform in ('ANDROID', 'IOS')),
    installation_id text  not null,          -- id установки приложения, генерирует само приложение
    push_token    text,                      -- обновляется приложением; null — push отключён
    app_version   text,
    os_version    text,
    is_active     boolean not null default true,   -- false, если FCM/APNs сообщили, что токен недействителен
    created_at    timestamptz not null default now(),
    last_seen_at  timestamptz not null default now(),
    unique (user_id, installation_id)
);
create unique index user_devices_push_token_uq on user_devices (push_token) where push_token is not null;

-- ---------------------------------------------------------------------
-- Сессии: долгие на телефоне (refresh-токен на устройство), короткие в вебе
-- Хранится только хеш refresh-токена.
-- ---------------------------------------------------------------------

create table auth_sessions (
    id                  bigint generated always as identity primary key,
    user_id             bigint not null references users (id) on delete cascade,
    client_type         text   not null check (client_type in ('MOBILE', 'WEB')),
    device_id           bigint references user_devices (id) on delete cascade,
    refresh_token_hash  text   not null unique,
    user_agent          text,
    created_at          timestamptz not null default now(),
    last_used_at        timestamptz not null default now(),
    expires_at          timestamptz not null,
    revoked_at          timestamptz,
    check (client_type = 'WEB' or device_id is not null)
);
create index auth_sessions_user_idx   on auth_sessions (user_id) where revoked_at is null;
create index auth_sessions_device_idx on auth_sessions (device_id) where device_id is not null;

-- ---------------------------------------------------------------------
-- Запросы дополнительных материалов
-- Клиент в приложении видит чек-лист «что загрузить», оператор создаёт запрос в вебе.
-- Система создаёт такой же запрос, если не прошла проверка комплектности.
-- ---------------------------------------------------------------------

create table info_requests (
    id            bigint generated always as identity primary key,
    claim_id      bigint not null references claims (id),
    source        text   not null check (source in ('SYSTEM', 'OPERATOR')),
    requested_by  bigint references users (id),
    message       text   not null,                 -- текст для клиента
    status        text   not null default 'OPEN' check (status in ('OPEN', 'FULFILLED', 'CANCELLED')),
    due_at        timestamptz,
    created_at    timestamptz not null default now(),
    closed_at     timestamptz,
    check (source = 'SYSTEM' or requested_by is not null),
    check ((status = 'OPEN') = (closed_at is null))
);
-- у обращения не больше одного открытого запроса — клиенту всегда ясно, что делать
create unique index info_requests_one_open_uq on info_requests (claim_id) where status = 'OPEN';
create index info_requests_claim_idx        on info_requests (claim_id, created_at);
create index info_requests_requested_by_idx on info_requests (requested_by) where requested_by is not null;

create table info_request_items (
    id             bigint generated always as identity primary key,
    request_id     bigint   not null references info_requests (id) on delete cascade,
    document_type  text     references document_types (code),  -- нужен документ/фото
    field_name     text,                                        -- или нужно заполнить поле (event_location, ...)
    min_count      smallint not null default 1 check (min_count > 0),
    comment        text,                                        -- «сфотографируйте задний бампер ближе»
    is_fulfilled   boolean  not null default false,
    check (document_type is not null or field_name is not null)
);
create index info_request_items_request_idx on info_request_items (request_id);

-- ---------------------------------------------------------------------
-- Комментарии по обращению
-- INTERNAL — заметки сотрудников в веб-карточке, клиенту не отдаются никогда.
-- CLIENT   — сообщение, которое клиент увидит в приложении.
-- ---------------------------------------------------------------------

create table claim_comments (
    id          bigint generated always as identity primary key,
    claim_id    bigint not null references claims (id),
    author_id   bigint not null references users (id),
    visibility  text   not null check (visibility in ('INTERNAL', 'CLIENT')),
    body        text   not null check (length(trim(body)) > 0),
    created_at  timestamptz not null default now()
);
create index claim_comments_claim_idx  on claim_comments (claim_id, created_at);
create index claim_comments_author_idx on claim_comments (author_id);

-- ---------------------------------------------------------------------
-- Уведомления: данные для перехода по push в нужный экран приложения
-- ---------------------------------------------------------------------

alter table notifications
    add column payload jsonb not null default '{}'::jsonb;   -- {"screen":"claim","claimId":42}

-- ---------------------------------------------------------------------
-- Представления под интерфейсы
-- ---------------------------------------------------------------------

-- Мобильное приложение: список обращений клиента
create view client_claim_list as
select c.id,
       c.client_id,
       c.claim_number,
       c.status,
       s.name_for_client                         as status_title,
       s.is_final,
       et.name                                   as event_type_name,
       c.event_at,
       c.created_at,
       c.updated_at,
       (r.id is not null)                        as action_required,   -- есть открытый запрос материалов
       r.message                                 as action_message
from claims c
join claim_statuses s    on s.code = c.status
left join event_types et on et.code = c.event_type
left join info_requests r on r.claim_id = c.id and r.status = 'OPEN';

-- Веб оператора: строки таблицы очереди и «моих» обращений
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
       c.sla_due_at,
       (c.sla_due_at is not null and c.sla_due_at < now() and not s.is_final) as is_overdue,
       (select count(*) from claim_files f
         where f.claim_id = c.id and f.status = 'ACCEPTED')                  as files_count,
       c.duplicate_of_claim_id is not null                                  as has_duplicate,
       c.updated_at
from claims c
join claim_statuses s    on s.code = c.status
join users u             on u.id = c.client_id
left join policies p     on p.id = c.policy_id
left join directions d   on d.code = c.direction_code
left join event_types et on et.code = c.event_type
left join users op       on op.id = c.assigned_operator_id
where c.status <> 'DRAFT';   -- черновики оператору не видны
