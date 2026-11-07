-- =====================================================================
-- V7: правки по ревью.
-- 1. Клиент может удалить лишний файл из черновика — статус DELETED (содержимое удаляется из хранилища).
-- 2. dedup_key уникален только среди активных задач: завершённая задача не мешает поставить такую же снова.
-- =====================================================================

alter table claim_files drop constraint claim_files_status_check;
alter table claim_files add constraint claim_files_status_check
    check (status in ('UPLOADED', 'ACCEPTED', 'REJECTED', 'DUPLICATE', 'REPLACED', 'DELETED'));

alter table background_jobs drop constraint background_jobs_dedup_key_key;
create unique index background_jobs_dedup_active_uq on background_jobs (dedup_key)
    where status in ('PENDING', 'RUNNING');
