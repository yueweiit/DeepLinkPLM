create table plm_master_data_source(source_system text,source_kind text,source_id text,source_code text,source_data jsonb not null default '{}', source_status text,organization_id text default 'org', object_id bigint,object_type text,phone_model_id bigint,batch_hash text default 'one',observed_at timestamp);
create table plm_master_data_import(source_hash text,status text,committed_at timestamp);
create table plm_product(product_id bigint primary key, deleted_flag int default 0, production_status text);
create table plm_phone_model_member(id bigint,code_item_id bigint);
create table plm_code_item(code_item_id bigint);
create table plm_product_bom(product_bom_id bigint primary key,product_id bigint,bom_code text,output_inventory_code text,output_inventory_name text,quantity numeric,custom_bom_specification text,version_no text,status text,confirmed_by text,confirmed_at timestamp,source_product_bom_id bigint,source_type text,deleted_flag int default 0);
create table plm_product_bom_route(product_bom_route_id bigint primary key,product_bom_id bigint,process_id bigint,route_code text,route_name text,binding_priority int, article_codes text[],status text default 'active', deleted_flag int default 0);
create table plm_product_bom_route_formal_selection(product_bom_id bigint,product_bom_route_id bigint,status text,deleted_flag int default 0);
create table plm_product_bom_item(product_bom_item_id bigint primary key, line_no int, inventory_id bigint,item_code text,item_name text,quantity numeric(18,8),uom_code text,unit_raw text,unit_cn text,unit_es text, component_row_id text, unit_cost_snapshot numeric,currency_code text, deleted_flag int default 0);
create table plm_bom_item_target(product_bom_route_id bigint,product_bom_item_id bigint,process_id bigint,article_codes text[]);
create table plm_process(process_id bigint,product_id bigint,process_type text,parent_process_id bigint,
node_code text,local_node_code text,process_name text,sequence_no int,position_x int,position_y int,operation_master_process_id bigint,process_code text,process_param_json jsonb,deleted_flag int default 0);
create table plm_process_edge(edge_id bigint,from_process_id bigint,to_process_id bigint,edge_no int,relation_type text,label text,condition_json jsonb,route_id bigint, deleted_flag int default 0);
create table plm_inventory(inventory_id bigint,stock_uom text);
insert into plm_master_data_import values('one','committed',now());
insert into plm_product(product_id) values(100),(101),(102),(103),(104),(200),(300);
insert into plm_master_data_source(source_system,source_kind,source_id,source_code,object_id,object_type,source_data) values
 ('crm','product_series','series-id','SER',100,'product','{"seriesNameZh":"款一","seriesNameEs":"uno"}'),
 ('mes','bom_main','bm1','BORIG',1,'bom','{"version":"V1"}'),
 ('mes','bom_main','bm4','BDIFF',4,'bom','{"version":"W3"}');
insert into plm_master_data_source(source_system,source_kind,source_id,source_code,object_id,object_type,source_data)
select 'crm','product_sku',id,code,obj,'product',jsonb_build_object('seriesCode','SER','productSeriesId','series-id','productName','款一','phoneModelId',phone,'modelCode',mcode,'modelName',model,'colorShortCode',short,'colorNameZh',color,'colorNameEn',en,'colorNameEs',es,'colorCode',en,'basePrice',300) from (values
 ('a','SKU01',101,'m1','0100','Redmi','01','紫色','Purple','Morado'),('b','SKU02',102,'m1','0100','Redmi','02','黑色','Black','Negro'),
 ('c','SKU01',103,'m2','0100','Redmi/Max','01','紫色','Purple','Morado'),('d','SKU03',104,'m3','0099','HIDE','01','紫色','Purple','Morado'),
 ('e','SKU04',200,'m4','0098','Other','01','紫色','Purple','Morado')) f(id,code,obj,phone,mcode,model,short,color,en,es);
insert into plm_master_data_source(source_system,source_kind,source_id,source_status,source_data) values ('mes','product_route_binding','bind','enabled','{"product_code":"SER","route_code":"R","condition_json":{"color_names":["紫色","黑色"]}}'),('mes','product_route_binding','bind-other','enabled','{"product_code":"SER","route_code":"S","condition_json":null}');
insert into plm_product_bom(product_bom_id,product_id,bom_code,output_inventory_code,output_inventory_name,quantity,version_no,status,source_product_bom_id,source_type) values
(1,100,'B1','SER','款一',12,'MES-V1-xx','released',null,'mes_baseline'),(2,100,'B2','SER','款一',12,'PLM-007','reviewing',1,'mes_derivative'),
(3,100,'B3','SER','款一',12,'PLM-005','released',1,'mes_derivative'),(4,100,'B4','SER','款一',12,'MES-W3-xx','frozen',null,'mes_baseline');
insert into plm_product_bom_route(product_bom_route_id,product_bom_id,process_id,route_code,route_name,binding_priority,article_codes) values
(10,1,1000,'R','染色',90,'{SKU01,SKU02,SKU03}'),(20,2,2000,'R','染色',90,'{SKU01,SKU02}'),(21,2,2100,'S','其他路线',90,'{SKU02,SKU03,SKU99}'),(30,3,3000,'R','染色',90,'{SKU01,SKU02}'),(40,4,4000,'R','染色',40,'{SKU01,SKU04}');
insert into plm_product_bom_route_formal_selection values (1,10,'invalidated',0),(3,30,'active',0);
insert into plm_process(process_id,product_id,process_type,process_name) select id,100,'routing','路线' from unnest(array[1000,2000,2100,3000,4000]) id;
insert into plm_process(process_id,product_id,process_type,parent_process_id,process_name,sequence_no,node_code) select id*100+1,100,'operation',id,'组装和打包',1, id||'-node1' from unnest(array[1000,2000,2100,3000,4000]) id;
insert into plm_product_bom_item(product_bom_item_id,line_no,inventory_id,item_code,item_name,quantity,uom_code) select route,1,500, 'MAT'||route,'材料'||route,0.20000000,'件' from unnest(array[10,20,21,30,40]) route;
insert into plm_bom_item_target select product_bom_route_id,product_bom_route_id,process_id*100+1,null from plm_product_bom_route;
