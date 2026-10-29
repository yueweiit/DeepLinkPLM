#!/usr/bin/env python3
"""Selection API acceptance against a fixed, explicitly labelled loopback clone only.

Start the candidate on 18099, restore an offline backup into plm-selection-qa-20261021
on 15446, then run --output <private directory>. Never accepts a production URL.
Generated users, supplier declarations and photos are simulated QA facts only.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import csv
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.error
import urllib.request
import uuid

from local_research_fixture import multipart_payload, quote

CONTAINER='plm-selection-qa-20261021'
DATABASE='plm_selection_qa'
BASE='http://127.0.0.1:18099/api/v1'

def main():
    args=argparse.ArgumentParser();args.add_argument('--output',required=True,type=Path);out=args.parse_args().output
    os.umask(0o077);out.mkdir(parents=True,exist_ok=True)
    info=json.loads(subprocess.check_output(['docker','inspect',CONTAINER]))[0]
    assert info['Config']['Labels']['purpose']=='plm-selection-isolated-acceptance'
    assert info['NetworkSettings']['Ports']['5432/tcp'][0]=={'HostIp':'127.0.0.1','HostPort':'15446'}
    def sql(statement):
        return subprocess.check_output(['docker','exec','-i',CONTAINER,'psql','-X','-U','postgres','-d',DATABASE,'-qAt','-v','ON_ERROR_STOP=1'],input=('set search_path=plm,public;\n'+statement).encode(),stderr=subprocess.PIPE).decode().strip()
    assert sql('select current_database()')==DATABASE
    checks=[];key=lambda:uuid.uuid4().hex
    def check(name,condition=True):
        assert condition,name
        checks.append(name)
    def api(method,path,body=None,token=None,reject=False,mime='application/json',binary=False):
        headers={'Content-Type':mime}
        if token:headers['Authorization']='Bearer '+token
        data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
        try:
            response=urllib.request.urlopen(urllib.request.Request(BASE+path,data=data,headers=headers,method=method),timeout=40)
            content=response.read()
            if binary:return content
            payload=json.loads(content)
        except urllib.error.HTTPError as error:payload=json.load(error)
        if reject:
            assert payload.get('code')!=0, 'Unexpected success: '+path
            return payload
        assert payload.get('code')==0,f'{method} {path}: {payload.get("message")}'
        return payload.get('data')
    run=key()[:8]
    def account(suffix,role=None,permissions=None,engineering=False,all_objects=True):
        name='selection-qa-'+run+'-'+suffix;password=secrets.token_urlsafe(24)
        hashed=subprocess.run(['/usr/sbin/htpasswd','-niB','-C','4',name],input=password+'\n',text=True,capture_output=True,check=True).stdout.split(':',1)[1].strip()
        uid=int(sql('select coalesce(max(user_id),0)+100 from sys_user'))
        sql(f"insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions) values({uid},{quote(name)},{quote(name)},{quote(name)},{quote(name)},{quote('隔离选品验收 '+suffix)},{quote(hashed)},'active',{'false' if role else 'true'})")
        if role:sql(f"insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values({uid},{quote(role)},{quote(json.dumps(permissions or []))}::jsonb,{'true' if all_objects else 'false'},'QA','QA')")
        if engineering:sql(f"insert into plm_collaboration_assignment(field_group,user_id) values('engineering',{uid}) on conflict do nothing")
        return uid,api('POST','/auth/login',{'username':name,'password':password})
    _,session=account('admin');token=session['token']
    (out/'browser-session.private.json').write_text(json.dumps(session))
    _,engineer=account('engineer','domestic',['partner:view','partner:write','collaboration:view','documents:write','file:read'],True)
    eng=engineer['token']
    _,reader=account('reader','domestic',['partner:view','collaboration:view'])
    _,finance=account('finance','finance',['partner:view','finance:read'])
    _,limited=account('limited','domestic',['partner:view','collaboration:view'],all_objects=False)
    original={}
    for table,identity in [('plm_product','product_id'),('plm_inventory','inventory_id'),('plm_attachment','attachment_id'),('plm_partner_record','record_id'),('plm_partner_spec','spec_id')]:
        ceiling=int(sql(f'select coalesce(max({identity}),0) from {table}'))
        statement=f"select count(*)||'|'||md5(coalesce(string_agg(to_jsonb(t)::text,'' order by {identity}),'')) from {table} t where {identity}<={ceiling}"
        original[table]={'statement':statement,'before':sql(statement)}
    phone_ids=[int(x) for x in sql("select m.id from plm_phone_model_member m join plm_phone_brand b on b.id=m.brand_id where m.status='enabled' and b.status='enabled' order by m.id limit 2").splitlines()]
    assert len(phone_ids)==2
    p1,p2=phone_ids
    supplier=api('POST','/inventories/suppliers',{'supplierName':'QA供方 '+run,'supplierType':'company','status':'active','requestId':key()},token)
    code=supplier['supplierCode']
    root='/partner-selections';get=lambda sid:api('GET',f'{root}/{sid}',token=token)
    def save(s,**changes):return api('PUT',f'{root}/{s["id"]}',{**s,**changes,'requestId':key()},token)
    def confirm(s,rows,actor=token,reject=False,request=None):return api('POST',f'{root}/{s["id"]}/confirm',{'rowIds':rows,'version':s['version'],'requestId':request or key()},actor,reject)
    def row(name,**values):return {'rowId':str(uuid.uuid4()),'name':name,'supplierCode':code,'specification':'QA透明配置','modelText':'期望iPhone系列（模拟资料）','selected':True,'phoneModelIds':[p1],**values}
    a=row('QA已核实供货 '+run,availablePhoneModelIds=[p1],unavailablePhoneModelIds=[p2],unitPrice='12.50',currency='CNY',sameStyleKey='QA-SAME-'+run,developmentStatus='preferred',developmentEvidence='QA模拟供方开发先后依据，不代表真实供方事实')
    b=row('QA未核实供货 '+run,availablePhoneModelIds=[],unitPrice='',currency='')
    c={'rowId':str(uuid.uuid4()),'name':'未选目录缺资料','selected':False}
    request=key();s=api('POST',root,{'name':'QA选品流程 '+run,'rows':[a,b,c],'requestId':request},token)
    check('create retry is idempotent and candidates do not become products',api('POST',root,{'name':s['name'],'rows':[a,b,c],'requestId':request},token)['id']==s['id'])
    api('GET',root,reject=True)
    api('GET',f'{root}/{s["id"]}',token=limited['token'],reject=True)
    check('anonymous and out-of-scope users cannot read selection sheets')
    check('costs visible to engineer and finance but redacted from ordinary reader','unitPrice' in api('GET',f'{root}/{s["id"]}',token=eng)['rows'][0] and 'unitPrice' in api('GET',f'{root}/{s["id"]}',token=finance['token'])['rows'][0] and 'unitPrice' not in api('GET',f'{root}/{s["id"]}',token=reader['token'])['rows'][0])
    api('PUT',f'{root}/{s["id"]}',{**s,'requestId':key()},reader['token'],reject=True)
    check('readers cannot edit by bypassing hidden buttons')
    count=sql("select count(*) from plm_partner_record where kind='product'")
    confirm(s,[a['rowId'],b['rowId']],reject=True)
    check('an unknown supplier range blocks whole confirmation without partial products',sql("select count(*) from plm_partner_record where kind='product'")==count)
    bad=json.loads(json.dumps(s));bad['rows'][0]['currency']='';bad=save(bad)
    confirm(bad,[a['rowId']],reject=True);s=save(bad,rows=s['rows'])
    check('known number without currency never becomes a CNY assumption')
    body,mime=multipart_payload('SIMULATED-selection.png',base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='),{'purpose':'photo','version':'V1'})
    f=api('POST',f'{root}/{s["id"]}/files',body,token,mime=mime)
    refreshed=get(s['id']);check('upload increments selection version instead of silently invalidating import evidence',refreshed['version']>s['version']);s=refreshed
    s=save(s,refs=[{'id':f['attachmentId']}])
    request=key();s=confirm(s,[a['rowId']],actor=eng,request=request)
    check('unselected incomplete catalogue row does not block a selected valid draft',s['rows'][0]['productId']>0 and s['rows'][2]['productId']==0)
    p=api('GET','/partner-products/'+str(s['rows'][0]['productId']),token=token)
    check('confirmation reuses product save, keeps source supplier and does not adopt or list',p['status']=='draft' and p['listing']=='unrecorded' and p['specs'][0]['sources'][0]['supplierCode']==code)
    check('supplier price evidence does not elevate engineering to customer-finance writes','priceCents' not in p['specs'][0]['sources'][0] and s['rows'][0]['confirmationSnapshot']['row']['unitPrice']=='12.50')
    check('adopted photos are inherited through existing attachment references',f['attachmentId'] in [x['id'] for x in p['files']])
    check('same confirmation request does not create another product',confirm(s,[a['rowId']],actor=eng,request=request)['version']==s['version'])
    changed=json.loads(json.dumps(s));changed['rows'][0]['name']='forbidden rewrite';api('PUT',f'{root}/{s["id"]}',{**changed,'requestId':key()},token,reject=True)
    check('confirmed rows remain frozen while unrelated candidate rows can be saved',get(s['id'])['rows'][0]['name']==s['rows'][0]['name'])
    foreign=api('POST',root,{'name':'QA跨表行','rows':[],'requestId':key()},token)
    api('PUT',f'{root}/{foreign["id"]}',{**foreign,'rows':[b],'requestId':key()},token,reject=True)
    check('row identity cannot be rebound to a different sheet')
    exported=api('GET',f'{root}/{s["id"]}/export?format=csv',token=token,binary=True)
    entries=list(csv.DictReader(io.StringIO(exported.decode('utf-8-sig'))));headers=list(entries[0])
    def csv_bytes(rows):
        buf=io.StringIO();writer=csv.DictWriter(buf,fieldnames=headers);writer.writeheader();writer.writerows(rows);return buf.getvalue().encode('utf-8-sig')
    def preview(rows,actor=token):
        body,mime=multipart_payload('standard.csv',csv_bytes(rows),{})
        return api('POST',f'{root}/{s["id"]}/import/preview',body,actor,mime=mime)
    same=preview(entries);check('full export can be returned unchanged including frozen rows',same['errorCount']==0 and all(x['action']=='same' for x in same['rows']))
    entry=entries[1].copy();entry['款名']='QA离线改款 '+run
    new={h:'' for h in headers};new['款名']='QA离线新候选 '+run
    pv=preview([entry,new]);check('missing Excel rows are retained and new versus modified is previewed',pv['errorCount']==0 and {x['action'] for x in pv['rows']}=={'new','update'})
    imported=api('POST',f'{root}/{s["id"]}/import/{pv["token"]}/commit',token=token)
    again=api('POST',f'{root}/{s["id"]}/import/{pv["token"]}/commit',token=token)
    check('Excel commit retry keeps stable UUIDs and omitted rows without duplicate insertion',len(imported['rows'])==4 and imported==again and imported['rows'][0]['rowId']==a['rowId'])
    api('POST',f'/partner-products/import/{pv["token"]}/commit',token=token,reject=True)
    check('selection import tokens cannot be used in the historical product importer')
    s=get(s['id']);exported=api('GET',f'{root}/{s["id"]}/export?format=csv',token=token,binary=True)
    entries=list(csv.DictReader(io.StringIO(exported.decode('utf-8-sig'))));pv=preview(entries)
    s=save(s,notes='QA另一个编辑者改动')
    api('POST',f'{root}/{s["id"]}/import/{pv["token"]}/commit',token=token,reject=True)
    check('another save after preview causes a version conflict rather than overwrite')
    s['rows'][1]['availablePhoneModelIds']=[p1];s['rows'][1]['selected']=True;s=save(s)
    before=sql("select count(*) from plm_partner_record where kind='product'")
    sql("create function qa_selection_audit_stop() returns trigger language plpgsql as $$ begin if NEW.action='PARTNER_SELECTION_CONFIRM' then raise exception 'QA required audit failure'; end if; return NEW; end $$; create trigger qa_selection_audit_stop before insert on plm_operation_log for each row execute function qa_selection_audit_stop()")
    try:confirm(s,[b['rowId']],reject=True)
    finally:sql('drop trigger qa_selection_audit_stop on plm_operation_log;drop function qa_selection_audit_stop()')
    check('required audit failure rolls back product, selection and file bindings together',sql("select count(*) from plm_partner_record where kind='product'")==before and get(s['id'])['version']==s['version'])
    def race(_):
        try:confirm(s,[b['rowId']]);return True
        except AssertionError:return False
    with ThreadPoolExecutor(max_workers=2) as pool:outcomes=list(pool.map(race,range(2)))
    check('two concurrent confirmations create once and reject the stale writer',sum(outcomes)==1 and int(sql("select count(*) from plm_partner_record where kind='product'"))==int(before)+1)
    target=get(s['id'])['rows'][1]['specId']
    linkrow=row('QA关联已有 '+run,availablePhoneModelIds=[p1],targetSpecId=target)
    linked=api('POST',root,{'name':'QA明确关联','rows':[linkrow],'requestId':key()},token)
    count=sql("select count(*) from plm_partner_record where kind='product'");linked=confirm(linked,[linkrow['rowId']])
    check('explicit existing-spec association does not create another product',linked['rows'][0]['specId']==target and sql("select count(*) from plm_partner_record where kind='product'")==count)
    page=api('GET',root+'?size=1&page=1',token=token);next_page=api('GET',root+'?size=1&page=2',token=token)
    check('sheet pages use stable server ordering and consistent total',len(page['content'])==1 and page['totalElements']==next_page['totalElements'] and page['content'][0]['id']!=next_page['content'][0]['id'])
    display_rows=[row(f'QA界面 {n:03d} 长款式名称／透明磁吸壳',availablePhoneModelIds=[p1],unavailablePhoneModelIds=[p2],selected=False,unitPrice='12.50',currency='CNY',color='透明／原色',supplierItemCode=f'QA-{n:03d}') for n in range(1,61)]
    display=api('POST',root,{'name':'隔离验收用选品表（非业务数据）','rows':display_rows,'requestId':key()},token)
    body,mime=multipart_payload('SIMULATED-table-photo.png',base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='),{'purpose':'photo'})
    photo=api('POST',f'{root}/{display["id"]}/files',body,token,mime=mime);display=get(display['id'])
    display['rows'][0]['refs']=[{'id':photo['attachmentId']}];display=save(display)
    for table,evidence in original.items():check('pre-existing '+table+' rows remain unchanged',sql(evidence['statement'])==evidence['before'])
    fixtures={'sheetId':display['id'],'supplierCode':code,'phoneIds':phone_ids,'flowSheetId':s['id'],'photoId':photo['attachmentId'],'baseUrl':BASE,'frontUrl':'http://127.0.0.1:5196/#/partner-selections?id='+str(display['id'])}
    (out/'fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2))
    (out/'report.json').write_text(json.dumps({'count':len(checks),'checks':checks,'database':DATABASE,'api':BASE,'productionConnections':0},ensure_ascii=False,indent=2))
    print(json.dumps({'passed':len(checks),'report':str(out/'report.json'),'sheetId':display['id']},ensure_ascii=False))

if __name__=='__main__':main()
