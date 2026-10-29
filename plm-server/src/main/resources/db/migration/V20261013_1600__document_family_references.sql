-- Retrieval indexes are mutable; a released revision keeps a separate immutable snapshot.
create table plm_document_reference (
  family_id bigint not null references plm_attachment(attachment_id),
  object_type varchar(32) not null check(object_type in ('product','sku','item','mold_code','source_code','mold_set')),
  object_id bigint not null check(object_id > 0),
  part_type varchar(8) not null default 'ALL' check(part_type in ('ALL','PC','TPU')),
  created_at timestamp not null default now(), created_by varchar(128) not null,
  primary key(family_id,object_type,object_id,part_type)
);
create index ix_document_reference_object on plm_document_reference(object_type,object_id,family_id);
create index if not exists ix_attachment_family_live on plm_attachment((coalesce(family_id,attachment_id)),created_at desc,attachment_id desc) where deleted_flag=0;
create table plm_document_reference_history (
  history_id bigserial primary key, family_id bigint not null references plm_attachment(attachment_id),
  before_references jsonb not null, after_references jsonb not null,
  reason text not null, actor_id bigint not null, actor_name varchar(128) not null,
  changed_at timestamp not null default now()
);
create index ix_document_reference_history on plm_document_reference_history(family_id,history_id desc);
alter table plm_attachment add column publication_references_json text;

-- Only existing foreign-key relationships are migrated. No SKU, code or physical set is inferred.
insert into plm_document_reference(family_id,object_type,object_id,created_by)
select distinct coalesce(a.family_id,a.attachment_id),case when p.product_type='sku' then 'sku' else 'product' end,p.product_id,'document-migration'
from plm_attachment a join plm_product p on p.product_id=a.owner_object_id
where a.deleted_flag=0 and p.deleted_flag=0 and lower(a.owner_object_type) in ('product','project')
on conflict do nothing;
insert into plm_document_reference(family_id,object_type,object_id,created_by)
select distinct coalesce(a.family_id,a.attachment_id),'item',a.collaboration_item_id,'document-migration'
from plm_attachment a join plm_collaboration_item i on i.item_id=a.collaboration_item_id
where a.deleted_flag=0 and i.deleted_flag=0 on conflict do nothing;
insert into plm_document_reference(family_id,object_type,object_id,created_by)
select distinct coalesce(a.family_id,a.attachment_id),'mold_set',a.mold_set_id,'document-migration'
from plm_attachment a join plm_collaboration_mold_set s on s.mold_set_id=a.mold_set_id
where a.deleted_flag=0 and s.deleted_flag=0 on conflict do nothing;

update plm_attachment a set publication_references_json=(
 select coalesce(jsonb_agg(jsonb_build_object('type',r.object_type,'id',r.object_id,'partType',r.part_type)),'[]'::jsonb)::text
 from plm_document_reference r where r.family_id=coalesce(a.family_id,a.attachment_id)
 and ((r.object_type in ('product','sku') and r.object_id=a.owner_object_id)
   or (r.object_type='item' and r.object_id=a.collaboration_item_id)
   or (r.object_type='mold_set' and r.object_id=a.mold_set_id))
) where a.released_at is not null;
