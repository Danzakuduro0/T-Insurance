-- =====================================================================
-- V5: текст для клиента отдельно от внутренней причины (вопрос 12)
-- reason        — внутренняя причина перехода (журнал, карточка оператора)
-- client_comment — что клиент видит в таймлайне; null — ничего не показываем
-- =====================================================================

alter table claim_status_history add column client_comment text;

-- Прежние системные пояснения о недостающих материалах предназначались клиенту
update claim_status_history set client_comment = reason
    where actor_type = 'SYSTEM' and to_status = 'NEEDS_INFO' and reason is not null;

drop view claim_client_timeline;
create view claim_client_timeline as
select h.claim_id,
       h.id                                        as history_id,
       h.created_at,
       h.to_status                                 as status,
       coalesce(h.client_title, s.name_for_client) as title,
       h.client_comment                            as comment
from claim_status_history h
join claim_statuses s on s.code = h.to_status
where h.visible_to_client;
