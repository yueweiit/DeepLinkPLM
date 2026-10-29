#!/usr/bin/env python3
"""Exercise sales/cost operations only in the disposable local database fixture."""
import gzip,json,time,uuid
from pathlib import Path
from decimal import Decimal
from local_research_fixture import main,sql,quote

def verify(c):
    api,check,token,db,account=(c[k] for k in ['api','check','token','database','account']);root=Path(__file__).resolve().parents[2];source=root/'qa/source'
    def get(path,auth=token):return api('GET','/mold-sales'+path,token=auth)
    def sync(success=True):
        start=api('POST','/mold-sales/sync',token=token)
        for _ in range(120):
            job=get('/sync')
            if job['status']!='running':break
            time.sleep(.25)
        if success:check('sync completes without remote writes',job['status']=='completed')
        else:check('failed source import keeps a clear failure status',job['status']=='failed')
        return job
    first=sync();status=get('/status');check('real 2025-current snapshot is complete and scoped',status['sourceRows']==48255 and status['periodStart']=='2025-01-01' and status['periodEnd']=='2026-10-16')
    p=get('?size=100');check('real sales and cost groups are available',p['totalElements']>0 and p['totalSalesQuantity']>0)
    sqlcount=sql(db,"select count(*) from plm_mold_sales_line");history=get('/history');sync();check('repeated identical snapshot is idempotent',len(get('/history'))==len(history) and sqlcount==sql(db,'select count(*) from plm_mold_sales_line'))
    # A deliberately isolated, small shared group. No real project or production fields are edited.
    line=int(sql(db,"select product_id from plm_product where product_specific_code='BA' and deleted_flag=0 and product_type='product_line' limit 1"))
    item=int(sql(db,f"insert into plm_collaboration_item(product_line_id,style_name,compatible_models,phone_model_code,created_by,updated_by) values({line},'QA 亮甲共模','QA Phone A / Phone B','9987','fixture','fixture') returning item_id"))
    second=int(sql(db,f"insert into plm_collaboration_item(product_line_id,style_name,compatible_models,phone_model_code,created_by,updated_by) values({line},'QA 亮甲别名','QA Phone B archive','9988','fixture','fixture') returning item_id"))
    mold=int(sql(db,f"insert into plm_collaboration_mold_set(item_id,set_no,set_name,created_by,updated_by) values({item},1,'QA shared set','fixture','fixture') returning mold_set_id"))
    sql(db,f"insert into plm_collaboration_mold_part(mold_set_id,part_type,physical_status) values({mold},'PC','confirmed'),({mold},'TPU','confirmed')")
    brand=int(sql(db,'select id from plm_phone_brand order by id limit 1'))
    for code in ['9987','9988']:
        codeid=int(sql(db,f"insert into plm_code_item(code_type,code_value,code_name) values('phone_model','{code}','QA shared {code}') returning code_item_id"))
        phone=int(sql(db,f"insert into plm_phone_model_member(brand_id,model_name,code_item_id,created_by,updated_by) values({brand},'QA shared phone {code}',{codeid},'fixture','fixture') returning id"))
        sql(db,f"insert into plm_collaboration_phone_member(item_id,phone_model_id,pc_confirmed,tpu_confirmed,evidence,mold_set_ids,created_by,updated_by) values({item},{phone},true,true,'QA fit',array[{mold}]::bigint[],'fixture','fixture');insert into plm_collaboration_phone_set_compatibility(item_id,phone_model_id,mold_set_id,pc_confirmed,tpu_confirmed,pc_evidence,tpu_evidence,created_by,updated_by) values({item},{phone},{mold},true,true,'QA PC fit','QA TPU fit','fixture','fixture')")
    def row(i,code,qty,status='closed',date='2026-10-01',**extra):return {'kind':'line','id':i,'orderId':'order-'+i,'orderNo':'QA-'+i,'status':status,'deleted':0,'businessDate':date,'date':date,'quantity':str(qty),'code':code,'name':'QA sample order',**extra}
    facts=[row('A','NBA4030998702',1000),row('B','NBA4030998807',2000),row('C','NBA4030998702',100,'cancelled'),row('D','NBA4030998702',50,'pending'),row('E','NBA4030998702',40,phoneCode='0001'),row('F','MBA209987',99),row('G','NBA4030888802',30)]
    def snapshot(name,rows,footer_count=None):
        target=source/'snapshots'/name
        with gzip.open(target,'wt') as f:
            for r in [{'kind':'header','format':1,'organization':'100001','organizations':1,'rows':len(rows),'capturedAt':'2026-10-16T00:00:00Z','periodStart':'2025-01-01','periodEnd':'2026-10-16'},*rows,{'kind':'footer','rows':len(rows) if footer_count is None else footer_count,'complete':True}]:f.write(json.dumps(r)+'\n')
        (source/'active-snapshot').write_text(name)
    snapshot('crm-sales-qa-valid.jsonl.gz',facts);sync();p=get(f'?itemId={item}');g=p['content'][0]
    check('confirmed shared phones combine both models and both colors once',g['salesQuantity']==3050 and len(g['phoneCodes'])==2 and len(g['itemIds'])==2 and p['totalElements']==1)
    check('one complete set has one cost and correct per-unit allocation',Decimal(str(g['cost']['totalCost']))==Decimal('21702.30') and Decimal(str(g['cost']['perUnitCost']))==Decimal('7.1155') and g['cost']['costSets']==1 and g['cost']['breakEvenUnits']==6782)
    d=get(f'/groups/{item}');states={r['eligibility'] for r in d['orders']};check('registered pending orders count while cancelled and conflicting evidence stays inspectable',{'eligible','cancelled','identity_conflict'}<=states and d['totalElements']==5)
    check('unassigned sales remain separate from visible group totals',get('?filter=withSales')['unassignedQuantity']>=30 and any(r['phoneCode']=='8888' for r in get('/unmatched')))
    _,limited=account('mold-sales-limited','domestic',['collaboration:view'],False)
    limited_id=int(sql(db,"select user_id from sys_user where username='mold-sales-limited'"));sql(db,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({limited_id},'item',{item})")
    visible=get('',limited['token']);check('scope and amount rules apply to the projection',visible['totalElements']==1 and 'cost' not in visible['content'][0] and not visible['status']['canReadCost'] and 'sourceSummary' not in visible['status'] and 'unassignedQuantity' not in visible)
    for path in ['/sync','/history','/unmatched']:api('GET','/mold-sales'+path,token=limited['token'],success=False)
    api('POST','/mold-sales/sync',token=limited['token'],success=False);check('restricted user cannot sync or read global history',True)
    checksum=sql(db,"select md5(string_agg(source_id||quantity||eligibility,',' order by source_id)) from plm_mold_sales_line")
    for name,bad in [('old-date',[row('OLD','NBA4030998702',9999,date='2024-12-31')]),('future-date',[row('FUTURE','NBA4030998702',9999,date='2026-10-17')])]:
        snapshot('crm-sales-qa-'+name+'.jsonl.gz',bad);sync(False);check(name+' cannot change the current projection',checksum==sql(db,"select md5(string_agg(source_id||quantity||eligibility,',' order by source_id)) from plm_mold_sales_line"))
    snapshot('crm-sales-qa-incomplete.jsonl.gz',facts,99);sync(False);check('partial snapshot is rolled back atomically',checksum==sql(db,"select md5(string_agg(source_id||quantity||eligibility,',' order by source_id)) from plm_mold_sales_line"))
    updated=[{**r,'quantity':'500'} if r['id']=='A' else r for r in facts];snapshot('crm-sales-qa-updated.jsonl.gz',updated);sync();g=get(f'?itemId={item}')['content'][0];check('order amendments replace current quantities instead of accumulating',g['salesQuantity']==2550 and len(get('/history'))==len(history)+2)
    snapshot('crm-sales-qa-valid-again.jsonl.gz',facts);sync()
    (c['output']/'mold-sales-fixtures.json').write_text(json.dumps({'itemId':item,'groupId':g['groupId'],'sourceRoot':str(source)}))

if __name__=='__main__':main(verify)
