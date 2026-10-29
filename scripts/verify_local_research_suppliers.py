#!/usr/bin/env python3
"""Supplier selection/replacement on the existing research workflow, in the disposable fixture only."""
import json
import uuid
from local_research_fixture import main, sql
from verify_local_product_launch import verify_launch


def verify(ctx):
    verify_launch(ctx)
    api, check, token, database = (ctx[k] for k in ('api', 'check', 'token', 'database'))
    baseline_checks = len(ctx['checks'])
    key = lambda: uuid.uuid4().hex
    get = lambda pid: api('GET', f'/product-research/{pid}', token=token)
    original = sql(database, "select md5(to_jsonb(p)::text) from plm_product p where product_id=20447")
    original_workflow = sql(database, "select md5(to_jsonb(s)::text) from plm_product_development_state s where product_id=20447")

    def supplier(name, status='active'):
        return api('POST', '/inventories/suppliers', {'supplierName': name, 'supplierType': 'company', 'status': status, 'requestId': key()}, token)

    a, b, c = [supplier(name) for name in ('QA 原技术供方', 'QA 替换技术供方', 'QA 后续技术供方')]
    inactive = supplier('QA 已停用技术供方', 'inactive')
    code_a, code_b, code_c = [s['supplierCode'] for s in (a, b, c)]

    def assignments(complete=True):
        return [{'mode': 'supplier', 'supplierCode': code_a, 'role': 'finished_product', 'scope': '整壳PC' if complete else '', 'basis': '原供方设计沟通与打样依据' if complete else ''}]

    def create(name, stage=1, complete=True):
        pid = api('POST', '/product-research', {'productName': name, 'productType': 'product_line', 'versionNo': 'V1', 'createdBy': 'fixture'}, token)['productId']
        d = get(pid)
        d = api('PUT', f'/product-research/{pid}/design', {'profile': {'productName': name, 'requirements': '', 'model': '', 'ownerUserId': None, 'phoneModelIds': [], 'references': [], 'version': d['workflow']['version']}, 'plan': {'method': 'direct', 'needsMold': False, 'phoneModelIds': [], 'supplierAssignments': assignments(complete)}}, token)
        if stage > 1:
            # Only these new fixture projects receive a stage setup; the full workflow ran above.
            sql(database, f"update plm_product set current_step_no={stage} where product_id={pid}; update plm_product_development_state set data=jsonb_set(data,'{{plan}}',(data->'planDraft')||'{{\"planVersion\":1}}'::jsonb) where product_id={pid}")
        return get(pid)

    def change(d, data=None, request=None, session=token, success=True):
        return api('POST', f'/product-research/{d["productId"]}/workflow', {'action': 'supplier_change', 'version': d['workflow']['version'], 'requestId': request or key(), 'data': {'stage': d.get('workflow', {}).get('revision', {}).get('stage', d['currentStepNo']), 'previousSupplierCode': code_a, 'supplierCode': code_b, 'role': 'finished_product', 'scope': '整壳PC', 'reason': '原供方反馈透明件精度技术无法实现，改由新供方验证', **(data or {})}}, session, success=success)

    incomplete = create('QA 立项可先选择技术供方', complete=False)
    check('initiation saves a real supplier before scope, samples, BOM or future evidence exists', incomplete['workflow']['planDraft']['supplierAssignments'][0]['supplierCode'] == code_a and not incomplete['workflow']['planDraft']['supplierAssignments'][0]['basis'] and not incomplete['rounds'])
    before = get(incomplete['productId'])
    missing = change(before, {'scope': '', 'reason': '  '}, success=False)
    check('a blank replacement reason fails without changing the original draft', '更换原因' in missing['message'] and get(before['productId']) == before)
    bad_draft = json.loads(json.dumps(before['workflow']['planDraft']))
    bad_draft['supplierAssignments'][0]['supplierCode'] = code_b
    payload = {'profile': {'productName': before['productName'], 'requirements': '', 'model': '', 'ownerUserId': None, 'phoneModelIds': [], 'references': [], 'version': before['workflow']['version']}, 'plan': bad_draft}
    api('PUT', f'/product-research/{before["productId"]}/design', payload, token, success=False)
    check('the original design writer cannot bypass the replacement reason', get(before['productId']) == before)
    payload['plan']['reason'] = '原供方无法实现表面工艺，立项草稿改选新供方'
    after = api('PUT', f'/product-research/{before["productId"]}/design', payload, token)
    event = next(e for e in after['events'] if e['kind'] == 'supplier_change')
    check('a design-editor supplier replacement records old/new canonical names and the engineer’s reason', event['data']['before']['supplierAssignments'][0]['supplierName'] == a['supplierName'] and event['data']['after']['supplierAssignments'][0]['supplierName'] == b['supplierName'] and event['data']['reason'] == payload['plan']['reason'] and event['createdBy'])

    stage_projects = {}
    for stage in (1, 2, 3, 4):
        d = create('QA 供应商更换·阶段' + str(stage), stage)
        pid = d['productId']
        d = api('POST', f'/product-research/{pid}/workflow', {'action': 'engineering_confirm', 'version': d['workflow']['version'], 'requestId': key(), 'data': {'stage': stage}}, token)
        old_engineering = d['workflow']['engineeringBasis']
        old_requirement = d['workflow'].get('requirement')
        check('the original engineering confirmation is current before replacement at stage ' + str(stage), d['engineering']['current'])
        request = key()
        updated = change(d, request=request)
        repeated = change(d, request=request)
        row = next(e for e in updated['events'] if e['kind'] == 'supplier_change')
        check('replacement stays in the current stage and does not rewrite prior engineering/requirements ' + str(stage), updated['currentStepNo'] == stage and updated['workflow']['engineeringBasis'] == old_engineering and updated['workflow'].get('requirement') == old_requirement and updated['engineering']['confirmed'] and not updated['engineering']['current'])
        check('supplier ID/code and cause are snapshotted with operator/time once at stage ' + str(stage), row['data']['before']['supplierAssignments'][0]['supplierId'] == a['supplierId'] and row['data']['after']['supplierAssignments'][0]['supplierId'] == b['supplierId'] and row['data']['reason'] and row['createdAt'] and row['createdBy'] and len([e for e in repeated['events'] if e['kind'] == 'supplier_change']) == 1 and repeated['workflow']['version'] == updated['workflow']['version'])
        check('old supplier evidence is not automatically approved for the replacement ' + str(stage), updated['workflow']['planDraft']['supplierAssignments'][0]['basis'] == '')
        api('POST', f'/product-research/{pid}/workflow', {'action': 'engineering_confirm', 'version': updated['workflow']['version'], 'requestId': key(), 'data': {'stage': stage, 'reason': '仍未取得新供方采用依据'}}, token, success=False)
        stage_projects[str(stage)] = pid

    d = create('QA 更换负向权限与状态', 2)
    for data, label in [({'supplierCode': code_a}, 'same supplier'), ({'supplierCode': 'QA-NO-SUCH-SUPPLIER'}, 'unknown supplier'), ({'supplierCode': inactive['supplierCode']}, 'inactive supplier'), ({'scope': '别的部件'}, 'stale scope'), ({'stage': 3}, 'stale stage')]:
        before = get(d['productId'])
        change(before, data, success=False)
        check(label + ' cannot mutate supplier assignments', get(d['productId']) == before)
    stale = {**d, 'workflow': {**d['workflow'], 'version': 0}}
    change(stale, success=False)
    check('optimistic version protects the supplier change too', get(d['productId']) == d)
    change(d, session=ctx['limited_token'], success=False)
    check('project/document permissions alone cannot grant supplier maintenance', get(d['productId']) == d)
    sql(database, f"update plm_product set lock_status='frozen' where product_id={d['productId']}")
    frozen = get(d['productId'])
    change(frozen, success=False)
    check('frozen development cannot reassign a supplier', get(d['productId']) == frozen and not frozen['canChangeSupplier'])

    d = create('QA 相同供方不同范围', 3)
    second = {**assignments()[0], 'scope': '整壳TPU'}
    escaped = json.dumps(second).replace("'", "''")
    sql(database, f"update plm_product_development_state set data=jsonb_set(jsonb_set(data,'{{plan,supplierAssignments}}',(data#>'{{plan,supplierAssignments}}')||'[{escaped}]'::jsonb),'{{planDraft,supplierAssignments}}',(data#>'{{planDraft,supplierAssignments}}')||'[{escaped}]'::jsonb) where product_id={d['productId']}")
    updated = change(get(d['productId']))
    rows = updated['workflow']['plan']['supplierAssignments']
    check('same supplier in different scopes is not globally replaced', any(s['scope'] == '整壳PC' and s['supplierCode'] == code_b for s in rows) and any(s['scope'] == '整壳TPU' and s['supplierCode'] == code_a for s in rows))

    check('the existing Toujia product and workflow remain unchanged', original == sql(database, "select md5(to_jsonb(p)::text) from plm_product p where product_id=20447") and original_workflow == sql(database, "select md5(to_jsonb(s)::text) from plm_product_development_state s where product_id=20447"))
    (ctx['output'] / 'supplier-fixtures.json').write_text(json.dumps({'stages': stage_projects, 'supplierA': a, 'supplierB': b, 'supplierC': c, 'incompleteProjectId': incomplete['productId'], 'supplierChecks': len(ctx['checks']) - baseline_checks, 'existingLaunchChecks': baseline_checks}))


if __name__ == '__main__':
    main(verify)
