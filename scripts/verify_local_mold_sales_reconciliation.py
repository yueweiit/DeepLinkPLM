#!/usr/bin/env python3
"""Reconciliation regression and real reviewed-batch verification in a disposable DB."""
import gzip,json,subprocess,time
from pathlib import Path
from local_research_fixture import main,sql,quote
from verify_local_order_production import verify as verify_orders
from apply_mold_sales_review import apply

def verify(c):
    api,check,token,db=(c[k] for k in ['api','check','token','database']);root=Path(__file__).resolve().parents[2];source=root/'qa/source';review=json.loads((root/'review-order-basis.json').read_text())
    def get(path=''):return api('GET','/mold-sales'+path,token=token)
    def sync():
        api('POST','/mold-sales/sync',token=token)
        for _ in range(150):
            job=get('/sync')
            if job['status']!='running':break
            time.sleep(.2)
        check('fixture refresh complete',job['status']=='completed')
    # Real high-confidence batch, unchanged CRM lines, engineering fits and manual production.
    api('POST','/mold-sales/recalculate',token=token);sql(db,'delete from plm_mold_sales_reconciliation');review['previousBatchHash']='';audit_before=int(sql(db,"select count(*) from plm_operation_log where action='MOLD_SALES_RECONCILE'"));before=get('?size=100');original=sql(db,"select md5(string_agg(to_jsonb(l)::text,',' order by source_id)) from plm_mold_sales_line l")
    applied=apply(db,review,'research-qa-admin');apply(db,review,'research-qa-admin');after=get('?size=100');production=get('?view=production&size=100')
    check('reviewed identities reconcile registered finished orders once under the final-step scope',after['totalSalesQuantity']>before['totalSalesQuantity'] and production['totalOrderQuantity']==6226067)
    check('out-of-scope history is separate from the 120 pending combinations',after['ignoredQuantity']==210866 and after['unassignedQuantity']==421177 and len(get('/unmatched'))==120 and production['ignoredQuantity']==210866 and production['unassignedQuantity']==421177)
    check('real batch is idempotent and does not rewrite CRM rows or assert physical fit',sql(db,'select count(*) from plm_mold_sales_reconciliation')=='1' and int(sql(db,"select count(*) from plm_operation_log where action='MOLD_SALES_RECONCILE'"))==audit_before+1 and sql(db,"select md5(string_agg(to_jsonb(l)::text,',' order by source_id)) from plm_mold_sales_line l")==original and sql(db,'select count(*) from plm_collaboration_phone_set_compatibility')=='0')
    shared=get('/groups/1427?view=production');check('explicitly grouped archive combines old codes once without adding a physical set',set(shared['group']['phoneCodes'])=={'1283','1284'} and len(shared['group']['salesAliases'])==2 and shared['group']['physicalSets']==0)
    check('original codes and names remain visible alongside the review evidence',get('/groups/617')['group']['salesAliases'][0]['phoneCode']=='1176' and any(o['phoneCode']=='1176' for o in get('/groups/617?page=12')['orders']))
    (c['output']/'real-review-results.json').write_text(json.dumps({'batch':applied,'sales':after,'production':production,'pending':get('/unmatched')},ensure_ascii=False))
    sql(db,'delete from plm_mold_sales_reconciliation');verify_orders(c)
    fixture=json.loads((c['output']/'production-fixture.json').read_text());item=fixture['itemId'];g=get(f'?itemId={item}')['content'][0]
    snapshot=sql(db,'select snapshot_id from plm_mold_sales_snapshot order by created_at desc,snapshot_id desc limit 1')
    for code,qty in [('8887',20),('8886',10)]:
        sql(db,f"insert into plm_mold_sales_line values('review-{code}','{snapshot}','review-order-{code}','review-{code}','closed','NBA4030{code}02','BA','{code}',{qty},'eligible',jsonb_build_object('name','Historical {code}','businessDate','2026-10-01'))")
    record=next(r for r in g['productionRecords'] if r['itemId']==item)
    payload={'sourceHash':get('/status')['sourceHash'],'catalogUrl':'https://lemos-case.com/home','catalogCapturedAt':review['catalogCapturedAt'],'catalog':{},'decisions':[
        {'styleCode':'BA','phoneCode':'8888','sourceNames':['QA sample order'],'decision':'linked','reason':'QA explicit grouped model','targetItemId':item,'targetBasis':{'modelName':record['modelName'],'phoneCode':record['phoneCode']}},
        {'styleCode':'BA','phoneCode':'8887','sourceNames':['Historical 8887'],'decision':'ignored','reason':'QA not listed'},
        {'styleCode':'BA','phoneCode':'8886','sourceNames':['Historical 8886'],'decision':'pending','reason':'QA variant needs confirmation','websiteNames':['QA current model'],'candidateIds':[item],'candidateLabels':['QA existing mold']}]}
    apply(db,payload,'research-qa-admin');check('alias extends the existing group without double-counting manual production or mold costs',get(f'?itemId={item}')['content'][0]['production']['orderQuantity']==5080 and get(f'?itemId={item}')['content'][0]['cost']['costSets']==1)
    pending=get('/unmatched');check('pending details include original names, candidates and reason',len(pending)==1 and pending[0]['phoneCode']=='8886' and pending[0]['names']==['Historical 8886'] and pending[0]['reviewReason']=='QA variant needs confirmation')
    line=g['productLineId'];direct=int(sql(db,f"insert into plm_collaboration_item(product_line_id,style_name,compatible_models,phone_model_code,created_by,updated_by) values({line},'QA direct archive','QA explicit model','8888','fixture','fixture') returning item_id"))
    check('a later direct archive takes precedence and never duplicates an alias quantity',get(f'?itemId={item}')['content'][0]['production']['orderQuantity']==5050 and get(f'?itemId={direct}')['content'][0]['production']['orderQuantity']==30)
    sql(db,f'update plm_collaboration_item set deleted_flag=1 where item_id={direct}')
    sql(db,f"update plm_collaboration_item set compatible_models='QA changed target' where item_id={item}")
    check('target changes suspend the historical alias for review',get(f'?itemId={item}')['content'][0]['production']['orderQuantity']==5050 and any(r['phoneCode']=='8888' and '档案已调整' in r['reviewReason'] for r in get('/unmatched')))
    sql(db,f"update plm_collaboration_item set compatible_models={quote(record['modelName'])} where item_id={item}")
    sql(db,"update plm_mold_sales_line set source_data=jsonb_set(source_data,'{name}','\"Different reused-code name\"') where source_id='review-8887'")
    check('a reused code with a new source name reopens an ignored decision',any(r['phoneCode']=='8887' and '新名称' in r['reviewReason'] for r in get('/unmatched')))
    prior=sql(db,'select batch_hash from plm_mold_sales_reconciliation order by created_at desc,batch_id desc limit 1')
    stage={**payload,'previousBatchHash':prior,'decisions':[{'styleCode':'BA','phoneCode':'9987','sourceNames':['QA sample order'],'decision':'pending','reason':'QA recorded intermediate source'}]}
    sql(db,"update plm_mold_sales_line set eligibility='not_finished_stage' where source_id like 'intermediate-%'")
    apply(db,stage,'research-qa-admin');check('excluded intermediate source remains reviewable without inflating finished quantities',get(f'?itemId={item}')['content'][0]['production']['orderQuantity']==5080)
    counts=sql(db,'select count(*) from plm_mold_sales_reconciliation');bad={**payload,'previousBatchHash':'wrong'}
    try:apply(db,bad,'research-qa-admin');raise AssertionError('baseline must reject')
    except subprocess.CalledProcessError:pass
    check('concurrent review baseline rejection is atomic',counts==sql(db,'select count(*) from plm_mold_sales_reconciliation'))
    _,limited=c['account']('review-limited','domestic',['collaboration:view'],False)
    api('GET','/mold-sales/unmatched',token=limited['token'],success=False)
    check('global review data remains administrator-only',api('GET','/mold-sales',token=limited['token'])['totalElements']==0)
    # Restore real projection and reviewed batch for UI checks, only within this fixture.
    sql(db,'delete from plm_mold_sales_reconciliation')
    original=source/'snapshots'/(root/'original-snapshot-name').read_text();fresh=source/'snapshots/crm-sales-qa-real-restored.jsonl.gz'
    with gzip.open(original,'rb') as inp,gzip.open(fresh,'wb') as out:out.write(inp.read())
    (source/'active-snapshot').write_text(fresh.name);sync();review['sourceHash']=get('/status')['sourceHash'];apply(db,review,'research-qa-admin')

if __name__=='__main__':main(verify)
