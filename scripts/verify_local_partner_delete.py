#!/usr/bin/env python3
"""Exercise product/spec deletion using the existing disposable partner fixture."""
import datetime
import json
import uuid
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote
from local_research_fixture import main, sql


def verify(ctx):
    api, check, token, database, account = (ctx[k] for k in ('api', 'check', 'token', 'database', 'account'))
    key = lambda: uuid.uuid4().hex
    get = lambda pid: api('GET', f'/partner-products/{pid}', token=token)

    def product(name, count=1, sources=None):
        return api('POST', '/partner-products', {'name': name, 'requestId': key(),
            'specs': [{'specification': f'{name} 原规格 {n}', 'sources': sources or []} for n in range(count)]}, token)

    def delete(p, spec=None, success=True, request=None, session=token):
        return api('DELETE', f'/partner-products/{p["id"]}' + (f'/specs/{spec}' if spec else ''),
            {'version': p['version'], 'requestId': request or key()}, session, success=success)

    p = product('QA deletion name only', 0)
    stale = delete({**p, 'version': 0}, success=False)
    check('stale delete preserves the record and asks for a reload', '记录已变化' in stale['message'] and get(p['id'])['version'] == p['version'])
    request = key()
    first = delete(p, request=request)
    check('name-only product deletion and an identical retry are idempotent', first == delete(p, request=request))
    check('one delete event is retained after retry', sql(database, f"select count(*) from plm_partner_event where record_id={p['id']} and action='product_delete'") == '1')
    api('GET', f'/partner-products/{p["id"]}', token=token, success=False)
    check('deleted detail is inaccessible', True)

    p = product('QA deletion 164 specifications', 164)
    file = ctx['upload'](f'/partner-products/{p["id"]}/files', 'QA-delete.png', ctx['image'], {'purpose': 'photo', 'version': 'V1'})
    revision_count = int(sql(database, f"select count(*) from plm_partner_revision where record_id={p['id']}"))
    delete(p)
    check('deleting a product removes all 164 specs from live choices', sql(database, f"select count(*) from plm_partner_spec where record_id={p['id']} and deleted_flag=0") == '0' and all(s['productId'] != p['id'] for s in api('GET', '/partner-options', token=token)['specs']))
    check('deletion retains the old IDs, files and audit instead of erasing them', sql(database, f"select count(*) from plm_partner_spec where record_id={p['id']}") == '164' and sql(database, f"select deleted_flag from plm_attachment where attachment_id={file['attachmentId']}") == '0' and int(sql(database, f"select count(*) from plm_partner_revision where record_id={p['id']}")) == revision_count + 1)
    page = api('GET', '/partner-products?keyword=' + quote(p['name']), token=token)
    check('deleted product disappears consistently from search counts and status statistics', page['content'] == [] and page['totalElements'] == 0 and page['statistics'] == [])

    p = product('QA delete individual', 2)
    original = p
    removed, kept = (s['id'] for s in p['specs'])
    request = key()
    p = delete(p, removed, request=request)
    check('individual spec delete refreshes summary and keeps the other real ID', [s['id'] for s in p['specs']] == [kept] and original['specs'][0]['name'] not in p['summary'] and p['version'] == original['version'] + 1)
    check('spec delete retry returns current data without another revision', delete(original, removed, request=request)['version'] == p['version'])
    check('a request key cannot be reused to delete a different spec', '提交标识已用于其他动作' in delete(p, kept, request=request, success=False)['message'])
    foreign = product('QA delete ownership', 1)
    check('foreign spec cannot be removed by naming another product', '不属于当前产品' in delete(p, foreign['specs'][0]['id'], success=False)['message'])
    api('PUT', f'/partner-products/{p["id"]}', {**p, 'specs': original['specs'], 'requestId': key()}, token, success=False)
    check('stale editors cannot resurrect a deleted spec', len(get(p['id'])['specs']) == 1)
    api('PUT', f'/partner-products/{p["id"]}', {**p, 'specs': [], 'requestId': key()}, token, success=False)
    check('ordinary save cannot bypass checked deletion', len(get(p['id'])['specs']) == 1)

    readonly_id, readonly = account('partner-delete-readonly', 'domestic', ['partner:view'], True)
    delete(p, success=False, session=readonly['token'])
    delete(p, kept, success=False, session=readonly['token'])
    limited_id, limited = account('partner-delete-limited', 'domestic', ['partner:view', 'partner:write'], False)
    delete(p, success=False, session=limited['token'])
    check('server enforces both write permission and object scope', get(p['id'])['version'] == p['version'])
    sql(database, f"update plm_partner_record set source_project_id={ctx['legacy']['productId']} where record_id={foreign['id']}; insert into plm_pilot_scope(user_id,entity_type,entity_id) values({limited_id},'product',{ctx['legacy']['productId']});")
    delete(p, success=False, session=limited['token'])
    check('access to another linked project does not grant access to unrelated products', get(p['id'])['version'] == p['version'])
    sql(database, f"insert into plm_pilot_scope(user_id,entity_type,entity_id) values({limited_id},'partner',{p['id']});")
    retry_key = key()
    delete(p, request=retry_key, session=limited['token'])
    check('authorized scoped maintainers can retry their own deletion', delete(p, request=retry_key, session=limited['token'])['deleted'])

    supplier_profile = api('POST', '/inventories/suppliers', {'supplierName': 'QA deletion supplier', 'supplierType': 'person', 'requestId': key()}, token)
    supplier = supplier_profile['supplierCode']
    customer = api('POST', '/customers', {'customerName': 'QA deletion customer', 'requestId': key()}, token)
    referenced = product('QA source with sample and quotation', sources=[{'supplierCode': supplier}])
    referenced_spec = referenced['specs'][0]['id']
    api('POST', '/samples', {'sourceType': 'partner', 'specId': referenced_spec, 'supplierCode': supplier, 'qty': 1, 'location': 'QA deletion shelf', 'date': datetime.date.today().isoformat(), 'requestId': key()}, token)
    api('POST', '/partner-sales', {'saleType': 'inquiry', 'customerId': customer['customerId'], 'currency': 'CNY', 'lines': [{'specId': referenced_spec, 'supplierCode': supplier, 'qty': 1, 'unitPrice': '1'}], 'requestId': key()}, token)
    blocked = delete(referenced, success=False)
    delete(referenced, referenced_spec, success=False)
    check('sales and physical samples keep their source product and spec', '销售／询价' in blocked['message'] and '样品' in blocked['message'] and get(referenced['id'])['version'] == referenced['version'])

    source = [{'supplierCode': supplier}]
    p = product('QA quotation reference', 2, source)
    spec = p['specs'][0]['id']
    api('POST', '/partner-operations/supplier_quote', {'name': 'QA quoted source', 'requestId': key(), 'supplierCode': supplier, 'specId': spec}, token)
    check('new supplier quotation dependencies also prevent deletion', '供方报价' in delete(p, success=False)['message'] and '供方报价' in delete(p, spec, success=False)['message'])
    check('a used product can still remove a different unused specification', len(delete(p, p['specs'][1]['id'])['specs']) == 1)

    for kind, data in [('fulfilment', lambda pid, sid: {'lines': [{'specId': sid, 'productId': pid}]}),
                       ('inspection', lambda pid, sid: {'allowedScope': [{'specId': sid, 'productId': pid}]}),
                       ('listing', lambda pid, sid: {'productId': pid, 'basis': {'specs': [{'specId': sid}]}})]:
        p = product('QA ' + kind + ' protection')
        sid = p['specs'][0]['id']
        facts = json.dumps(data(p['id'], sid)).replace("'", "''")
        # Seed minimal historical shapes; full execution workflows have their own verifier.
        sql(database, f"insert into plm_partner_record(kind,code,name,data,created_by,updated_by,owner_user_id) values('{kind}','QA-{key()}','QA history','{facts}'::jsonb,'fixture','fixture',{ctx['owner_id']})")
        delete(p, success=False)
        delete(p, sid, success=False)
        check('historical ' + kind + ' evidence protects both product and spec', get(p['id'])['version'] == p['version'])

    p = product('QA historical sale no longer in current lines')
    sid = p['specs'][0]['id']
    history = json.dumps({'lines': [{'specId': sid, 'snapshot': {'productId': p['id']}}]}).replace("'", "''")
    record = sql(database, f"insert into plm_partner_record(kind,code,name,created_by,updated_by,owner_user_id) values('sale','QA-{key()}','QA old sales line','fixture','fixture',{ctx['owner_id']}) returning record_id")
    sql(database, f"insert into plm_partner_revision(record_id,revision,data,created_by) values({record},1,'{history}'::jsonb,'fixture')")
    delete(p, success=False)
    delete(p, sid, success=False)
    check('a historical revision protects a source after the live line changes', get(p['id'])['version'] == p['version'])
    sql(database, f"delete from plm_partner_revision where record_id={record}; insert into plm_partner_event(record_id,action,data,created_by,actor_id) values({record},'QA_historical','{history}'::jsonb,'fixture',{ctx['owner_id']})")
    delete(p, success=False)
    delete(p, sid, success=False)
    check('historical event snapshots also protect their original source', get(p['id'])['version'] == p['version'])

    p = product('QA current listing protection')
    sql(database, f"update plm_partner_record set data=data||'{{\"listing\":\"listed\"}}'::jsonb where record_id={p['id']}")
    delete(p, success=False)
    delete(p, p['specs'][0]['id'], success=False)
    check('a currently listed product cannot lose its product or spec', get(p['id'])['listing'] == 'listed')

    for race in range(3):
        p = product('QA delete create race ' + str(race), sources=source)
        def sample():
            return api('POST', '/samples', {'sourceType': 'partner', 'specId': p['specs'][0]['id'], 'supplierCode': supplier, 'qty': 1, 'location': 'QA race', 'date': datetime.date.today().isoformat(), 'requestId': key()}, token)
        def attempt(call):
            try:
                return call()
            except AssertionError:
                return None
        with ThreadPoolExecutor(max_workers=2) as pool:
            deleted, sampled = list(pool.map(attempt, [lambda: delete(p), sample]))
        check('concurrent sample creation and product deletion cannot both succeed ' + str(race), bool(deleted) != bool(sampled))

    browser_product = product('QA 浏览器删除验证', 2)
    browser_bulk = product('QA 浏览器整款删除验证', 164)
    (ctx['output'] / 'deletion-fixtures.json').write_text(json.dumps({'productId': browser_product['id'], 'bulkProductId': browser_bulk['id'], 'blockedProductId': referenced['id'], 'readonlyUserId': readonly_id}))


if __name__ == '__main__':
    main(verify)
