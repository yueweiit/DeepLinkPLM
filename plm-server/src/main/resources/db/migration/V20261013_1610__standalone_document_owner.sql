-- Retain every existing owner kind while admitting independently indexed documents.
alter table plm_attachment drop constraint if exists ck_plm_attachment_owner_type;
alter table plm_attachment add constraint ck_plm_attachment_owner_type check (
  owner_object_type in ('Customer','Product','Order','ProductionOrder','Process','Inventory','Workstation','MoldApplication','Document')
);
