#!/usr/bin/env python3
"""Isolated full partner/sample/sales chain; never writes to the source database."""
import datetime,json,uuid
from local_research_fixture import main, sql

def verify(ctx):
 api,check,token,database,account=(ctx[x] for x in ('api','check','token','database','account'))
 today=datetime.date.today().isoformat()
 def key():return uuid.uuid4().hex
 def get(kind,id):return api('GET',f'/{kind}/{id}',token=token)
 def create(kind,data):return api('POST','/'+kind,{**data,'requestId':key()},token)
 def action(kind,id,name,data=None,request=None):
  return api('POST',f'/{kind}/{id}/actions/{name}',{**(data or {}),'version':get(kind,id)['version'],'requestId':request or key()},token)
 def reject(kind,id,name,data):
  before=get(kind,id);result=api('POST',f'/{kind}/{id}/actions/{name}',{**data,'version':before['version'],'requestId':key()},token,success=False)
  check('invalid '+name+' rolls back entirely',get(kind,id)==before)
  return result
 suppliers=api('GET','/inventories/suppliers/snapshot',token=token)['suppliers']
 tim=next(x for x in suppliers if x['supplierName']=='Tim');ct=next(x for x in suppliers if x['supplierName']=='C&T')
 check('known suppliers preserve company/person identity',tim['supplierType']=='person' and ct['supplierType']=='company')
 count_before=sql(database,'select count(*) from plm_inventory;')
 news=api('POST','/inventories/suppliers',{'supplierName':'QA pure supplier','supplierType':'person','status':'draft'},token)
 check('pure supplier creates no inventory',sql(database,'select count(*) from plm_inventory;')==count_before)
 customer=create('customers',{'customerName':'QA client','customerType':'company','status':'draft'})
 cid=customer['customerId'];check('name-based customer draft is real',api('GET',f'/customers/{cid}',token=token)['customerName']=='QA client')
 p=create('partner-products',{'name':'QA case name only'})
 check('name-only product draft persists',p['specs']==[])
 p=api('PUT',f'/partner-products/{p["id"]}',{'name':'QA magnetic case','version':p['version'],'requestId':key(),'site':'QA-SITE','externalId':'QA-PRODUCT','status':'adopted','listing':'listed','specs':[{'name':'iPhone 17 clear','websiteSku':'QA-SKU-17','sources':[{'supplierCode':tim['supplierCode'],'unitPrice':'10','currency':'CNY','supplierItemCode':'QA-TIM-17'}]}]},token)
 spec=p['specs'][0]['id']
 s=create('samples',{'sourceType':'partner','specId':spec,'supplierCode':tim['supplierCode'],'qty':10,'location':'QA sample shelf','date':today})
 s=action('samples',s['id'],'retain',{'qty':1,'location':'QA sample shelf','date':today})
 s=action('samples',s['id'],'loan',{'qty':3,'location':'QA sample shelf','person':'QA borrower','customerId':cid,'due':today,'date':today})
 check('receive10 retain1 loan3',s['balances']['onhand']==7 and s['balances']['available']==6 and s['balances']['borrowed']==3)
 loan=next(e for e in s['events'] if e['action']=='loan')['id']
 s=action('samples',s['id'],'return',{'qty':2,'damaged':0,'loanId':loan,'location':'QA sample shelf','date':today})
 check('partial return preserves outstanding',s['balances']['onhand']==9 and s['balances']['available']==8 and s['balances']['borrowed']==1)
 reject('samples',s['id'],'return',{'qty':2,'damaged':0,'loanId':loan,'location':'QA sample shelf','date':today})
 s=action('samples',s['id'],'return',{'qty':1,'damaged':1,'loanId':loan,'location':'QA sample shelf','date':today})
 check('damaged return is physical but unavailable',s['balances']['onhand']==10 and s['balances']['available']==8 and s['balances']['damaged']==1)
 s=action('samples',s['id'],'move',{'qty':1,'condition':'retained','location':'QA sample shelf','to':'QA retained cabinet','date':today})
 s=action('samples',s['id'],'scrap',{'qty':1,'condition':'damaged','location':'QA sample shelf','reason':'QA damaged scrap','date':today})
 check('retained move and scrap keep available correct',s['balances']['onhand']==9 and s['balances']['available']==8)
 sale=create('partner-sales',{'saleType':'inquiry','customerId':cid,'currency':'CNY','lines':[{'specId':spec,'supplierCode':tim['supplierCode'],'qty':100,'unitPrice':'10'}]})
 sale=action('partner-sales',sale['id'],'quote_confirm')
 sale=action('partner-sales',sale['id'],'order_confirm')
 check('inquiry converts in same record with history',sale['confirmed'] and len(sale['quotes'])==1)
 sale=action('partner-sales',sale['id'],'agreement',{'supplierCode':tim['supplierCode'],'gross':'1000','fee':'100','mode':'net'})
 sale=action('partner-sales',sale['id'],'receipt',{'amount':'1000','date':today})
 line=sale['lines'][0]['id']
 sale=action('partner-sales',sale['id'],'ship',{'lineId':line,'qty':50,'date':today})
 reject('partner-sales',sale['id'],'ship',{'lineId':line,'qty':51,'date':today})
 repeated=key();data={'supplierCode':tim['supplierCode'],'amount':'900','offset':'100','date':today}
 sale=action('partner-sales',sale['id'],'supplierpay',data,repeated)
 before=len(sale['events']);sale=action('partner-sales',sale['id'],'supplierpay',data,repeated)
 check('duplicate click is idempotent',len(sale['events'])==before)
 balance=sale['stats']['settlements'][0]
 check('net settlement closes distinct balances',balance['payRemainingCents']==0 and balance['feeRemainingCents']==0)
 gross=create('partner-sales',{'saleType':'order','customerId':cid,'currency':'CNY','lines':[{'specId':spec,'supplierCode':tim['supplierCode'],'qty':100,'unitPrice':'10'}]})
 gross=action('partner-sales',gross['id'],'order_confirm')
 gross=action('partner-sales',gross['id'],'agreement',{'supplierCode':tim['supplierCode'],'gross':'1000','fee':'100','mode':'gross'})
 gross=action('partner-sales',gross['id'],'supplierpay',{'supplierCode':tim['supplierCode'],'amount':'1000','date':today})
 check('paid goods do not auto receive fee',gross['stats']['settlements'][0]['feeRemainingCents']==10000)
 gross=action('partner-sales',gross['id'],'feereceipt',{'supplierCode':tim['supplierCode'],'amount':'100','date':today})
 reject('partner-sales',gross['id'],'feereceipt',{'supplierCode':tim['supplierCode'],'amount':'100','date':today})
 check('gross settlement closes only after receipt',gross['stats']['settlements'][0]['feeRemainingCents']==0)
 old_snapshot=sale['lines'][0]['snapshot'];p['name']='QA renamed case';p['requestId']=key()
 p=api('PUT',f'/partner-products/{p["id"]}',p,token)
 check('product changes do not rewrite sales or samples',get('partner-sales',sale['id'])['lines'][0]['snapshot']==old_snapshot and get('samples',s['id'])['sourceSnapshot']['productName']=='QA magnetic case')
 # A restricted internal maintainer can see only assigned records, and cannot see monetary snapshots.
 uid,limited=account('partner-qa-no-money','domestic',['partner:view','partner:write','samples:write','customer:view','supplier:view','file:read','documents:write'],False)
 sql(database,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({uid},'partner',{sale['id']}),({uid},'partner',{s['id']}),({uid},'partner',{p['id']}),({uid},'customer',{cid}),({uid},'supplier_profile',{tim['supplierId']});")
 masked=api('GET',f'/partner-sales/{sale["id"]}',token=limited['token'])
 check('money hidden in rows and nested historical snapshots','totalCents' not in masked['stats'] and 'priceCents' not in masked['lines'][0] and 'unitPrice' not in masked['lines'][0]['snapshot']['source'])
 api('GET',f'/partner-sales/{gross["id"]}',token=limited['token'],success=False)
 check('unassigned commercial records denied',True)
 api('POST',f'/partner-sales/{sale["id"]}/actions/receipt',{'version':sale['version'],'requestId':key(),'amount':'1','date':today},limited['token'],success=False)
 check('finance action cannot bypass endpoint permissions',True)
 fixture={'productId':p['id'],'sampleId':s['id'],'saleId':sale['id'],'grossSaleId':gross['id'],'customerId':cid,'supplierCode':tim['supplierCode'],'specId':spec,'limitedUserId':uid}
 (ctx['output']/'partner-fixtures.json').write_text(json.dumps(fixture))
 check('partner relationships query returns same facts',api('GET',f'/customers/{cid}/business',token=token)['sales']['totalElements']==2)

 # Corrections preserve physical ledger history and dependent records.
 s=action('samples',s['id'],'gift',{'qty':1,'location':'QA sample shelf','reason':'QA gift','customerId':cid,'date':today})
 gift=s['events'][-1]['id'];s=action('samples',s['id'],'undo',{'targetEventId':gift,'reason':'wrong quantity','date':today})
 check('sample correction restores quantity without erasing event',s['balances']['onhand']==9 and any(e['id']==gift for e in s['events']))
 s=action('samples',s['id'],'consume',{'qty':1,'location':'QA sample shelf','reason':'QA fit test','date':today})
 s=action('samples',s['id'],'loan',{'qty':1,'location':'QA sample shelf','person':'QA lost loan','due':today,'date':today})
 lossloan=s['events'][-1]['id'];s=action('samples',s['id'],'loss',{'qty':1,'loanId':lossloan,'reason':'QA lost','date':today})
 s=action('samples',s['id'],'count',{'actual':5,'location':'QA sample shelf','reason':'QA physical count','date':today})
 check('consumption loss and physical count have explicit facts',s['balances']['onhand']==6 and s['balances']['available']==5 and s['balances']['borrowed']==0)
 opening=create('samples',{'sourceType':'partner','pending':True,'name':'QA opening','provenance':'counted without source','qty':2,'location':'QA opening shelf','date':today})
 opening=action('samples',opening['id'],'source_resolve',{'sourceType':'partner','specId':spec,'supplierCode':tim['supplierCode'],'reason':'supplier confirmed original source'})
 check('pending source completion retains opening fact',not opening['pending'] and opening['events'][0]['data']['sourceSnapshot']['pending'] and opening['balances']['onhand']==2)
 # Pure supplier creation retries do not generate duplicate profiles.
 req=key();data={'supplierName':'QA retry supplier','supplierType':'person','requestId':req}
 first=api('POST','/inventories/suppliers',data,token);again=api('POST','/inventories/suppliers',data,token)
 check('supplier create retry is idempotent',first['supplierCode']==again['supplierCode'])
 # Files use existing storage and immutable versions; client names cannot falsify the snapshot.
 upload=ctx['upload'];file=upload(f'/partner-products/{p["id"]}/files','QA.png',ctx['image'],{'purpose':'photo','version':'V1'})
 fileid=file['attachmentId'];p=get('partner-products',p['id']);p['refs']=[{'id':fileid,'type':'file','name':'forged','version':'FAKE'}];p['requestId']=key()
 p=api('PUT',f'/partner-products/{p["id"]}',p,token)
 check('material snapshots use canonical stored version',p['refs'][0]['version']=='V1' and p['refs'][0]['name']!='forged')
 api('DELETE',f'/attachments/{fileid}',token=token,success=False);check('historical file deletion is protected',True)
 newer=upload(f'/partner-products/{p["id"]}/files','QA-new.png',ctx['image'],{'purpose':'photo','previous':fileid,'version':'V2','reason':'new drawing'})
 p=get('partner-products',p['id']);oldrev=next(r for r in p['revisions'] if r['data'].get('refs'))
 check('new file version keeps original adoption',p['refs'][0]['id']==fileid and oldrev['data']['refs'][0]['id']==fileid)
 private=upload(f'/partner-products/{p["id"]}/files','QA-money.png',ctx['image'],{'purpose':'financial','version':'F1'})
 masked=api('GET',f'/partner-products/{p["id"]}',token=limited['token'])
 check('financial file names and events are hidden',all(x['id']!=private['attachmentId'] for x in masked['files']) and not any(e['action']=='file_add' and e['data'].get('purpose')=='financial' for e in masked['events']))
 api('GET',f'/attachments/{private["attachmentId"]}/preview/metadata',token=limited['token'],success=False)
 check('financial blob permission cannot be bypassed',True)
 # Same source website order is not a real collection or shipment.
 import csv,io,zipfile,xml.etree.ElementTree as ET,urllib.request,urllib.error
 def csvfile(headers,rows):
  stream=io.StringIO();writer=csv.writer(stream);writer.writerow(headers);writer.writerows(rows);return stream.getvalue().encode('utf-8-sig')
 def preview(kind,headers,rows):return upload('/'+kind+'/import/preview','QA.csv',csvfile(headers,rows),{})
 ph=['站点','网站商品号','网站SKU','商品名称','规格','供方代码','供方货号','参考单价','币种']
 rows=[['QA-IMPORT','QA-PRODUCT-I','QA-I-SKU','QA imported product','clear',tim['supplierCode'],'I-TIM','12.50','CNY']]
 imp=preview('partner-products',ph,rows);check('CSV preview validates matched real supplier',imp['errorCount']==0 and imp['rows'][0]['action']=='new')
 result=api('POST',f'/partner-products/import/{imp["token"]}/commit',{},token)
 again=preview('partner-products',ph,rows);check('repeated product import identifies unchanged version',again['rows'][0]['action']=='skip_same')
 result2=api('POST',f'/partner-products/import/{again["token"]}/commit',{},token);check('repeated import adds no product or needless revision',result2['saved']==0 and result2['skipped']==1)
 imported=api('GET','/partner-products?keyword=QA%20imported%20product',token=token)['content'][0]
 sh=['站点','网站订单号','客户代码','规格码','网站SKU','供方代码','数量','单价','币种','网站来源状态']
 sr=[['QA-IMPORT','QA-ORDER-I',customer['customerCode'],imported['specs'][0]['code'],'',tim['supplierCode'],'10','20','CNY','paid shipped on website']]
 si=preview('partner-sales',sh,sr);api('POST',f'/partner-sales/import/{si["token"]}/commit',{},token)
 order=api('GET','/partner-sales?keyword=QA-ORDER-I',token=token)['content'][0]
 check('imported website status does not manufacture facts',not order['confirmed'] and order['stats']['paidCents']==0 and order['stats']['shipped']==0)
 order=action('partner-sales',order['id'],'order_confirm');si=preview('partner-sales',sh,sr)
 check('confirmed order import preserves history',si['rows'][0]['action']=='skip_confirmed')
 api('POST',f'/partner-sales/import/{si["token"]}/commit',{},token)
 # Inline matching shares the same master-data writer.
 unresolved=preview('partner-sales',sh,[['QA-IMPORT','QA-ORDER-UNMATCHED','NO-CUSTOMER','NO-SPEC','','NO-SUPPLIER','1','10','CNY','']])
 check('unmatched rows require explicit identity',unresolved['errorCount']==1)
 matched=api('PUT',f'/partner-sales/import/{unresolved["token"]}/matches',[{'rowNumber':2,'customerId':cid,'specId':spec,'supplierCode':tim['supplierCode']}],token)
 check('explicit matching resolves without name guessing',matched['errorCount']==0)
 # XLSX template uses real parser; formulas are not treated as actual quantities.
 request=urllib.request.Request(ctx['base']+'/partner-products/import/template',headers={'Authorization':'Bearer '+token})
 raw=urllib.request.urlopen(request).read()
 def workbook(row,formula=False):
  ns='http://schemas.openxmlformats.org/spreadsheetml/2006/main';buf=io.BytesIO()
  with zipfile.ZipFile(io.BytesIO(raw)) as original,zipfile.ZipFile(buf,'w',zipfile.ZIP_DEFLATED) as dest:
   for item in original.infolist():
    value=original.read(item.filename)
    if item.filename=='xl/worksheets/sheet1.xml':
     root=ET.fromstring(value);sheet=root.find('{'+ns+'}sheetData');r=ET.SubElement(sheet,'{'+ns+'}row',r='2')
     for i,val in enumerate(row):
      c=ET.SubElement(r,'{'+ns+'}c',r=chr(65+i)+'2',t='inlineStr');ins=ET.SubElement(c,'{'+ns+'}is');ET.SubElement(ins,'{'+ns+'}t').text=val
     value=ET.tostring(root,encoding='utf-8',xml_declaration=True)
    dest.writestr(item,value)
  return buf.getvalue()
 xi=upload('/partner-products/import/preview','QA.xlsx',workbook(['QA-XLSX','X-P','X-SKU','QA XLSX product','black',ct['supplierCode'],'X-C','2','CNY']),{})
 check('XLSX imports use same matching and saves',xi['errorCount']==0)
 api('POST',f'/partner-products/import/{xi["token"]}/commit',{},token)
 # Preview protects against changes made between review and confirmation.
 changed=preview('partner-products',ph,[['QA-IMPORT','QA-PRODUCT-I','QA-I-SKU','QA changed import','clear',tim['supplierCode'],'I-TIM','12.50','CNY']])
 current=get('partner-products',imported['id']);current['notes']='changed concurrently';current['requestId']=key();api('PUT',f'/partner-products/{current["id"]}',current,token)
 api('POST',f'/partner-products/import/{changed["token"]}/commit',{},token,success=False);check('stale import cannot overwrite a concurrent update',True)
 # Same version: one transaction wins; same request: one immutable fact.
 from concurrent.futures import ThreadPoolExecutor
 def raw_action(payload):
  request=urllib.request.Request(ctx['base']+f'/samples/{s["id"]}/actions/gift',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+token},method='POST')
  try:return json.load(urllib.request.urlopen(request))
  except urllib.error.HTTPError as e:return json.load(e)
 current=get('samples',s['id']);payload={'version':current['version'],'requestId':key(),'qty':1,'location':'QA sample shelf','reason':'QA concurrent','date':today}
 with ThreadPoolExecutor(2) as pool:results=list(pool.map(raw_action,[payload,{**payload,'requestId':key()}]))
 check('concurrent different submissions cannot overdraw stale version',sum(r['code']==0 for r in results)==1)
 current=get('samples',s['id']);payload['version']=current['version'];payload['requestId']=key()
 with ThreadPoolExecutor(2) as pool:results=list(pool.map(raw_action,[payload,payload]))
 check('concurrent same request produces one event',all(r['code']==0 for r in results) and len(get('samples',s['id'])['events'])==len(current['events'])+1)
 # Actual returns and all three supplier-side reversals retain original allocation.
 sale=get('partner-sales',sale['id']);sale=action('partner-sales',sale['id'],'ship',{'lineId':line,'qty':50,'date':today});ship=next(e['id'] for e in sale['events'] if e['action']=='ship')
 receipt=next(e['id'] for e in sale['events'] if e['action']=='receipt');payment=next(e['id'] for e in sale['events'] if e['action']=='supplierpay')
 sale=action('partner-sales',sale['id'],'returngoods',{'lineId':line,'qty':10,'disposition':'refund','originalEventId':ship,'date':today,'reason':'QA return'})
 sale=action('partner-sales',sale['id'],'refund',{'amount':'100','originalEventId':receipt,'date':today,'reason':'QA refund'})
 sale=action('partner-sales',sale['id'],'supplierrefund',{'supplierCode':tim['supplierCode'],'amount':'90','originalEventId':payment,'date':today,'reason':'QA vendor return'})
 sale=action('partner-sales',sale['id'],'offsetreverse',{'supplierCode':tim['supplierCode'],'amount':'10','originalEventId':payment,'date':today,'reason':'QA fee reverse'})
 sale=action('partner-sales',sale['id'],'agreement',{'supplierCode':tim['supplierCode'],'gross':'900','fee':'90','mode':'net','reason':'QA reconciled after return'})
 check('returned paid order can reconcile supplier refund and offset reverse',sale['stats']['customerRemainingCents']==0 and sale['stats']['settlements'][0]['payRemainingCents']==0 and sale['stats']['settlements'][0]['feeRemainingCents']==0)
 reject('partner-sales',sale['id'],'refund',{'amount':'1000','originalEventId':receipt,'date':today,'reason':'excess'})
 gross=get('partner-sales',gross['id']);feeorigin=next(e['id'] for e in gross['events'] if e['action']=='feereceipt')
 gross=action('partner-sales',gross['id'],'feerefund',{'supplierCode':tim['supplierCode'],'amount':'20','originalEventId':feeorigin,'date':today,'reason':'QA fee adjust'})
 check('fee refund is separate from vendor goods payment',gross['stats']['settlements'][0]['feeReceivedCents']==8000 and gross['stats']['settlements'][0]['paidCents']==100000)
 # Original research facts are selected from the upstream confirmed round, never inferred from inventory.
 model=ctx['legacy']['productId'];roundid=int(sql(database,f"insert into plm_product_development_round(product_id,kind,round_no,title,status,result,record_date,details_json,references_json,created_by,updated_by) values({model},'sample',1,'QA actual upstream sample','confirmed','failed',current_date,'{{}}','[]','fixture','fixture') returning round_id;"))
 own=create('samples',{'sourceType':'self','projectId':model,'roundId':roundid,'qty':2,'location':'QA own samples','date':today})
 check('failed research conclusion remains separate from stock',own['sourceSnapshot']['round']['result']=='failed' and own['balances']['onhand']==2)
 check('server filters and master pagination use the same totals',api('GET','/customers?page=1&keyword=QA',token=token)['totalElements']==1 and api('GET','/inventories/suppliers/page?page=1&keyword=QA',token=token)['totalElements']==2)
 check('sample movement pagination is real',api('GET',f'/samples/movements?sampleId={s["id"]}',token=token)['totalElements']==len(get('samples',s['id'])['events']))

 # Multi-supplier quotation revisions, quantity changes and replacement delivery use original lines.
 p=get('partner-products',p['id']);p['specs'][0]['sources'].append({'supplierCode':ct['supplierCode'],'unitPrice':'20','currency':'CNY'});p['requestId']=key();p=api('PUT',f'/partner-products/{p["id"]}',p,token)
 multi=create('partner-sales',{'saleType':'inquiry','customerId':cid,'currency':'CNY','lines':[{'specId':spec,'supplierCode':tim['supplierCode'],'qty':10,'unitPrice':'10'},{'specId':spec,'supplierCode':ct['supplierCode'],'qty':5,'unitPrice':'20'}]})
 multi=action('partner-sales',multi['id'],'quote_confirm');multi['lines'][0]['unitPrice']='11';multi['requestId']=key();multi=api('PUT',f'/partner-sales/{multi["id"]}',multi,token)
 reject('partner-sales',multi['id'],'order_confirm',{})
 multi=action('partner-sales',multi['id'],'quote_confirm');multi=action('partner-sales',multi['id'],'order_confirm')
 check('revised quote retains both versions and blocks stale quote conversion',len(multi['quotes'])==2 and len(multi['lines'])==2 and multi['quotes'][0]['lines'][0]['priceCents']==1000 and multi['lines'][0]['priceCents']==1100)
 ml,other=multi['lines'][0]['id'],multi['lines'][1]['id']
 multi=action('partner-sales',multi['id'],'ship',{'lineId':ml,'qty':4,'date':today});origin=multi['events'][-1]['id']
 multi=action('partner-sales',multi['id'],'cancel',{'lineId':ml,'qty':2,'date':today,'reason':'QA cancellation'})
 multi=action('partner-sales',multi['id'],'shipreverse',{'lineId':ml,'qty':1,'originalEventId':origin,'date':today,'reason':'QA wrong shipment'})
 multi=action('partner-sales',multi['id'],'returngoods',{'lineId':ml,'qty':1,'originalEventId':origin,'date':today,'disposition':'replace','reason':'QA replacement return'})
 multi=action('partner-sales',multi['id'],'reship',{'lineId':ml,'qty':1,'originalEventId':origin,'date':today,'reason':'QA replacement dispatch'})
 check('cancel reverse replacement do not count replacement as new regular delivery',multi['stats']['ordered']==13 and multi['stats']['shipped']==3 and multi['stats']['totalCents']==18800)
 reject('partner-sales',multi['id'],'returngoods',{'lineId':other,'qty':1,'originalEventId':origin,'date':today,'disposition':'refund','reason':'wrong source'})
 multi=action('partner-sales',multi['id'],'amend',{'lineId':other,'qty':6,'unitPrice':'21','reason':'QA agreed correction'})
 check('confirmed order amendment preserves original and current revision',multi['lines'][1]['qty']==6 and multi['events'][-1]['data']['before']['qty']==5 and len(multi['revisions'])>=4)
 # Reopening a completed order is explicit so later genuine after-sales facts remain possible.
 sale=get('partner-sales',sale['id']);sale=action('partner-sales',sale['id'],'close')
 reject('partner-sales',sale['id'],'receipt',{'amount':'1','date':today})
 sale=action('partner-sales',sale['id'],'reopen',{'reason':'QA new after-sales request'})
 check('closed order can explicitly reopen without rewriting prior closing',sale['status']=='confirmed' and any(e['action']=='close' for e in sale['events']))
 # A non-finance maintainer changing quantity must not silently reset a negotiated draft price.
 negotiated=create('partner-sales',{'saleType':'order','customerId':cid,'currency':'CNY','lines':[{'specId':spec,'supplierCode':tim['supplierCode'],'qty':2,'unitPrice':'33'}]})
 sql(database,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({uid},'partner',{negotiated['id']});")
 hidden=api('GET',f'/partner-sales/{negotiated["id"]}',token=limited['token']);hidden['lines'][0]['qty']=3;hidden['requestId']=key();api('PUT',f'/partner-sales/{negotiated["id"]}',hidden,limited['token'])
 check('non-finance edit keeps hidden negotiated price',get('partner-sales',negotiated['id'])['lines'][0]['priceCents']==3300)
 api('GET','/partner-products',token=ctx['supplier']['token'],success=False);check('external supplier cannot enter commercial workspace',True)
 # More than one page shares exact filtering/statistics instead of local filtering.
 for n in range(31):create('customers',{'customerName':f'QA pagination {n:02}','customerType':'person'})
 page1=api('GET','/customers?page=1&keyword=pagination',token=token);page2=api('GET','/customers?page=2&keyword=pagination',token=token)
 check('master pagination does not lose rows across pages',page1['totalElements']==31 and len(page1['content'])==30 and len(page2['content'])==1)
 for n in range(31):create('partner-products',{'name':f'QA catalog pagination {n:02}'})
 page1=api('GET','/partner-products?page=1&keyword=QA%20catalog%20pagination&status=draft',token=token);page2=api('GET','/partner-products?page=2&keyword=QA%20catalog%20pagination&status=draft',token=token)
 check('business pagination and statistics use identical predicates',page1['totalElements']==31 and len(page1['content'])==30 and len(page2['content'])==1 and sum(x['count'] for x in page1['statistics'])==31)

 masked=api('GET',f'/partner-products/{p["id"]}',token=limited['token']);masked['requestId']=key();masked['specs'][0]['sources'][0]['currency']='USD'
 api('PUT',f'/partner-products/{p["id"]}',masked,limited['token'],success=False);check('financial currency cannot change without finance write',True)
 sourcebefore=get('samples',s['id'])['sourceSnapshot'];actual=action('samples',s['id'],'count',{'location':'QA sample shelf','actual':10,'reason':'QA count','date':today,'sourceSnapshot':{'productName':'forged'}})
 check('flow request cannot inject an invented development source','sourceSnapshot' not in actual['events'][-1]['data'] and actual['sourceSnapshot']==sourcebefore)

 sql(database,f"update plm_product_development_round set details_json='{{\"unitCost\":333,\"targetCost\":222,\"quoteEventId\":7,\"actualResult\":\"QA fitted\"}}' where round_id={roundid};")
 inherited=create('samples',{'sourceType':'self','projectId':model,'roundId':roundid,'qty':1,'location':'QA inherited','date':today})
 sql(database,f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({uid},'partner',{inherited['id']});")
 scoped=api('GET',f'/samples/{inherited["id"]}',token=limited['token'])['sourceSnapshot']['round']['details']
 check('inherited research details reuse original financial redaction','unitCost' not in scoped and 'targetCost' not in scoped and scoped['actualResult']=='QA fitted')

 specsearch=api('GET','/samples?keyword=iPhone%2017',token=token)
 check('sample specification search uses real scope and totals',specsearch['totalElements']>=2 and all('iPhone 17' in row['specName'] for row in specsearch['content']))
 flows=api('GET',f'/samples/movements?keyword=iPhone%2017&sampleId={s["id"]}',token=token)
 check('movement specification search uses same source scope',flows['totalElements']==len(get('samples',s['id'])['events']))

if __name__=='__main__':main(verify)
