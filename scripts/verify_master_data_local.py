#!/usr/bin/env python3
"""Exercise the real API on the already restored LOCAL acceptance database only."""
import argparse
from datetime import date, timedelta
import json
from pathlib import Path
import re
import secrets
import subprocess
import urllib.error
import urllib.request
from local_research_fixture import sql, quote, multipart_payload
from local_launch_evidence import LaunchEvidence


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', required=True)
    parser.add_argument('--port', type=int, default=18082)
    parser.add_argument('--snapshot', default='source-20261016-001')
    parser.add_argument('--baseline-database', default='plm_alignment_baseline_20261016_iqtzfj', help='Immutable restored pre-change backup, not the concurrently used local database')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--browser-state-only', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'plm_alignment_[a-z0-9_]+', args.database):
        parser.error('Only the isolated alignment acceptance database is allowed')
    if not re.fullmatch(r'plm_alignment_baseline_[a-z0-9_]+', args.baseline_database):
        parser.error('Only an immutable restored baseline is allowed for comparison')
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Previous deliberately modified QA versions remain in history, not as current production selections.
    sql(args.database,"""update plm_product_bom_route_formal_selection s set status='invalidated',invalidated_at=now(),invalidated_reason='上一轮隔离QA结束，历史版本保留'
      from plm_product_bom b where b.product_bom_id=s.product_bom_id and b.created_by='alignment-engineering' and b.version_no like 'QA-%' and s.status='active';""")
    base = f'http://127.0.0.1:{args.port}/api/v1'
    def browser_state(session):
        state_file=args.output/'browser-storage.json'
        state_file.write_text(json.dumps({'cookies':[],'origins':[{'origin':'http://127.0.0.1:15173','localStorage':[{'name':'plm_token','value':session['token']}]}]}),encoding='utf8')
        state_file.chmod(0o600)
    if args.browser_state_only:
        browser_state(json.loads((args.output/'browser-session.json').read_text(encoding='utf8')))
        return
    (args.output/'report.json').unlink(missing_ok=True)

    def api(method, path, body=None, token=None, success=True, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(base + path, data=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=180) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as failure:
            payload = json.load(failure)
        if success and payload.get('code') != 0:
            raise AssertionError(f'{method} {path}: {payload.get("message")}')
        if not success and payload.get('code') == 0:
            raise AssertionError('Unexpected success: ' + method + ' ' + path)
        return payload.get('data') if success else payload

    def upload(path,name,content,fields):
        body,content_type=multipart_payload(name,content,fields)
        return api('POST',path,body,token,content_type=content_type)

    password = secrets.token_urlsafe(24)
    hashed = subprocess.run(['/usr/sbin/htpasswd', '-niB', '-C', '4', 'alignment-qa'], input=password + '\n', text=True, capture_output=True, check=True).stdout.split(':', 1)[1].strip()
    uid = int(sql(args.database, "select coalesce((select user_id from sys_user where username='alignment-qa'),(select max(user_id)+100 from sys_user))"))
    sql(args.database, f"""insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions)
       values({uid},'alignment-qa','本地对齐验收','alignment-qa','alignment-qa','本地对齐验收',{quote(hashed)},'active',true)
       on conflict(user_id) do update set password_hash=excluded.password_hash;""")
    session = api('POST', '/auth/login', {'username': 'alignment-qa', 'password': password})
    token = session['token']
    session_file = args.output / 'browser-session.json'
    session_file.write_text(json.dumps(session), encoding='utf8')
    session_file.chmod(0o600)
    browser_state(session)
    checks = []

    def check(name, valid):
        if not valid:
            raise AssertionError(name)
        checks.append(name)

    api('GET', '/products/current-catalog', success=False)
    check('login required', True)
    baseline = sql(args.database, "select md5(string_agg(product_id||':'||production_status||':'||sales_status,',' order by product_id)) from plm_product where deleted_flag=0;")
    preview = api('POST', '/master-data/preview', {'snapshotName': args.snapshot, 'seriesCodes': ['NFA4020']}, token)
    check('pilot preview contains only selected series', preview['seriesCodes'] == ['NFA4020'] and preview['skuCount'] > 0)
    committed = api('POST', '/master-data/commit', {**{key: preview[key] for key in ['importId', 'sourceHash', 'originalHash']}, 'reason': '隔离库：先验收骑士系列，禁止外发'}, token)
    check('pilot commits with no remote writes', committed['status'] == 'committed' and committed['remoteBusinessWrites'] == 0)
    preview = api('POST', '/master-data/preview', {'snapshotName': args.snapshot}, token)
    check('all CRM identities and listing counts', preview['skuCount'] == 3549 and preview['listedCount'] == 3380 and preview['unlistedCount'] == 169)
    check('duplicate item codes preserved as warnings', len(preview['warnings']) == 83)
    result = api('POST', '/master-data/commit', {**{key: preview[key] for key in ['importId', 'sourceHash', 'originalHash']}, 'reason': '隔离库：本期全部来源身份，保留原关系和人工状态'}, token)
    before = sql(args.database, "select (select count(*) from plm_product),(select count(*) from plm_product_bom),(select count(*) from plm_inventory),(select count(*) from plm_process),(select count(*) from plm_partner_spec);")
    repeated = api('POST', '/master-data/commit', {**{key: preview[key] for key in ['importId', 'sourceHash', 'originalHash']}, 'reason': '相同请求幂等验证'}, token)
    check('same commit id is idempotent', repeated == result and before == sql(args.database, "select (select count(*) from plm_product),(select count(*) from plm_product_bom),(select count(*) from plm_inventory),(select count(*) from plm_process),(select count(*) from plm_partner_spec);"))
    options = api('GET', '/products/current-catalog/options', token=token)
    check('catalog includes all SKU and purchased products', options['total'] == 3549 and options['listed'] == 3380 and options['purchased'] == 164)
    rows = api('GET', '/products/current-catalog?size=100', token=token)
    check('four-digit descending server pagination', rows['totalElements'] == 3549 and [r['modelCode'] for r in rows['content']] == sorted([r['modelCode'] for r in rows['content']], reverse=True))
    purchased = api('GET', '/products/current-catalog?seriesCode=NSF4030', token=token)
    check('purchased products do not create manufacturing products', all(r['purchased'] and r['objectType'] == 'partner' for r in purchased['content']))
    check('purchased products need no BOM', api('GET', '/products/current-catalog/' + purchased['content'][0]['sourceId'], token=token)['manufacturing'] == [])
    made = api('GET', '/products/current-catalog?seriesCode=NFA4020&listingStatus=listed', token=token)['content'][0]
    detail = api('GET', '/products/current-catalog/' + made['sourceId'], token=token)
    check('exact scoped recipe and route graph', len(detail['manufacturing']) > 0 and all(r['items'] and r['graph']['nodes'] for r in detail['manufacturing']))
    # Full identity reconciliation, including repeat item codes and leading-zero model codes.
    catalog = []
    for page in range(1, 37):
        catalog.extend(api('GET', f'/products/current-catalog?size=100&page={page}', token=token)['content'])
    source = json.loads((args.output.parent / args.snapshot / 'source.json').read_text(encoding='utf8'))
    check('every CRM source identity reconciles exactly once', len(catalog) == 3549 and {r['sourceId'] for r in catalog} == {r['id'] for r in source['crm']['skus']})
    check('all sources keep original article codes', {r['sourceId']: r['itemCode'] for r in catalog} == {r['id']: r['itemCode'] for r in source['crm']['skus']})
    protected = {
        'plm_product': ('product_id', 'production_status,sales_status,status,source_instance_id,project_kind,project_registered_at,phone_model_member_id'),
        'plm_inventory': ('inventory_id', 'stock_uom,quantity,opening_stock,unit_cost,currency_code'),
        'plm_code_item': ('code_item_id', 'production_status,status,code_value'),
        'plm_product_bom': ('product_bom_id', 'bom_code,version_no,status,released_at,released_by'),
    }
    for table, (pk, columns) in protected.items():
        statement = f"select coalesce(jsonb_object_agg({pk}::text,to_jsonb(v)-'{pk}'),'{{}}'::jsonb)::text from (select {pk},{columns} from {table}) v;"
        old = json.loads(sql(args.baseline_database, statement))
        current = json.loads(sql(args.database, statement))
        check('original IDs and protected facts preserved: ' + table, all(current.get(key) == value for key, value in old.items()))

    # True non-administrator engineering identity; no finance permission or extra administrator review.
    engineer_password = secrets.token_urlsafe(24)
    engineer_hash = subprocess.run(['/usr/sbin/htpasswd', '-niB', '-C', '4', 'alignment-engineering'], input=engineer_password + '\n', text=True, capture_output=True, check=True).stdout.split(':', 1)[1].strip()
    engineer_id = int(sql(args.database, "select coalesce((select user_id from sys_user where username='alignment-engineering'),(select max(user_id)+100 from sys_user))"))
    sql(args.database, f"""insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions)
      values({engineer_id},'alignment-engineering','本地工程验收','alignment-engineering','alignment-engineering','本地工程验收',{quote(engineer_hash)},'active',false)
      on conflict(user_id) do update set password_hash=excluded.password_hash;
      insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by)
      values({engineer_id},'domestic','["project:view","product:view","process:view","collaboration:view","partner:view","bom:write","bom:publish","documents:write"]',true,'fixture','fixture') on conflict(user_id) do update set permissions=excluded.permissions;
      insert into plm_collaboration_assignment(field_group,user_id) values('engineering',{engineer_id}) on conflict do nothing;""")
    engineer_session = api('POST', '/auth/login', {'username': 'alignment-engineering', 'password': engineer_password})
    engineer=engineer_session['token']
    (args.output/'engineering-session.json').write_text(json.dumps(engineer_session),encoding='utf8');(args.output/'engineering-session.json').chmod(0o600)
    check('domestic engineering can use current scoped catalog', api('GET', '/products/current-catalog/options', token=engineer)['total'] == 3549)
    api('POST', '/master-data/preview', {'snapshotName': args.snapshot}, engineer, success=False)
    check('only administrators can import', True)
    public_detail = api('GET', '/products/current-catalog/' + made['sourceId'], token=engineer)
    check('internal source money is not leaked', 'basePrice' not in public_detail['source'] and all('unitPrice' not in item for r in public_detail['manufacturing'] for item in r['items']))
    recipe = detail['manufacturing'][0]
    base_bom = recipe['bomId']
    old_snapshot = api('GET', f'/boms/{base_bom}/workbench', token=token)
    copy = api('POST', f'/boms/{base_bom}/copy-version', {'versionNo': 'QA-' + secrets.token_hex(4), 'selectedColors': []}, engineer)
    new_id = copy['productBomId']
    workbench = api('GET', f'/boms/{new_id}/workbench', token=engineer)
    try:
        sql(args.database,f"delete from plm_collaboration_assignment where field_group='engineering' and user_id={engineer_id};")
        api('PUT',f'/boms/{new_id}',{'bomName':'不应被非工程覆盖','bomType':'mbom','versionNo':'QA-DENIED','originalHash':workbench['routes'][0]['originalHash']},engineer,success=False)
        check('BOM write permission alone cannot bypass engineering responsibility',True)
    finally:
        sql(args.database,f"insert into plm_collaboration_assignment(field_group,user_id) values('engineering',{engineer_id}) on conflict do nothing;")
    try:
        sql(args.database,f"update plm_pilot_account set all_objects=false where user_id={engineer_id};")
        api('GET',f'/process-routes/{recipe["processId"]}',token=engineer,success=False)
        api('GET',f'/process-routes/{recipe["processId"]}/graph',token=engineer,success=False)
        check('process detail and graph respect the same object scope',True)
    finally:
        sql(args.database,f"update plm_pilot_account set all_objects=true where user_id={engineer_id};")
    check('copy preserves node and per-article targets', all(i.get('nodeId') and i.get('quantityExact') for r in workbench['routes'] for i in r['items']))
    routes = workbench['routes']
    if routes[0]['items']:
        routes[0]['items'][0]['quantity'] = '0.00000001'
    api('PUT', f'/boms/{new_id}/routes', routes, engineer)
    saved = api('GET', f'/boms/{new_id}/workbench', token=engineer)
    check('eight decimal quantities round-trip without floating conversion', saved['routes'][0]['items'][0]['quantityExact'] == '0.00000001')
    api('PUT', f'/boms/{new_id}/routes', routes, engineer, success=False)
    check('stale structure edits are rejected', True)
    api('POST', f'/boms/{new_id}/submit-review', token=engineer)
    api('POST', f'/boms/{new_id}/confirm-current-version', token=engineer)
    api('POST', f'/boms/{new_id}/confirm-current-version', token=engineer)
    check('engineering alone confirms the exact version', api('GET', f'/boms/{new_id}', token=engineer)['status'] == 'released')
    api('PUT', f'/boms/{new_id}/routes', saved['routes'], engineer, success=False)
    check('confirmed versions are immutable', True)
    check('source baseline and its historical adopted content remain unchanged', api('GET', f'/boms/{base_bom}/workbench', token=token) == old_snapshot)
    check('explicit engineering selection is used instead of latest draft', api('GET', '/products/current-catalog/' + made['sourceId'], token=engineer)['manufacturing'][0]['bomId'] == new_id)
    check('no ERP delivery event for source confirmation', sql(args.database, f'select count(*) from plm_pilot_bom_release where bom_id={new_id};') == '0')
    # A new development draft uses the same MES node/edge and BOM fields, without legacy invented codes.
    qa_name='QA'+secrets.token_hex(5)+'-MES工程格式'
    new_product=api('POST','/product-research',{'productName':qa_name,'productType':'product_line','versionNo':'V1','createdBy':'fixture'},token)['productId']
    def development():return api('GET',f'/product-research/{new_product}',token=token)
    def action(name,data):return api('POST',f'/product-research/{new_product}/workflow',{'action':name,'version':development()['workflow']['version'],'requestId':secrets.token_hex(16),'data':data},token)
    def hand(node):return api('POST',f'/projects/{new_product}/timeline/{node}/confirm',{'developmentVersion':development()['workflow']['version']},token)
    phone=made['phoneModelId']
    api('PUT',f'/product-research/{new_product}',{'productName':qa_name,'requirements':'隔离 QA：真实 MES 字段及指定版本承接','phoneModelIds':[phone],'ownerUserId':engineer_id,'version':development()['workflow']['version']},token)
    evidence=LaunchEvidence(args.database,lambda method,path,body=None,success=True:api(method,path,body,token,success),lambda pid:development(),lambda name,data,pid:action(name,data),engineer_id,upload)
    # Use the shared review fixture's day for the whole synthetic lifecycle. A
    # fixed historical date would conflict with its real-date review evidence.
    fixture_day=date.fromisoformat(evidence.today)
    action('plan_draft',{'phoneModelIds':[phone],'pilotDate':evidence.today,'launchDate':(fixture_day+timedelta(days=25)).isoformat(),'method':'direct','needsMold':False,'references':[]})
    action('confirm_design_basis',{})
    template=api('GET',f'/process-routes/{recipe["processId"]}',token=engineer)
    node_keys={n['processId']:n['nodeCode'] for n in template['operations']}
    edges=[{'fromNodeCode':node_keys[e['fromNodeId']],'toNodeCode':node_keys[e['toNodeId']],'edgeNo':e['edgeNo'],'relationType':e['relationType'],'label':e.get('label'),'condition':e.get('condition')} for e in template['graph']['edges']]
    graph=api('POST',f'/projects/{new_product}/process-routes',{'processName':template['processName'],'versionNo':'QA-G1','sourceProcessId':template['processId'],'sourceRouteCode':template['sourceRouteCode'],'operations':template['operations'],'edges':edges},engineer)
    check('new project route copies actual graph edges and node config',len(graph['graph']['nodes'])==len(template['graph']['nodes']) and len(graph['graph']['edges'])==len(template['graph']['edges']))
    graph_payload={'processName':graph['processName'],'versionNo':graph['versionNo'],'sourceProcessId':graph['processId'],'sourceRouteCode':graph['sourceRouteCode'],'operations':graph['operations'],'edges':[], 'graphOriginalHash':graph['graphOriginalHash']}
    api('PUT',f'/process-routes/{graph["processId"]}',{**graph_payload,'edges':None},engineer,success=False)
    check('missing graph data cannot silently erase edges',True)
    blank=api('POST',f'/projects/{new_product}/boms',{'bomName':'QA-MES格式工程草稿','bomType':'mbom','versionNo':'QA-G1','processId':graph['processId']},engineer)
    check('unknown base output and currency are not invented',sql(args.database,f"select quantity is null and currency_code is null from plm_product_bom where product_bom_id={blank['productBomId']};")=='t')
    draft=api('GET',f'/boms/{blank["productBomId"]}/workbench',token=engineer)
    draft_routes=draft['routes']
    draft_routes[0]['colors']=old_snapshot['routes'][0]['colors']
    draft_routes[0]['colorItems']=old_snapshot['routes'][0]['colorItems']
    material=recipe['items'][0]
    draft_routes[0]['items']=[{'lineNo':1,'inventoryId':material['inventoryId'],'itemCode':material['childCode'],'itemName':material['childName'],'quantity':'0.00000001','unit':material['unit'],'nodeId':graph['operations'][0]['processId']}]
    api('PUT',f'/boms/{blank["productBomId"]}/routes',draft_routes,engineer)
    api('POST',f'/boms/{blank["productBomId"]}/items',{'lineNo':2,'itemName':material['childName'],'quantity':'1','unit':material['unit']},engineer,success=False)
    check('legacy item writes cannot bypass explicit nodes and range checks',True)
    api('POST',f'/boms/{blank["productBomId"]}/submit-review',token=engineer)
    api('POST',f'/boms/{blank["productBomId"]}/confirm-current-version',token=engineer,success=False)
    check('incomplete technical draft is allowed but cannot be confirmed',True)
    original_hash=api('GET',f'/boms/{blank["productBomId"]}/workbench',token=engineer)['routes'][0]['originalHash']
    api('PUT',f'/boms/{blank["productBomId"]}',{'bomName':'QA-MES格式工程草稿','bomType':'mbom','versionNo':'QA-G1','parentCode':recipe['parentCode'],'parentName':recipe['parentName'],'outputQty':'10.0000','originalHash':original_hash},engineer)
    api('POST',f'/boms/{blank["productBomId"]}/confirm-current-version',token=engineer)
    check('engineering confirms new BOM plus exact process version without finance approval',api('GET',f'/boms/{blank["productBomId"]}',token=engineer)['status']=='released' and api('GET',f'/process-routes/{graph["processId"]}',token=engineer)['status']=='locked')
    refs=[{'type':'bom','id':blank['productBomId']},{'type':'process','id':graph['processId']}]
    api('POST',f'/projects/{new_product}/timeline/LAUNCH_DESIGN/confirm',{'developmentVersion':development()['workflow']['version']},token,success=False)
    check('engineering BOM confirmation cannot bypass design review',development()['currentStepNo']==1)
    evidence.review(new_product,refs);hand('LAUNCH_DESIGN')
    action('no_mold',{'reason':'隔离验收：选用既有技术方案，无新增实物模具','references':refs});hand('LAUNCH_MOLD')
    sample=api('POST',f'/product-research/{new_product}/rounds',{'kind':'sample','recordDate':evidence.today,'title':'隔离用例：指定图及用料版本实样','result':'passed','references':refs,'confirm':True,'details':{'phoneModelIds':[phone],'makerName':'隔离 QA','designSummary':'MES 实际图格式','actualResult':'仅隔离验收夹具，不是实际业务通过事实'},'version':development()['workflow']['version'],'requestId':secrets.token_hex(16)},token)
    bom_reference=next(r for r in sample['references'] if r['type']=='bom')
    process_reference=next(r for r in sample['references'] if r['type']=='process')
    check('sample adopts exact BOM node targets and process edges',len(bom_reference['content']['materialTargets'])==1 and len(process_reference['content']['edges'])==len(graph['graph']['edges']))
    action('select_round',{'roundId':sample['roundId']})
    api('POST',f'/projects/{new_product}/timeline/LAUNCH_VALIDATION/confirm',{'developmentVersion':development()['workflow']['version']},token,success=False)
    check('sample pass alone cannot bypass reliability review',development()['currentStepNo']==3)
    api('POST',f'/product-research/{new_product}/rounds',evidence.round_payload(new_product,sample,'test',tests=[]),token,success=False)
    reliability=evidence.round(new_product,sample,'test');hand('LAUNCH_VALIDATION')
    check('handover retains the exact sample round number as well as its ID',development()['workflow']['stage3Basis']['sample']['roundNo']==sample['roundNo'])
    api('POST',f'/product-research/{new_product}/rounds',evidence.round_payload(new_product,reliability,'pilot',inspectedQty=9),token,success=False)
    pilot=evidence.round(new_product,reliability,'pilot')
    check('required test results and full-inspection quantities are enforced',pilot['details']['yieldRate']==100)
    workbench=api('GET',f'/boms/{blank["productBomId"]}/workbench',token=token)
    action('unit_cost_from_bom',{'bomId':blank['productBomId'],'routeId':workbench['routes'][0]['productBomRouteId']})
    cost_draft=development()['workflow']['unitCostDraft']
    check('unknown currency and batch quantities are not invented as per-unit costs',cost_draft['currency']=='' and cost_draft['sourceOutputQty']==10 and cost_draft['rows'][0]['sourceQuantity']==0.00000001 and cost_draft['rows'][0]['quantity'] is None and not cost_draft['complete'])
    denied=api('POST',f'/product-research/{new_product}/workflow',{'action':'unit_cost_confirm','version':development()['workflow']['version'],'data':cost_draft},engineer,success=False)
    check('BOM engineering responsibility does not grant cost confirmation permission: '+denied['message'],denied['code']==40103)
    cost_id=evidence.cost(new_product,blank['productBomId'])
    action('release_draft',{'confirmationDate':evidence.today,'phoneModelIds':[phone],'references':refs,'pilotRoundId':pilot['roundId'],'unitCostVersionId':cost_id});hand('LAUNCH_PILOT')
    api('POST',f'/projects/{new_product}/publish',{'developmentVersion':development()['workflow']['version'],'riskConfirmed':True,'exceptionReason':'仅隔离 QA：报告及签样待补','supplementDueDate':(fixture_day+timedelta(days=16)).isoformat(),'developmentResponsibleUserId':engineer_id,'developmentReferences':refs},token)
    release=development()['release'];frozen_release=json.dumps(release,sort_keys=True)
    check('final package keeps exact adopted technical versions',[(r['type'],r['id']) for r in release['references']]==[(r['type'],r['id']) for r in refs])
    api('POST',f'/boms/{blank["productBomId"]}/copy-version',{'versionNo':'QA-NEXT','selectedColors':[]},engineer)
    check('new correction draft never rewrites released sample or final package',json.dumps(development()['release'],sort_keys=True)==frozen_release)
    check('purchased source specs keep real articles and unique CRM identities',sql(args.database,"select count(*) from plm_master_data_source s join plm_partner_spec p on p.spec_id=s.partner_spec_id and p.record_id=s.object_id where s.source_system='crm' and s.source_kind='product_sku' and s.object_type='partner' and p.website_sku=s.source_code and p.data->>'externalId'=s.source_id;")=='164')

    # Deliberate optimistic conflict and mandatory-audit failure in the isolated database.
    conflict = api('POST', '/master-data/preview', {'snapshotName': args.snapshot}, token)
    sql(args.database, f"update plm_product set updated_at=updated_at+interval '0.000001 seconds' where product_id={made['objectId']};")
    api('POST', '/master-data/commit', {**{key: conflict[key] for key in ['importId','sourceHash','originalHash']}, 'reason': '隔离并发冲突验收，不得覆盖'}, token, success=False)
    check('concurrent local changes refuse the entire import', True)
    failure = api('POST', '/master-data/preview', {'snapshotName': args.snapshot}, token)
    original_dates = sql(args.database, 'select md5(string_agg(product_id||updated_at::text,\',\' order by product_id)) from plm_product;')
    try:
        sql(args.database, """create or replace function plm.qa_alignment_reject_audit() returns trigger language plpgsql as $$begin if new.business_type='MASTER_DATA' then raise exception 'isolated forced audit rejection'; end if; return new; end$$;
          create trigger qa_alignment_reject_audit before insert on plm_operation_log for each row execute function plm.qa_alignment_reject_audit();""")
        api('POST', '/master-data/commit', {**{key: failure[key] for key in ['importId','sourceHash','originalHash']}, 'reason': '隔离强制审计失败回滚'}, token, success=False)
        check('audit failure rolls back all source and object edits', original_dates == sql(args.database, 'select md5(string_agg(product_id||updated_at::text,\',\' order by product_id)) from plm_product;'))
    finally:
        sql(args.database, 'drop trigger if exists qa_alignment_reject_audit on plm_operation_log;drop function if exists plm.qa_alignment_reject_audit();')
    report = {'checks': checks, 'passed': len(checks), 'database': args.database, 'sourceHash': result['sourceHash'], 'counts': options, 'objectCounts': before, 'originalStateFingerprint': baseline, 'remoteBusinessWrites': 0}
    (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, default=str), encoding='utf8')
    print(json.dumps({'passed': len(checks), 'report': str(args.output / 'report.json')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
