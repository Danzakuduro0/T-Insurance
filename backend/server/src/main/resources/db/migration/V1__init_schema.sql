-- =====================================================================
-- Т-Страхование: сопровождение страхового случая
-- V1: базовая схема
-- Справочники (статусы, переходы, типы документов, направления) — в V2.
-- Все данные в системе вымышленные.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Справочники
-- ---------------------------------------------------------------------

-- Статусы обращения (конечный автомат)
create table claim_statuses (
    code             text primary key,
    name_for_client  text    not null,          -- что видит клиент в timeline
    is_final         boolean not null default false,
    sort_order       smallint not null
);

-- Разрешённые переходы и кто может их выполнять.
-- Источник правды для БД; в Kotlin держим тот же граф (и тест, что они совпадают).
create table status_transitions (
    from_status     text   not null references claim_statuses (code),
    to_status       text   not null references claim_statuses (code),
    allowed_actors  text[] not null,            -- SYSTEM | CLIENT | OPERATOR | ADMIN
    primary key (from_status, to_status),
    check (from_status <> to_status),
    check (allowed_actors <@ array['SYSTEM', 'CLIENT', 'OPERATOR', 'ADMIN'])
);

-- Типы страховых событий
create table event_types (
    code  text primary key,
    name  text not null
);

-- Типы документов/материалов
create table document_types (
    code               text primary key,
    name               text    not null,
    is_image           boolean not null,        -- отправлять ли в ML-анализ
    allowed_mime_types text[]  not null,
    max_size_bytes     bigint  not null check (max_size_bytes > 0)
);

-- Правила комплектности: какой минимум документов нужен для типа события
create table required_documents (
    event_type     text     not null references event_types (code),
    document_type  text     not null references document_types (code),
    min_count      smallint not null default 1 check (min_count > 0),
    primary key (event_type, document_type)
);

-- Направления обработки (очереди)
create table directions (
    code         text primary key,
    name         text not null,
    description  text
);

-- ---------------------------------------------------------------------
-- Пользователи и договоры
-- ---------------------------------------------------------------------

create table users (
    id                 bigint generated always as identity primary key,
    role               text    not null check (role in ('CLIENT', 'OPERATOR', 'ADMIN')),
    full_name          text    not null,
    email              text    not null unique,
    phone              text,
    password_hash      text    not null,
    max_active_claims  smallint check (max_active_claims > 0),  -- лимит для оператора
    is_active          boolean not null default true,
    created_at         timestamptz not null default now()
);

-- Специализации операторов
create table operator_directions (
    operator_id     bigint not null references users (id) on delete cascade,
    direction_code  text   not null references directions (code),
    primary key (operator_id, direction_code)
);
create index operator_directions_direction_idx on operator_directions (direction_code);

-- Вымышленные полисы
create table policies (
    id              bigint generated always as identity primary key,
    policy_number   text  not null unique,
    client_id       bigint not null references users (id),
    product         text  not null check (product in ('OSAGO', 'KASKO', 'PROPERTY')),
    insured_object  jsonb not null default '{}'::jsonb,   -- марка/модель/VIN (вымышленные)
    valid_from      date  not null,
    valid_to        date  not null,
    created_at      timestamptz not null default now(),
    check (valid_to >= valid_from)
);
create index policies_client_id_idx on policies (client_id);

-- ---------------------------------------------------------------------
-- Обращения
-- ---------------------------------------------------------------------

create sequence claim_number_seq;

