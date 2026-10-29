#!/usr/bin/env python3
"""System phone selection, persistence, imports and immutable evidence in a disposable DB."""
import csv,io,json,uuid
from urllib.parse import quote
from local_research_fixture import main,sql
from verify_local_partner import verify as verify_existing

def verify(ctx):
 verify_existing(ctx)
 api,check,token,database,account=[ctx[k] for k in ('api','check','token','database','account')]
 def key():return uuid.uuid4().hex
 def create(path,data):return api('POST',path,{**data,'requestId':key()},token)
 def product(id):return api('GET',f'/partner-products/{id}',token=token)
 def save(p):return api('PUT',f'/partner-products/{p["id"]}',{**p,'requestId':key()},token)
 brand=create('/molds/reference-data/brands',{'name':'QA phone selection '+key()[:8]})
 phones=[create('/molds/reference-data/phone-models',{'brandId':brand['brandId'],'modelName':'Phone '+n}) for n in ['Alpha','Beta']]
 ids=sorted(p['phoneModelId'] for p in phones)
 tim=next(s for s in api('GET','/inventories/suppliers/snapshot',token=token)['suppliers'] if s['supplierName']=='Tim')
 src=[{'supplierCode':tim['supplierCode'],'currency':'CNY'}]
 p=create('/partner-products',{'name':'QA selected phones','specs':[{'phoneModelIds':list(reversed(ids))+ids,'specification':'透明','phones':[{'modelName':'forged'}],'sources':src}]})
 spec=p['specs'][0]
 check('product stores deduplicated real IDs and canonical names',spec['phoneModelIds']==ids and len(spec['phones'])==2 and 'forged' not in json.dumps(spec) and spec['name'].endswith(' · 透明'))
 check('product reopens with the same selected models',product(p['id'])['specs']==p['specs'])
 sample=create('/samples',{'sourceType':'partner','specId':spec['id'],'supplierCode':tim['supplierCode'],'qty':1,'location':'QA phone shelf','date':ctx['today'] if 'today' in ctx else '2026-10-16'})
 customer=create('/customers',{'customerName':'QA selected phone customer'})
 sale=create('/partner-sales',{'saleType':'order','customerId':customer['customerId'],'currency':'CNY','lines':[{'specId':spec['id'],'supplierCode':tim['supplierCode'],'qty':1,'unitPrice':'10'}]})
 sale=api('POST',f'/partner-sales/{sale["id"]}/actions/order_confirm',{'version':sale['version'],'requestId':key()},token)
 check('samples and orders carry the same phone evidence',sample['sourceSnapshot']['phoneModelIds']==ids and sale['lines'][0]['snapshot']['phones']==spec['phones'])
 before_sample=sample['sourceSnapshot'];before_sale=sale['lines'][0]['snapshot'];before_revision=p['version']
 api('PATCH',f'/molds/reference-data/phone-models/{ids[0]}',{'changes':{'modelName':'Phone renamed'},'original':{'modelName':phones[0]['modelName']}},token)
 p['specs'][0]['phoneModelIds']=[ids[0]];p=save(p)
 check('removing a selected phone persists without retyping',p['specs'][0]['phoneModelIds']==[ids[0]] and p['specs'][0]['specification']=='透明')
 check('model changes keep prior product revisions and physical sales evidence',any(r['revision']==before_revision and r['data']['specs'][0]['phoneModelIds']==ids for r in p['revisions']) and api('GET',f'/samples/{sample["id"]}',token=token)['sourceSnapshot']==before_sample and api('GET',f'/partner-sales/{sale["id"]}',token=token)['lines'][0]['snapshot']==before_sale)
 count=sql(database,"select count(*) from plm_partner_record where kind='product'")
 for bad in [[999999999],[1.5],['Phone Alpha'],'Phone Alpha']:
  api('POST','/partner-products',{'name':'QA invalid selection','requestId':key(),'specs':[{'phoneModelIds':bad,'specification':'透明'}]},token,success=False)
 check('invalid or typed model identifiers roll back entirely',sql(database,"select count(*) from plm_partner_record where kind='product'")==count)
 api('PATCH',f'/molds/reference-data/phone-models/{ids[0]}',{'changes':{'status':'disabled'},'original':{'status':'enabled'}},token)
 p['notes']='unrelated note';p=save(p)
 check('previously selected disabled models remain visible during unrelated edits',p['specs'][0]['phoneModelIds']==[ids[0]])
 api('POST','/partner-products',{'name':'QA disabled model','requestId':key(),'specs':[{'phoneModelIds':[ids[0]]}]},token,success=False)
 check('disabled models cannot become a new association',True)
 legacy=create('/partner-products',{'name':'QA legacy text','specs':[{'name':'Unknown handwritten model / blue','sources':src}]})
 legacy['notes']='keep text';legacy=save(legacy)
 check('legacy free text is retained without guessed model IDs',legacy['specs'][0]['name']=='Unknown handwritten model / blue' and legacy['specs'][0]['phoneModelIds']==[])
 # A long multiselect label is kept complete in JSON and the lookup table.
 longphones=[create('/molds/reference-data/phone-models',{'brandId':brand['brandId'],'modelName':'Phone '+str(i)+' '+('Long model name '*5)}) for i in range(4)]
 wide=create('/partner-products',{'name':'QA wide selection','specs':[{'phoneModelIds':[x['phoneModelId'] for x in longphones]}]})
 check('long multiselect labels are stored without truncation',wide['specs'][0]['specification']=='' and len(wide['specs'][0]['name'])>255 and sql(database,'select length(name) from plm_partner_spec where spec_id='+str(wide['specs'][0]['id']))==str(len(wide['specs'][0]['name'])))
 # An internal partner/project maintainer can select reference identities without receiving reference history.
 _,limited=account('phone-picker-partner','domestic',['partner:view','partner:write'],False)
 catalog=api('GET','/molds/reference-data/phone-models?keyword='+quote(brand['name']),token=limited['token'])
 selected=api('GET','/molds/reference-data/phone-models/options?ids='+str(ids[1]),token=limited['token'])
 check('partner-only accounts can select canonical library identities',any(x['phoneModelId']==ids[1] for x in catalog['content']) and selected[0]['phoneModelId']==ids[1])
 check('selection-only accounts do not receive raw reference sources','sourceSnapshot' not in selected[0] and all('sourceSnapshot' not in x for x in catalog['content']))
 api('POST','/molds/reference-data/phone-models',{'brandId':brand['brandId'],'modelName':'Unauthorized'},limited['token'],success=False)
 _,denied=account('phone-picker-denied','domestic',['customer:view'],False)
 api('GET','/molds/reference-data/phone-models',token=denied['token'],success=False)
 check('selection permission does not grant maintenance or unrelated read access',True)
 # Import preview uses the same IDs and validation as single-entry maintenance.
 def preview():
  stream=io.StringIO();writer=csv.writer(stream);writer.writerow(['站点','网站商品号','网站SKU','商品名称','规格','供方代码','币种']);writer.writerow(['QA-PHONES','phone-import','phone-sku','QA phone import','黑色',tim['supplierCode'],'CNY']);raw=stream.getvalue().encode();boundary='Phone'+key()
  data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="phones.csv"\r\nContent-Type: text/csv\r\n\r\n'.encode()+raw+f'\r\n--{boundary}--\r\n'.encode())
  return api('POST','/partner-products/import/preview',data,token,'multipart/form-data; boundary='+boundary)
 pre=preview();r=pre['rows'][0];r['phoneModelIds']=[ids[1]]
 matched=api('PUT',f'/partner-products/import/{pre["token"]}/matches',[r],token)
 check('import preview keeps explicit system phone selection',matched['errorCount']==0 and matched['rows'][0]['phoneModelIds']==[ids[1]])
 api('POST',f'/partner-products/import/{pre["token"]}/commit',token=token)
 imported=api('GET','/partner-products?keyword=QA%20phone%20import',token=token)['content'][0]
 check('import and single entry use the same model data',imported['specs'][0]['phoneModelIds']==[ids[1]] and imported['specs'][0]['specification']=='黑色')
 repeated=preview();check('reimport keeps selected IDs and skips identical rows',repeated['rows'][0]['phoneModelIds']==[ids[1]] and repeated['rows'][0]['action']=='skip_same')
 matched=api('PUT',f'/partner-products/import/{repeated["token"]}/matches',[{**repeated['rows'][0],'phoneModelIds':[999999999]}],token)
 check('invalid import selections are rejected before commit',matched['errorCount']==1)
 api('POST',f'/partner-products/import/{repeated["token"]}/commit',token=token,success=False)
 check('rejected import does not change existing model associations',product(imported['id'])['specs'][0]['phoneModelIds']==[ids[1]])
 # Keep two research stages for actual browser validation of prefilled selection.
 project_ids=[]
 for step in [1,2]:
  pid=create('/product-research',{'productName':'QA phone project '+str(step),'productType':'product_line','versionNo':'V1','createdBy':'fixture'})['productId'];d=ctx['detail'](pid)
  api('PUT',f'/product-research/{pid}',{'productName':'QA phone project '+str(step),'requirements':'手机壳验证','phoneModelIds':[ids[1]],'ownerUserId':ctx['owner_id'],'version':d['workflow']['version']},token)
  if step==2:
   d=ctx['detail'](pid);api('POST',f'/projects/{pid}/timeline/LAUNCH_REQUIREMENT/confirm',{'developmentVersion':d['workflow']['version']},token)
  project_ids.append(pid)
 check('research initiation and plan retain real selected model IDs',ctx['detail'](project_ids[0])['workflow']['profileDraft']['phoneModelIds']==[ids[1]] and ctx['detail'](project_ids[1])['workflow']['requirement']['phoneModelIds']==[ids[1]])
 (ctx['output']/'phone-fixtures.json').write_text(json.dumps({'productId':p['id'],'importedId':imported['id'],'projectIds':project_ids,'phoneIds':ids,'brand':brand['name']}))

if __name__=='__main__':main(verify)
