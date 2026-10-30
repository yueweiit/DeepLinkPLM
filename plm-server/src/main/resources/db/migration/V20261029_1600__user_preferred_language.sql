alter table sys_user
    add column preferred_language varchar(10) not null default 'zh-CN',
    add constraint ck_sys_user_preferred_language check (preferred_language in ('zh-CN', 'es'));