create table claims (
    id                     bigint generated always as identity primary key,
    claim_number           text not null unique
                           default ('TS-' || lpad(nextval('claim_number_seq')::text, 7, '0')),
    client_id              bigint not null references users (id),
    policy_id              bigint references policies (id),          -- может быть пустым в черновике
    event_type             text   references event_types (code),
    event_at               timestamptz,
    event_location         text,
    description            text,
    contact_phone          text,

    status                 text not null default 'DRAFT' references claim_statuses (code),

    -- Действующие (эффективные) значения маршрутизации — денормализация для очереди.
    -- Полная история «система vs оператор» лежит в claim_decisions.
    direction_code         text references directions (code),
    priority               smallint check (priority between 1 and 4), -- 1 низкий … 4 критический
    requires_manual_review boolean not null default false,
    assigned_operator_id   bigint references users (id),
    duplicate_of_claim_id  bigint references claims (id),

    client_request_id      uuid unique,           -- идемпотентность создания с мобильного клиента
    sla_due_at             timestamptz,
    submitted_at           timestamptz,
    assigned_at            timestamptz,
    closed_at              timestamptz,

    version                integer not null default 0,  -- оптимистическая блокировка
    created_at             timestamptz not null default now(),
    updated_at             timestamptz not null default now(),

    -- после отправки обязательные поля должны быть заполнены
    constraint claims_required_fields_chk check (
        status in ('DRAFT', 'CANCELLED')
        or (policy_id is not null and event_type is not null and event_at is not null)
    ),
    -- в работе у оператора — значит оператор назначен
    constraint claims_assignee_chk check (
        status not in ('ASSIGNED', 'IN_REVIEW') or assigned_operator_id is not null
    )
);
create index claims_client_idx       on claims (client_id, created_at desc);
create index claims_policy_idx       on claims (policy_id);
create index claims_operator_idx     on claims (assigned_operator_id) where assigned_operator_id is not null;
create index claims_duplicate_of_idx on claims (duplicate_of_claim_id) where duplicate_of_claim_id is not null;
create index claims_status_idx       on claims (status, created_at);
-- Очередь: внутри направления — сначала срочные, потом у кого раньше SLA
create index claims_queue_idx        on claims (direction_code, priority desc, sla_due_at)
    where status = 'QUEUED';

-- Участники события (вымышленные)
create table claim_participants (
    id         bigint generated always as identity primary key,
    claim_id   bigint not null references claims (id) on delete cascade,
    role       text   not null check (role in ('APPLICANT_DRIVER', 'OTHER_DRIVER', 'VICTIM', 'WITNESS')),
    full_name  text   not null,
    phone      text,
    vehicle    jsonb,          -- марка/модель, госномер (вымышленный)
    insurer    text,
    note       text
);
create index claim_participants_claim_idx on claim_participants (claim_id);

-- ---------------------------------------------------------------------
-- Материалы
-- Сам файл лежит в S3/MinIO, в БД — только метаданные.
-- ---------------------------------------------------------------------

create table claim_files (
    id                    bigint generated always as identity primary key,
    claim_id              bigint not null references claims (id),
    document_type         text   not null references document_types (code),
    storage_key           text   not null unique,
    original_name         text   not null,
    mime_type             text   not null,      -- определяется по сигнатуре файла, а не по расширению
    size_bytes            bigint not null check (size_bytes > 0),
    sha256                text   not null,      -- точные дубли
    phash                 bigint,               -- перцептивный хеш: «та же картинка, другой размер»
    width                 integer,
    height                integer,
    status                text   not null default 'UPLOADED'
                          check (status in ('UPLOADED', 'ACCEPTED', 'REJECTED', 'DUPLICATE', 'REPLACED')),
    rejection_reason      text,                 -- CORRUPTED | UNSUPPORTED_FORMAT | TOO_LARGE | LOW_QUALITY ...
    duplicate_of_file_id  bigint references claim_files (id),
    replaced_by_file_id   bigint references claim_files (id),
    uploaded_by           bigint not null references users (id),
    client_request_id     uuid unique,          -- повтор загрузки при плохой сети не создаёт дубль
    uploaded_at           timestamptz not null default now(),
    check (status <> 'REJECTED' or rejection_reason is not null),
    check (status <> 'DUPLICATE' or duplicate_of_file_id is not null)
);
create index claim_files_claim_idx        on claim_files (claim_id, document_type);
create index claim_files_sha256_idx       on claim_files (sha256);
create index claim_files_uploaded_by_idx  on claim_files (uploaded_by);
create index claim_files_duplicate_of_idx on claim_files (duplicate_of_file_id) where duplicate_of_file_id is not null;
create index claim_files_replaced_by_idx  on claim_files (replaced_by_file_id) where replaced_by_file_id is not null;

-- Результат ML-анализа изображения (может быть несколько: повторный прогон, новая модель)
create table image_analyses (
    id                 bigint generated always as identity primary key,
    file_id            bigint not null references claim_files (id),
    model_version      text   not null,
    quality_ok         boolean not null,
    quality_score      real   check (quality_score between 0 and 1),
    quality_issues     text[] not null default '{}',   -- BLURRY | DARK | LOW_RESOLUTION | NO_VEHICLE
    vehicle_detected   boolean,
    damage_type        text,                           -- SCRATCH | DENT | GLASS | LAMP | BUMPER ...
    damage_confidence  real   check (damage_confidence between 0 and 1),
    raw_result         jsonb,                          -- полный ответ ML-сервиса
    created_at         timestamptz not null default now()
);
create index image_analyses_file_idx on image_analyses (file_id, created_at desc);

