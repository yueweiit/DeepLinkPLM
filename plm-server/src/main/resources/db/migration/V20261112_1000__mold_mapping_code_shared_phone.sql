alter table plm_mold_entity_code_mapping
    drop constraint if exists plm_mold_entity_code_mapping_mapping_code_key;

create unique index if not exists ux_mold_entity_code_phone
    on plm_mold_entity_code_mapping(mapping_code, phone_model_id);
