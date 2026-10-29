"""Verify the current registered non-cancelled order quantity basis in an isolated DB."""
import gzip,json,time
from pathlib import Path
from local_research_fixture import main,sql
from verify_local_mold_sales import verify as verify_sales
def verify(c):
 verify_sales(c)
 api,check,token,db=(c[k] for k in ['api','check','token','database']);root=Path(__file__).resolve().parents[2];source=root/'qa/source'
 item=json.loads((c['output']/'mold-sales-fixtures.json').read_text())['itemId'];alias=int(sql(db,f"select item_id from plm_collaboration_item where item_id<>{item} and phone_model_code='9988' order by item_id desc limit 1"))
 def get(path=''):return api('GET','/mold-sales'+path,token=token)
 def current():return get(f'?view=production&itemId={item}')['content'][0]
 check('registered pending orders contribute one PC and TPU per finished unit',current()['production']['orderQuantity']==3050 and current()['production']['pcTotal']==current()['production']['tpuTotal']==current()['production']['finishedTotal']==3050)
 data=[json.loads(line) for line in gzip.open(source/'snapshots'/(source/'active-snapshot').read_text(),'rt')];rows=data[1:-1]
 for name,state,qty in [('deal','deal',500),('shipped','fully_shipped',700),('partial','partial_shipped',100),('arrived','fully_arrived',200),('cancelled','cancelled',50)]:rows.append({**rows[0],'id':'order-basis-'+name,'orderNo':'QA-'+name,'status':state,'quantity':str(qty)})
 def refresh(name):
  out=source/'snapshots'/name
  with gzip.open(out,'wt') as f:
   for row in [{**data[0],'rows':len(rows)},*rows,{'kind':'footer','rows':len(rows),'complete':True}]:f.write(json.dumps(row)+'\n')
  (source/'active-snapshot').write_text(name);api('POST','/mold-sales/sync',token=token)
  for _ in range(100):
   state=get('/sync')
   if state['status']!='running':break
   time.sleep(.2)
  check('order quantity projection refresh succeeds',state['status']=='completed')
 refresh('crm-sales-qa-order-basis.jsonl.gz');g=current()
 check('all non-cancelled registered orders count regardless of production or delivery',g['production']['orderQuantity']==4550 and g['salesQuantity']==4550 and g['delivery']['pendingQuantity']==1250 and g['delivery']['partialOrderQuantity']==100)
 sql(db,f'update plm_collaboration_item set pc_total=6000,tpu_total=2000,finished_total=3500 where item_id={item};update plm_collaboration_item set pc_total=5500,tpu_total=7000,finished_total=3000 where item_id={alias}')
 p=current()['production'];check('shared manual totals and order basis use component maxima, never sums',p['pcTotal']==6000 and p['tpuTotal']==7000 and p['finishedTotal']==4550)
 before=sql(db,f"select pc_total||','||tpu_total||','||finished_total from plm_collaboration_item where item_id={item}");refresh('crm-sales-qa-order-basis-repeat.jsonl.gz')
 check('repeat updates do not accumulate quantities or rewrite manual facts',current()['production']==p and before==sql(db,f"select pc_total||','||tpu_total||','||finished_total from plm_collaboration_item where item_id={item}"))
 evidence=get(f'/groups/{item}?view=production');states={o['orderStatus'] for o in evidence['orders']}
 check('order evidence retains cancelled and pending source states with their actual eligibility',{'pending','closed','deal','fully_shipped','partial_shipped','fully_arrived','cancelled'}<=states and all(o['eligibility']=='cancelled' for o in evidence['orders'] if o['orderStatus']=='cancelled'))
 _,limited=c['account']('order-production-limited','domestic',['collaboration:view'],False);uid=int(sql(db,"select user_id from sys_user where username='order-production-limited'"));sql(db,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({uid},'item',{item})")
 visible=api('GET',f'/mold-sales?view=production&itemId={item}',token=limited['token'])['content'][0];check('hidden manual archives remain excluded from restricted scope',len(visible['productionRecords'])==1 and visible['production']['tpuTotal']==4550 and 'cost' not in visible)
 api('GET',f'/mold-sales/groups/{alias}?view=production',token=limited['token'],success=False);api('POST','/mold-sales/recalculate',token=limited['token'],success=False)
 line=g['productLineId'];unknown=int(sql(db,f"insert into plm_collaboration_item(product_line_id,style_name,compatible_models,created_by,updated_by) values({line},'QA unknown production','unverified','fixture','fixture') returning item_id"))
 p=get(f'?view=production&itemId={unknown}')['content'][0]['production'];check('unlinked quantity remains unknown',all(p[k] is None for k in ['orderQuantity','pcTotal','tpuTotal','finishedTotal']))
 result=api('POST','/collaboration-plans/batch-save',{'rows':[{'entityType':'item','id':item,'changes':[{'field':'finishedTotal','before':3500,'after':3600}]}]},token=token);check('manual corrections use the original audited ledger',result[0]['status']=='saved' and current()['production']['finishedTotal']==4550)
 rows=[{**row,'quantity':'1500'} if row['id']=='A' else row for row in rows];refresh('crm-sales-qa-order-basis-amended.jsonl.gz');p=current()['production'];check('amended quantities replace the basis and retain larger manual values',p['orderQuantity']==5050 and p['pcTotal']==6000 and p['tpuTotal']==7000 and p['finishedTotal']==5050)
 check('filters and page totals use the same order quantity calculation',get('?view=production&filter=withProduction')['totalOrderQuantity']==5050 and get('?view=production&filter=withProduction')['totalElements']==1)
 for suffix in ['?view=pendingDelivery','?view=production&filter=withPendingDelivery']:api('GET','/mold-sales'+suffix,token=token,success=False)
 check('obsolete delivery-specific selectors are removed',True)
 baseline=current()['production'];rows.extend([{**rows[0],'id':'intermediate-'+step,'orderNo':'QA-intermediate-'+step,'code':'NBA'+step+'998702','quantity':'9000','status':'pending'} for step in ['1010','1020','2030','3010','3020']]);refresh('crm-sales-qa-intermediate-orders.jsonl.gz')
 check('registered intermediate orders never count as finished cases or inflate PC TPU basis',current()['production']==baseline)
 sql(db,"update plm_mold_sales_line set eligibility='eligible' where source_id like 'intermediate-%'")
 check('legacy cached intermediate classifications cannot bypass current final-stage rules',current()['production']==baseline and all(o['eligibility']=='not_finished_stage' for o in get(f'/groups/{item}?view=production')['orders'] if o['sourceId'].startswith('intermediate-')))
 detail=get(f'/groups/{item}?view=production');check('phone breakdown uses the same final-stage and order-scope calculation',sum(row['quantity'] for row in detail['phoneSales'])==baseline['orderQuantity'])
 (c['output']/'production-fixture.json').write_text(json.dumps({'itemId':item,'aliasId':alias,'unknownId':unknown}))
if __name__=='__main__':main(verify)
