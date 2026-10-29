#!/usr/bin/env python3
"""Apply a reviewed local sales reconciliation, preserving CRM and engineering facts.

The JSON is an explicit reviewed batch, not an automatic fuzzy-name importer.
Run first against a disposable PostgreSQL clone. Requires an existing administrator.
"""
import argparse,hashlib,json,re,subprocess
from pathlib import Path

def quote(value):return "'"+str(value).replace("'","''")+"'"
def prepare_statement(payload,operator):
    assert payload['catalogUrl']=='https://lemos-case.com/home'
    assert re.fullmatch(r'[0-9a-f]{64}',payload['sourceHash'])
    keys=set()
    for d in payload['decisions']:
        k=(d['styleCode'],d['phoneCode']);assert k not in keys;keys.add(k)
        assert re.fullmatch(r'[A-Z]{2}',k[0]) and re.fullmatch(r'[0-9]{4}',k[1])
        assert d['decision'] in ['linked','pending','ignored'] and d['reason'] and d['sourceNames']
        if d['decision']=='linked':
            assert d['targetItemId']>0 and d['targetBasis']['modelName']
            if 'codeBasis' in d:
                assert d['codeBasis']['kind']=='mold_catalog' and d['codeBasis']['codes']
                for source in d['codeBasis']['codes']:
                    assert source['sourceCodeId']>0 and re.fullmatch(r'M[A-Z]{2}[0-9]{6}',source['moldCode'])
                    assert source['moldCode'][1:3]==d['styleCode'] and source['moldCode'][5:]==d['targetBasis']['phoneCode']
    raw=json.dumps(payload,ensure_ascii=False,sort_keys=True,separators=(',',':'));digest=hashlib.sha256(raw.encode()).hexdigest()
    # Database checks, append and mandatory audit are one transaction. Duplicate batches do nothing.
    statement=f"""set search_path=plm,public;
begin;
select pg_advisory_xact_lock(202610161830);
create temporary table review_input(data jsonb) on commit drop;
insert into review_input values({quote(raw)}::jsonb);
do $review$
declare r jsonb; target record;
begin
 if not exists(select 1 from sys_user where username={quote(operator)} and all_permissions and status='active') then raise exception 'Administrator required'; end if;
 if exists(select 1 from plm_mold_sales_reconciliation where batch_hash={quote(digest)}) then return; end if;
 if (select source_hash from plm_mold_sales_snapshot order by (snapshot_id=(select snapshot_id from plm_mold_sales_line limit 1)) desc nulls last,created_at desc,snapshot_id desc limit 1)<>{quote(payload['sourceHash'])} then raise exception 'CRM snapshot changed; recheck first'; end if;
 if coalesce((select batch_hash::text from plm_mold_sales_reconciliation order by created_at desc,batch_id desc limit 1),'')<>{quote(payload.get('previousBatchHash',''))} then raise exception 'Review baseline changed'; end if;
 for r in select jsonb_array_elements(data->'decisions') from review_input loop
  if not exists(select 1 from plm_mold_sales_line where style_code=r->>'styleCode' and phone_code=r->>'phoneCode' and eligibility in ('eligible','not_finished_stage')) then raise exception 'Source pair missing'; end if;
  if exists(select 1 from plm_mold_sales_line where style_code=r->>'styleCode' and phone_code=r->>'phoneCode' and eligibility in ('eligible','not_finished_stage') and not (r->'sourceNames' @> jsonb_build_array(coalesce(source_data->>'name','')))) then raise exception 'Source names changed'; end if;
  if r->>'decision'='linked' then
   select p.product_specific_code style,coalesce(nullif(i.mold_name,''),i.compatible_models) name,coalesce(c.code_value,i.phone_model_code,'') code into target from plm_collaboration_item i join plm_product p on p.product_id=i.product_line_id and p.deleted_flag=0 left join plm_code_item c on c.code_item_id=i.phone_model_code_item_id where i.item_id=(r->>'targetItemId')::bigint and i.deleted_flag=0;
   if not found or target.style<>r->>'styleCode' or target.name<>r->'targetBasis'->>'modelName' or target.code<>r->'targetBasis'->>'phoneCode' then raise exception 'Target archive changed or style differs'; end if;
   if r ? 'codeBasis' then
    if r->'codeBasis'->>'kind'<>'mold_catalog' or jsonb_array_length(r->'codeBasis'->'codes')=0 or exists(
     select 1 from jsonb_array_elements(r->'codeBasis'->'codes') evidence where not exists(
      select 1 from plm_mold_code_source s where s.source_code_id=(evidence->>'sourceCodeId')::bigint and not s.source_disabled
       and upper(btrim(s.mold_code))=evidence->>'moldCode' and substring(upper(btrim(s.mold_code)),2,2)=r->>'styleCode'
       and substring(btrim(s.mold_code),6,4)=r->'targetBasis'->>'phoneCode')) then raise exception 'Mold code source changed';end if;
   end if;
  end if;
 end loop;
end $review$;
with saved as (
 insert into plm_mold_sales_reconciliation(batch_id,batch_hash,source_hash,catalog_url,catalog_captured_at,catalog,decisions,created_by)
 select gen_random_uuid(),{quote(digest)},data->>'sourceHash',data->>'catalogUrl',(data->>'catalogCapturedAt')::timestamptz,data->'catalog',data->'decisions',{quote(operator)} from review_input
 on conflict(batch_hash) do nothing returning batch_id
)
insert into plm_operation_log(operator_user_id,operator_user_name,action,business_type,business_id,business_name,result,detail_json,created_by,updated_by)
select u.user_id,{quote(operator)},'MOLD_SALES_RECONCILE','MOLD_SALES',s.batch_id::text,'历史订单与现有模具核对','success',jsonb_build_object('batchHash',{quote(digest)},'sourceHash',{quote(payload['sourceHash'])},'decisions',{len(keys)})::text,{quote(operator)},{quote(operator)} from saved s join sys_user u on u.username={quote(operator)};
commit;
"""
    return digest,statement

def apply(database,payload,operator,container='yuewei-plm-postgres-local'):
    assert re.fullmatch(r'[A-Za-z0-9_]+',database),'Invalid database'
    digest,statement=prepare_statement(payload,operator)
    subprocess.run(['docker','exec','-i',container,'psql','-X','-q','-U','postgres','-d',database,'-v','ON_ERROR_STOP=1'],input=statement,text=True,capture_output=True,check=True)
    return {'batchHash':digest,'decisions':len(payload['decisions'])}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--review',required=True,type=Path);p.add_argument('--database',required=True);p.add_argument('--operator',required=True);p.add_argument('--apply',action='store_true');a=p.parse_args();payload=json.loads(a.review.read_text())
    if not a.apply:print(json.dumps({'dryRun':True,'counts':{k:sum(d['decision']==k for d in payload['decisions']) for k in ['linked','pending','ignored']}}))
    else:print(json.dumps(apply(a.database,payload,a.operator)))