-- ---------------------------------------------------------------------
-- Решения системы и ручные исправления
-- Append-only: исправление оператора — НОВАЯ строка со ссылкой на исходную.
-- Первоначальное решение никогда не перезаписывается.
-- ---------------------------------------------------------------------

create table claim_decisions (
    id             bigint generated always as identity primary key,
    claim_id       bigint not null references claims (id),
    kind           text   not null
                   check (kind in ('COMPLETENESS', 'DUPLICATE', 'DAMAGE', 'ROUTING', 'PRIORITY')),
    source         text   not null check (source in ('SYSTEM', 'OPERATOR')),
    value          jsonb  not null,       -- {"direction":"AUTO_SIMPLE"} / {"missing":[...]} / ...
    confidence     real   check (confidence between 0 and 1),
    reason         text,                  -- для SYSTEM — сработавшие правила, для OPERATOR — комментарий
    rule_version   text,
    supersedes_id  bigint references claim_decisions (id),
    is_current     boolean not null default true,
    created_by     bigint references users (id),
    created_at     timestamptz not null default now(),
    -- ручное исправление: обязателен автор и причина
    check (source = 'SYSTEM' or (created_by is not null and reason is not null and length(trim(reason)) > 0))
);
-- одно действующее решение каждого вида на обращение
create unique index claim_decisions_current_uq on claim_decisions (claim_id, kind) where is_current;
create index claim_decisions_claim_idx      on claim_decisions (claim_id, created_at);
create index claim_decisions_supersedes_idx on claim_decisions (supersedes_id) where supersedes_id is not null;
create index claim_decisions_created_by_idx on claim_decisions (created_by) where created_by is not null;

-- ---------------------------------------------------------------------
-- История статусов (основа клиентской временной шкалы)
-- FK на status_transitions: недопустимый переход физически не попадёт в историю.
-- from_status = NULL — создание обращения.
-- ---------------------------------------------------------------------

create table claim_status_history (
    id                 bigint generated always as identity primary key,
    claim_id           bigint not null references claims (id),
    from_status        text,
    to_status          text   not null references claim_statuses (code),
    actor_type         text   not null check (actor_type in ('SYSTEM', 'CLIENT', 'OPERATOR', 'ADMIN')),
    actor_id           bigint references users (id),
    reason             text,
    visible_to_client  boolean not null default true,
    created_at         timestamptz not null default now(),
    foreign key (from_status, to_status) references status_transitions (from_status, to_status),
    check (actor_type = 'SYSTEM' or actor_id is not null)
);
create index claim_status_history_claim_idx on claim_status_history (claim_id, created_at);
create index claim_status_history_actor_idx on claim_status_history (actor_id) where actor_id is not null;

-- ---------------------------------------------------------------------
-- Общий журнал изменений (кто, что, было → стало, комментарий)
-- ---------------------------------------------------------------------

create table audit_log (
    id           bigint generated always as identity primary key,
    claim_id     bigint references claims (id),
    entity_type  text   not null,      -- CLAIM | FILE | DECISION | ASSIGNMENT ...
    entity_id    bigint,
    action       text   not null,      -- CREATE | UPDATE | STATUS_CHANGE | OVERRIDE | ASSIGN ...
    old_value    jsonb,
    new_value    jsonb,
    actor_type   text   not null check (actor_type in ('SYSTEM', 'CLIENT', 'OPERATOR', 'ADMIN')),
    actor_id     bigint references users (id),
    comment      text,
    created_at   timestamptz not null default now()
);
create index audit_log_claim_idx  on audit_log (claim_id, created_at) where claim_id is not null;
create index audit_log_entity_idx on audit_log (entity_type, entity_id);
create index audit_log_actor_idx  on audit_log (actor_id) where actor_id is not null;

-- ---------------------------------------------------------------------
-- Уведомления клиенту (in-app; push/email — через тот же журнал)
-- ---------------------------------------------------------------------

