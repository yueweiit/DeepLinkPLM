"""Synthetic evidence: writes exclusively to the isolated acceptance database."""
import json, urllib.error
from verify_mold_registry_preview import sql, api, OUT

token=json.loads((OUT/'browser-session.private.json').read_text(encoding='utf-8'))['token']
fixture=json.loads(sql("""
insert into plm_product_mold_code(product_id,mold_code)
select 51,code from (values ('MBA101041'),('MBA201041')) c(code)
where not exists(select 1 from plm_product_mold_code where product_id=51 and mold_code=code and deleted_flag=0);
with i as (
 insert into plm_collaboration_item(style_name,mold_name,compatible_models,product_line_id,created_by,updated_by)
 values('QA-REGISTRY-隔离模拟','QA-REGISTRY-非真实模具','仅隔离模拟',51,'REGISTRY_QA','REGISTRY_QA') returning item_id
), s as (
 insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by)
 select item_id,1,'QA-REGISTRY-隔离模拟','REGISTRY_QA','REGISTRY_QA' from i returning item_id,mold_set_id
), parts as (
 insert into plm_collaboration_mold_part(mold_set_id,part_type,original_mold_code,product_mold_code_id,physical_status)
 select s.mold_set_id,c.part,c.code,mc.product_mold_code_id,'confirmed' from s
 cross join (values ('PC','MBA201041'),('TPU','MBA101041')) c(part,code)
 join plm_product_mold_code mc on mc.product_id=51 and mc.mold_code=c.code and mc.deleted_flag=0
), member as (
 insert into plm_collaboration_phone_member(item_id,phone_model_id,mold_set_ids,pc_confirmed,tpu_confirmed,evidence,created_by,updated_by)
 select item_id,1393,array[mold_set_id],true,true,'QA隔离模拟','REGISTRY_QA','REGISTRY_QA' from s
), compat as (
 insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by)
 select item_id,1393,mold_set_id,true,true,'QA模拟PC依据，非工程事实','QA模拟TPU依据，非工程事实','REGISTRY_QA','REGISTRY_QA' from s
)
select json_build_object('itemId',item_id,'moldSetId',mold_set_id) from s;
""").splitlines()[-1])
fits=api('/molds/reference-data/phone-models/1393/molds?productLineId=51',token=token)
fit=next(f for f in fits if f['moldSetId']==fixture['moldSetId'])
relation=api('/manufacturing-relations',{**fixture,'productLineId':51,'phoneModelId':1393,'holeType':'precision','structureDescription':'QA-REGISTRY-隔离模拟','sharedMaterial':False,'skuIds':[],'moldEvidence':'QA隔离模拟，非真实工程事实','materialEvidence':'','compatibilityDigest':fit['compatibilityDigest'],'reason':'独立验收准备模拟依据','status':'active'},token)
data={'productLineId':51,'sourcePhoneModelId':1393,'referencePhoneModelId':1396,'materialCode':'10','holeType':'precision','structureDescription':'QA-REGISTRY-隔离模拟','manufacturingRelationId':relation['id'],'manufacturingRelationVersion':relation['version'],'evidence':'QA隔离模拟，非真实工程事实','reason':'独立验收新增映射','status':'active'}
def reject(payload,method='POST',suffix=''):
 try: api('/mold-share-mappings'+suffix,payload,token,method)
 except (AssertionError,urllib.error.HTTPError): return True
 raise AssertionError('Expected rejection')
before=sql("select (select count(*) from plm_inventory)||'|'||(select count(*) from plm_product)||'|'||(select count(*) from plm_mold_application);")
created=api('/mold-share-mappings',data,token)
assert reject(data)
updated=api('/mold-share-mappings/'+str(created['id']),{**data,'version':created['version'],'evidence':'QA模拟更新后的依据'},token,'PUT')
assert updated['version']==created['version']+1
assert reject({**data,'version':created['version']},'PUT','/'+str(created['id']))
disabled=api('/mold-share-mappings/'+str(created['id']),{**data,'version':updated['version'],'status':'inactive','reason':'QA停用验收'},token,'PUT')
assert disabled['status']=='inactive'
assert before==sql("select (select count(*) from plm_inventory)||'|'||(select count(*) from plm_product)||'|'||(select count(*) from plm_mold_application);")
report={'isolatedDatabaseOnly':True,'fixture':fixture,'mappingId':created['id'],'create':True,'update':True,'duplicateRejected':True,'staleVersionRejected':True,'deactivate':True,'inventoryProductApplicationCountsUnchanged':True,'retainedAsInactiveSyntheticFixture':True}
(OUT/'save-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(report,ensure_ascii=False))
