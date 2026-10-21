-- =====================================================================
-- V2: справочные данные
-- Значения — предварительные, уточняются по вопросам 2, 13, 25, 31, 32.
-- =====================================================================

insert into claim_statuses (code, name_for_client, is_final, sort_order) values
    ('DRAFT',       'Черновик',                              false, 10),
    ('SUBMITTED',   'Обращение отправлено',                  false, 20),
    ('NEEDS_INFO',  'Нужны дополнительные материалы',        false, 30),
    ('IN_ANALYSIS', 'Проверяем материалы',                   false, 40),
    ('QUEUED',      'Ожидает рассмотрения',                  false, 50),
    ('ASSIGNED',    'Назначен специалист',                   false, 60),
    ('IN_REVIEW',   'Специалист рассматривает обращение',    false, 70),
    ('COMPLETED',   'Рассмотрение завершено',                true,  80),
    ('CANCELLED',   'Обращение отозвано',                    true,  90);

insert into status_transitions (from_status, to_status, allowed_actors) values
    -- клиент
    ('DRAFT',       'SUBMITTED',   array['CLIENT']),
    ('DRAFT',       'CANCELLED',   array['CLIENT']),
    ('NEEDS_INFO',  'SUBMITTED',   array['CLIENT']),               -- дозагрузил материалы
    ('NEEDS_INFO',  'CANCELLED',   array['CLIENT', 'SYSTEM']),     -- отозвал / истёк срок
    -- система: проверка формата и комплектности
    ('SUBMITTED',   'NEEDS_INFO',  array['SYSTEM']),               -- неполный комплект
    ('SUBMITTED',   'IN_ANALYSIS', array['SYSTEM']),
    -- система: анализ изображений и маршрутизация
    ('IN_ANALYSIS', 'NEEDS_INFO',  array['SYSTEM']),               -- плохое качество фото
    ('IN_ANALYSIS', 'QUEUED',      array['SYSTEM']),
    -- назначение
    ('QUEUED',      'ASSIGNED',    array['SYSTEM', 'OPERATOR', 'ADMIN']),
    ('ASSIGNED',    'QUEUED',      array['OPERATOR', 'ADMIN']),    -- снять назначение
    ('ASSIGNED',    'IN_REVIEW',   array['OPERATOR']),
    -- работа оператора
    ('IN_REVIEW',   'NEEDS_INFO',  array['OPERATOR']),             -- запрос документов
    ('IN_REVIEW',   'QUEUED',      array['OPERATOR', 'ADMIN']),    -- смена направления
    ('IN_REVIEW',   'COMPLETED',   array['OPERATOR']);

insert into event_types (code, name) values
    ('CAR_ACCIDENT', 'ДТП'),
    ('CAR_DAMAGE',   'Повреждение автомобиля без ДТП (град, падение предмета, вандализм)'),
    ('CAR_GLASS',    'Повреждение стекла');

insert into document_types (code, name, is_image, allowed_mime_types, max_size_bytes) values
    ('DAMAGE_PHOTO',         'Фото повреждений',                       true,
        array['image/jpeg', 'image/png', 'image/heic'],                     20 * 1024 * 1024),
    ('ACCIDENT_NOTICE',      'Извещение о ДТП',                        false,
        array['image/jpeg', 'image/png', 'application/pdf'],                20 * 1024 * 1024),
    ('DRIVER_LICENSE',       'Водительское удостоверение',             false,
        array['image/jpeg', 'image/png', 'application/pdf'],                10 * 1024 * 1024),
    ('VEHICLE_REGISTRATION', 'Свидетельство о регистрации ТС',         false,
        array['image/jpeg', 'image/png', 'application/pdf'],                10 * 1024 * 1024),
    ('POLICE_REPORT',        'Справка из полиции',                     false,
        array['image/jpeg', 'image/png', 'application/pdf'],                20 * 1024 * 1024),
    ('OTHER',                'Прочие документы',                       false,
        array['image/jpeg', 'image/png', 'application/pdf'],                20 * 1024 * 1024);

insert into required_documents (event_type, document_type, min_count) values
    ('CAR_ACCIDENT', 'DAMAGE_PHOTO',         3),
    ('CAR_ACCIDENT', 'ACCIDENT_NOTICE',      1),
    ('CAR_ACCIDENT', 'DRIVER_LICENSE',       1),
    ('CAR_ACCIDENT', 'VEHICLE_REGISTRATION', 1),
    ('CAR_DAMAGE',   'DAMAGE_PHOTO',         3),
    ('CAR_DAMAGE',   'VEHICLE_REGISTRATION', 1),
    ('CAR_DAMAGE',   'POLICE_REPORT',        1),
    ('CAR_GLASS',    'DAMAGE_PHOTO',         2),
    ('CAR_GLASS',    'VEHICLE_REGISTRATION', 1);

insert into directions (code, name, description) values
    ('AUTO_SIMPLE',   'Простые автокейсы',   'Один участник, лёгкие повреждения, высокая уверенность модели'),
    ('AUTO_COMPLEX',  'Сложные автокейсы',   'Несколько участников, серьёзные/множественные повреждения'),
    ('GLASS',         'Стёкла',              'Повреждение стекла без других повреждений'),
    ('MANUAL_REVIEW', 'Ручная проверка',     'Сомнительные случаи: низкая уверенность, дубли, противоречия');