create table notifications (
    id          bigint generated always as identity primary key,
    user_id     bigint not null references users (id),
    claim_id    bigint references claims (id),
    type        text   not null,      -- STATUS_CHANGED | DOCUMENTS_REQUIRED | OPERATOR_ASSIGNED ...
    title       text   not null,
    body        text   not null,
    channel     text   not null default 'IN_APP' check (channel in ('IN_APP', 'PUSH', 'EMAIL')),
    created_at  timestamptz not null default now(),
    sent_at     timestamptz,
    read_at     timestamptz
);
create index notifications_user_idx   on notifications (user_id, created_at desc);
create index notifications_unread_idx on notifications (user_id) where read_at is null;
create index notifications_claim_idx  on notifications (claim_id) where claim_id is not null;

-- ---------------------------------------------------------------------
-- Фоновые задачи (ML-анализ, маршрутизация, рассылка).
-- Забираются воркером через FOR UPDATE SKIP LOCKED.
-- Если ML недоступен — задача остаётся PENDING и повторяется с backoff.
-- ---------------------------------------------------------------------

create table background_jobs (
    id            bigint generated always as identity primary key,
    type          text    not null check (type in ('ANALYZE_IMAGE', 'ROUTE_CLAIM', 'SEND_NOTIFICATION')),
    payload       jsonb   not null,
    dedup_key     text    unique,        -- напр. 'ANALYZE_IMAGE:file:42' — не ставим задачу дважды
    status        text    not null default 'PENDING'
                  check (status in ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts      integer not null default 0,
    max_attempts  integer not null default 5,
    run_after     timestamptz not null default now(),
    locked_at     timestamptz,
    locked_by     text,
    last_error    text,
    created_at    timestamptz not null default now(),
    finished_at   timestamptz
);
create index background_jobs_pending_idx on background_jobs (run_after) where status = 'PENDING';
create index background_jobs_running_idx on background_jobs (locked_at) where status = 'RUNNING';

-- ---------------------------------------------------------------------
-- Триггеры: защитная сетка конечного автомата на уровне БД
-- (основная логика переходов — в сервисе на Kotlin)
-- ---------------------------------------------------------------------

create function claims_before_update() returns trigger
language plpgsql as $$
begin
    if new.status is distinct from old.status then
        if not exists (
            select 1 from status_transitions
            where from_status = old.status and to_status = new.status
        ) then
            raise exception 'Недопустимый переход статуса: % -> %', old.status, new.status
                using errcode = 'check_violation';
        end if;
    end if;
    new.updated_at := now();
    return new;
end;
$$;

create trigger claims_before_update_trg
    before update on claims
    for each row execute function claims_before_update();

create function status_history_check_actor() returns trigger
language plpgsql as $$
begin
    if new.from_status is not null and not exists (
        select 1 from status_transitions
        where from_status = new.from_status
          and to_status   = new.to_status
          and new.actor_type = any (allowed_actors)
    ) then
        raise exception 'Актор % не может выполнить переход % -> %',
            new.actor_type, new.from_status, new.to_status
            using errcode = 'insufficient_privilege';
    end if;
    return new;
end;
$$;

create trigger status_history_check_actor_trg
    before insert on claim_status_history
    for each row execute function status_history_check_actor();

-- ---------------------------------------------------------------------
-- Представления
-- ---------------------------------------------------------------------

-- Временная шкала для клиента: только видимые клиенту события
create view claim_client_timeline as
select h.claim_id,
       h.created_at,
       h.to_status          as status,
       s.name_for_client    as title,
       h.reason             as comment
from claim_status_history h
join claim_statuses s on s.code = h.to_status
where h.visible_to_client;

-- Нагрузка на очередь по направлениям (для детекта перегрузки)
create view queue_load as
select d.code                                              as direction_code,
       count(c.id)                                         as queued,
       count(c.id) filter (where c.priority >= 3)          as queued_urgent,
       count(c.id) filter (where c.sla_due_at < now())     as overdue,
       min(c.submitted_at)                                 as oldest_submitted_at
from directions d
left join claims c on c.direction_code = d.code and c.status = 'QUEUED'
group by d.code;

-- Загрузка операторов
create view operator_load as
select u.id                    as operator_id,
       u.full_name,
       u.max_active_claims,
       count(c.id)             as active_claims
from users u
left join claims c on c.assigned_operator_id = u.id and c.status in ('ASSIGNED', 'IN_REVIEW')
where u.role = 'OPERATOR' and u.is_active
group by u.id;
