#!/usr/bin/env python3
"""Supplier workflow acceptance. Fixed disposable DB/loopback API only, never production.

Requires a restored offline QA container and the candidate running on 18089.
Generated credentials and simulated quality/signatures stay in the private QA directory.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import base64
import datetime
import json
from pathlib import Path
import secrets
import struct
import subprocess
import urllib.error
import urllib.request
import uuid
import zlib

from local_research_fixture import multipart_payload, quote

CONTAINER = 'plm-cooperation-qa-20261019'
DATABASE = 'plm_cooperation_qa'
BASE = 'http://127.0.0.1:18089/api/v1'


def sql(statement):
    return subprocess.check_output(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-U', 'postgres', '-d', DATABASE, '-qAt', '-v', 'ON_ERROR_STOP=1'], input=('set search_path=plm,public;\n' + statement).encode(), stderr=subprocess.PIPE).decode().strip()


def png(ink=True):
    def chunk(kind, data):
        return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)
    # Explicitly SIMULATED QA strokes, never a real person's signature.
    rows = [b'\0' + b''.join(b'\x18\x2b\x49\xff' if ink and 60 < x < 570 and abs(y - (75 + (x % 80))) < 3 else b'\xff\xff\xff\0' for x in range(640)) for y in range(200)]
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 640, 200, 8, 6, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b'')


def main():
    global CONTAINER, DATABASE, BASE
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--deployment-clone', action='store_true', help='Fixed local production-backup rehearsal only; never a remote URL')
    args = parser.parse_args()
    if args.deployment_clone:
        CONTAINER, DATABASE, BASE = 'plm-cooperation-release-20261019', 'plm_cooperation_release_qa', 'http://127.0.0.1:18090/api/v1'
    args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
    info = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER]))[0]
    assert info['Config']['Labels'].get('purpose') == 'plm-cooperation-isolated-acceptance'
    assert info['NetworkSettings']['Ports']['5432/tcp'][0] == {'HostIp': '127.0.0.1', 'HostPort': '15441' if args.deployment_clone else '15439'}
    assert sql('select current_database();') == DATABASE
    run = uuid.uuid4().hex[:10]
    checks = []
    today = datetime.date.today().isoformat()
    def check(name, condition=True):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
    def api(method, path, body=None, token=None, content_type='application/json', reject=False):
        headers = {'Content-Type': content_type}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as error:
            payload = json.load(error)
        if reject:
            assert payload.get('code') != 0, 'Unexpected success: ' + path
            return payload
        assert payload.get('code') == 0, f'{method} {path}: {payload.get("message")}'
        return payload.get('data')
    def account(suffix, role=None, permissions=None):
        name = 'coop-qa-' + run + '-' + suffix
        password = secrets.token_urlsafe(24)
        hashed = subprocess.run(['/usr/sbin/htpasswd', '-niB', '-C', '4', name], input=password + '\n', text=True, capture_output=True, check=True).stdout.split(':', 1)[1].strip()
        uid = int(sql('select coalesce(max(user_id),0)+100 from sys_user;'))
        sql(f"insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions) values({uid},{quote(name)},{quote(name)},{quote(name)},{quote(name)},{quote('隔离验收 '+suffix)},{quote(hashed)},'active',{'true' if role is None else 'false'});")
        if role:
            sql(f"insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values({uid},{quote(role)},{quote(json.dumps(permissions))}::jsonb,true,'QA','QA');")
        session = api('POST', '/auth/login', {'username': name, 'password': password})
        return uid, session
    admin_id, session = account('admin')
    token = session['token']
    private_session = args.output / 'browser-session.json'
    private_session.write_text(json.dumps(session)); private_session.chmod(0o600)
    key = lambda: uuid.uuid4().hex
    def detail(kind, record):
        return api('GET', f'/partner-operations/{kind}/{record}', token=token)
    def create(kind, d, actor=token):
        return api('POST', '/partner-operations/' + kind, {**d, 'requestId': key()}, actor)
    def action(kind, record, verb, d=None, request=None, actor=token, reject=False):
        current = detail(kind, record)
        return api('POST', f'/partner-operations/{kind}/{record}/actions/{verb}', {**(d or {}), 'version': current['version'], 'requestId': request or key()}, actor, reject=reject)
    def upload(path, purpose='photo', content=None, name='SIMULATED-QA.png', actor=token, reject=False):
        body, mime = multipart_payload(name, content or png(), {'purpose': purpose})
        return api('POST', path, body, actor, mime, reject)
    legacy = {}
    for table, identity in [('plm_product', 'product_id'), ('plm_inventory', 'inventory_id'), ('plm_attachment', 'attachment_id'), ('plm_partner_record', 'record_id'), ('plm_partner_spec', 'spec_id'), ('plm_supplier_profile', 'supplier_id')]:
        ceiling = int(sql(f'select coalesce(max({identity}),0) from {table};'))
        statement = f"select count(*)::text||'|'||md5(coalesce(string_agg(to_jsonb(t)::text,'' order by {identity}),'')) from {table} t where {identity}<={ceiling};"
        legacy[table] = {'sql': statement, 'before': sql(statement)}
    old_counts = sql("select (select count(*) from plm_inventory),(select count(*) from plm_attachment where created_by<>'QA');")
    supplier = api('POST', '/inventories/suppliers', {'supplierName': 'QA 供方 ' + run, 'supplierType': 'company', 'status': 'active', 'supplyCategories': ['模具', '合作产品'], 'requestId': key()}, token)
    code = supplier['supplierCode']
    p = api('POST', '/partner-products', {'name': 'QA 透明合作产品 '+run, 'notes': '隔离验收，非真实商品与通过事实', 'requestId': key(), 'specs': [{'name': '0185 透明', 'websiteSku': 'QA-SKU-'+run, 'sources': [{'supplierCode': code}]}]}, token)
    pid, spec = p['id'], p['specs'][0]['id']
    check('new source keeps unknown reference price/currency without zero or CNY', 'priceCents' not in p['specs'][0]['sources'][0] and p['specs'][0]['sources'][0]['currency'] == '')
    q = create('supplier_quote', {'name': 'QA 原报价 '+run, 'supplierCode': code, 'specId': spec, 'specDescription': '原款式与0185透明规格', 'date': today, 'terms': '每次实际约定，不填未定金额'})
    check('supplier quotation unknown amounts remain unknown', 'unitPriceCents' not in q and q['currency'] == '')
    quote_file = upload(f'/partner-operations/supplier_quote/{q["id"]}/files', 'material')
    q = action('supplier_quote', q['id'], 'confirm', {'refs': [{'id': quote_file['attachmentId']}]})
    action('supplier_quote', q['id'], 'confirm', reject=True)
    check('confirmed supplier quote is immutable and original is attached', q['status'] == 'recorded' and len(q['refs']) == 1)
    fee = create('sample_fee', {'name': 'QA 样品费用 '+run, 'parentId': q['id'], 'currency': 'MXN', 'amount': '100', 'terms': '本单抵大货，也可直接退款，实际办理分别记录'})
    charged = {'amount': '100', 'date': today, 'reason': 'QA实际费用', 'evidence': 'QA原收费凭据', 'payer': '按本单甲方', 'payee': '按本单乙方', 'executor': '本单实际经办'}
    fee = action('sample_fee', fee['id'], 'charge', charged)
    request = key(); offset = {**charged, 'amount': '60', 'bulkReference': '本单大货结算原件'}
    fee = action('sample_fee', fee['id'], 'offset', offset, request); count = len(fee['events'])
    fee = action('sample_fee', fee['id'], 'offset', offset, request)
    check('repeated fee request is idempotent', len(fee['events']) == count)
    action('sample_fee', fee['id'], 'refund', {**charged, 'amount': '41'}, reject=True)
    fee = action('sample_fee', fee['id'], 'refund', {**charged, 'amount': '40'})
    check('offset and direct refund never consume the same balance twice', fee['balances']['remainingCents'] == 0 and fee['balances']['offsetCents'] == 6000)
    concurrent = create('sample_fee', {'name': 'QA并发费用 '+run, 'parentId': q['id'], 'currency': 'USD', 'terms': '本单实际约定'})
    concurrent = action('sample_fee', concurrent['id'], 'charge', {**charged, 'amount': '10'})
    bodies = [{**charged, 'amount': '7', 'version': concurrent['version'], 'requestId': key()} for _ in range(2)]
    def racing(body):
        try:
            api('POST', f'/partner-operations/sample_fee/{concurrent["id"]}/actions/refund', body, token)
            return True
        except AssertionError:
            return False
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(racing, bodies))
    check('concurrent old-version refunds allow one writer and keep a nonnegative balance', sum(outcomes) == 1 and detail('sample_fee', concurrent['id'])['balances']['remainingCents'] == 300)
    before_count = sql('select count(*) from plm_partner_record;')
    sql("create function qa_coop_required_audit_stop() returns trigger language plpgsql as $$ begin if NEW.action='PARTNER_OPERATION_CREATE' then raise exception 'QA simulated required audit failure'; end if; return NEW; end $$; create trigger qa_coop_required_audit_stop before insert on plm_operation_log for each row execute function qa_coop_required_audit_stop();")
    try:
        api('POST', '/partner-operations/sample_fee', {'name': 'QA应全部回滚', 'parentId': q['id'], 'terms': 'QA约定', 'requestId': key()}, token, reject=True)
    finally:
        sql('drop trigger qa_coop_required_audit_stop on plm_operation_log; drop function qa_coop_required_audit_stop();')
    check('required audit failure rolls back the complete new business record', sql('select count(*) from plm_partner_record;') == before_count)
    sample = api('POST', '/samples', {'requestId': key(), 'sourceType': 'partner', 'specId': spec, 'supplierCode': code, 'qty': 3, 'location': 'QA公司样品柜', 'ownerName': '实际保管岗位', 'date': today}, token)
    before_balance = sample['balances']
    qc = create('inspection', {'name': 'QA样品质检 '+run, 'parentId': sample['id'], 'factory': 'QA工厂'})
    upload(f'/partner-operations/inspection/{qc["id"]}/files', 'factory_signature', reject=True)
    qc = action('inspection', qc['id'], 'confirm', {'factory': 'QA工厂', 'date': today, 'checks': 'QA模拟检查结果', 'result': 'pass', 'onsite': True, 'scope': [{'specId': spec, 'checkedQty': 1}], 'refs': []})
    signature = {'basisHash': qc['basisHash'], 'reviewed': True, 'representativeName': 'QA模拟工厂代表', 'representativePosition': 'QA岗位'}
    upload(f'/partner-operations/inspection/{qc["id"]}/files', 'factory_signature', png(False), reject=True)
    action('inspection', qc['id'], 'sign', signature, reject=True)
    photo = upload(f'/partner-operations/inspection/{qc["id"]}/files')
    action('inspection', qc['id'], 'sign', {**signature, 'signatureFileId': photo['attachmentId']}, reject=True)
    signfile = upload(f'/partner-operations/inspection/{qc["id"]}/files', 'factory_signature')
    action('inspection', qc['id'], 'sign', {**signature, 'signatureFileId': signfile['attachmentId'], 'basisHash': 'wrong-version'}, reject=True)
    qc = action('inspection', qc['id'], 'sign', {**signature, 'signatureFileId': signfile['attachmentId']})
    qc = action('inspection', qc['id'], 'reference', {'location': 'QA公司样品柜', 'custodian': 'QA原实际保管人', 'date': today, 'reason': '只用于本次0185规格的后续对照'})
    check('quality/signature/reference do not increase physical sample quantity', api('GET', f'/samples/{sample["id"]}', token=token)['balances'] == before_balance)
    action('inspection', qc['id'], 'confirm', {'result': 'fail'}, reject=True)
    check('signed result cannot be overwritten and signature is version-bound', qc['status'] == 'signed' and qc['factorySignature']['basisHash'] == qc['basisHash'])
    upload(f'/partner-products/{pid}/files')
    p = api('GET', f'/partner-products/{pid}', token=token)
    listing_body = {'version': p['version'], 'requestId': key(), 'supplierCode': code, 'specIds': [spec]}
    listing = api('POST', f'/partner-operations/products/{pid}/listing-confirmations', listing_body, token)
    again = api('POST', f'/partner-operations/products/{pid}/listing-confirmations', {**listing_body, 'requestId': key()}, token)
    check('unchanged one-time listing confirmation is reused and not marked externally listed', listing['id'] == again['id'] and listing['status'] == 'not_connected' and api('GET', f'/partner-products/{pid}', token=token)['listing'] == 'unrecorded')
    batch = create('fulfilment', {'name': 'QA 委外批次 '+run, 'supplierCode': code, 'factory': 'QA实际工厂', 'sourceChannel': 'WhatsApp已确认需求', 'sourceReference': 'QA原确认依据（不是新客户订单）', 'lines': [{'specId': spec, 'qty': 10}]})
    action('fulfilment', batch['id'], 'dispatch', {}, reject=True)
    batch = action('fulfilment', batch['id'], 'accept', {'date': today, 'expectedFactoryDate': today, 'reason': '本单工厂确认', 'evidence': '原工厂确认依据'})
    bulk = create('inspection', {'name': 'QA本批大货检验 '+run, 'parentId': batch['id']})
    bulk = action('inspection', bulk['id'], 'confirm', {'factory': 'QA实际工厂', 'date': today, 'onsite': True, 'checks': 'QA本批现场抽检2件', 'qualityEvidence': '原对照样品 '+str(sample['id']), 'result': 'pass', 'scope': [{'specId': spec, 'checkedQty': 2, 'releaseQty': 10}], 'refs': []})
    signfile = upload(f'/partner-operations/inspection/{bulk["id"]}/files', 'factory_signature')
    bulk = action('inspection', bulk['id'], 'sign', {**signature, 'basisHash': bulk['basisHash'], 'signatureFileId': signfile['attachmentId']})
    shipment = {'date': today, 'tracking': 'QA原运单', 'destination': '本单实际收货点', 'evidence': 'QA装箱和工厂出货原件', 'allocations': [{'inspectionId': bulk['id'], 'specId': spec, 'qty': 6}]}
    batch = action('fulfilment', batch['id'], 'dispatch', shipment)
    action('fulfilment', batch['id'], 'dispatch', shipment, reject=True)
    remaining = create('inspection', {'name': 'QA余量复检 '+run, 'parentId': batch['id'], 'previousId': bulk['id']})
    remaining = action('inspection', remaining['id'], 'confirm', {'factory': 'QA实际工厂', 'date': today, 'onsite': True, 'checks': 'QA余下4件重新检查，未覆盖已发6件', 'qualityEvidence': '原对照样品 '+str(sample['id']), 'result': 'pass', 'scope': [{'specId': spec, 'coveredQty': 4, 'checkedQty': 2, 'releaseQty': 4}], 'refs': []})
    old_allocation = {**shipment, 'allocations': [{'inspectionId': bulk['id'], 'specId': spec, 'qty': 4}]}
    action('fulfilment', batch['id'], 'dispatch', old_allocation, reject=True)
    signfile = upload(f'/partner-operations/inspection/{remaining["id"]}/files', 'factory_signature')
    remaining = action('inspection', remaining['id'], 'sign', {**signature, 'basisHash': remaining['basisHash'], 'signatureFileId': signfile['attachmentId']})
    check('reinspection covers only the remaining actual quantity and supersedes the old unshipped release', remaining['resultSnapshot']['scope'][0]['coveredQty'] == 4 and detail('inspection', bulk['id'])['factorySignature']['basisHash'] == bulk['basisHash'])
    request = key(); shipment['allocations'][0] = {'inspectionId': remaining['id'], 'specId': spec, 'qty': 4}
    batch = action('fulfilment', batch['id'], 'dispatch', shipment, request); count = len(batch['events'])
    batch = action('fulfilment', batch['id'], 'dispatch', shipment, request)
    check('partial dispatch and retry never reuse the release', len(batch['events']) == count and batch['status'] == 'shipping')
    closedata = {'date': today, 'reason': 'QA结案', 'deliveryResolved': True, 'qualityResolved': True, 'expensesResolved': True}
    action('fulfilment', batch['id'], 'close', closedata, reject=True)
    for e in [e for e in batch['events'] if e['action'] == 'dispatch']:
        quantity = sum(a['qty'] for a in e['data']['allocations'])
        batch = action('fulfilment', batch['id'], 'receipt', {'date': today, 'dispatchId': e['id'], 'specId': spec, 'qty': quantity, 'shortage': 0, 'evidence': 'QA海外实际收货依据'})
    batch = action('fulfilment', batch['id'], 'close', closedata)
    check('dispatch never substitutes actual receipt before closure', batch['status'] == 'closed')
    eng_id, eng = account('engineering', 'domestic', ['partner:view', 'partner:write', 'collaboration:view', 'file:read', 'documents:write', 'supplier:view', 'samples:write'])
    sql(f"insert into plm_collaboration_assignment(field_group,user_id) values('engineering',{eng_id});")
    caps = api('GET', '/partner-operations/capabilities', token=eng['token'])
    api('GET', '/partner-operations/supplier_quote', token=eng['token'])
    check('engineering can record costs without customer finance authority', caps['engineering'] and caps['canReadCost'] and not caps['listing'])
    engquote = create('supplier_quote', {'name': 'QA工程维护真实报价 '+run, 'supplierCode': code, 'specDescription': '本次实际规格', 'unitPrice': '1.23', 'currency': 'MXN', 'date': today}, eng['token'])
    recorded_price = engquote['unitPriceCents'] == 123 and engquote['currency'] == 'MXN'
    cleared = action('supplier_quote', engquote['id'], 'save_draft', {'unitPrice': '', 'currency': '', 'date': ''}, actor=eng['token'])
    check('engineering supplier price survives scoped authorization and clearing a draft preserves unknown rather than the obsolete price', recorded_price and 'unitPriceCents' not in cleared and cleared['currency'] == '' and cleared['date'] == '' and cleared['status'] == 'draft')
    sample = api('GET', f'/samples/{sample["id"]}', token=token)
    sample = api('POST', f'/samples/{sample["id"]}/actions/custody', {'version': sample['version'], 'requestId': key(), 'date': today, 'reason': '按实际记录，不固定份数', 'custody': [{'location': 'QA公司样品柜', 'custodian': '实际保管岗位甲', 'qty': 1}, {'location': 'QA公司样品柜', 'custodian': '实际保管岗位乙', 'qty': 2}]}, eng['token'])
    check('multiple actual custodians share the existing sample ledger without duplicate receipt', sample['balances'] == before_balance and len(sample['custody']) == 2)
    _, sales = account('overseas-sales', 'overseas_sales', ['partner:view', 'partner:listing', 'partner:delivery', 'file:read', 'documents:write', 'pilot:tasks'])
    api('GET', f'/partner-operations/supplier_quote/{q["id"]}', token=sales['token'], reject=True)
    api('GET', f'/attachments/{quote_file["attachmentId"]}/preview', token=sales['token'], reject=True)
    api('POST', '/partner-operations/inspection', {'name': '不应新建', 'parentId': sample['id'], 'requestId': key()}, sales['token'], reject=True)
    check('overseas sales cannot read supplier cost files or confirm engineering inspection')
    p = api('GET', f'/partner-products/{pid}', token=token)
    p['notes'] += ' 文案变化，不涉及质量依据'; p['requestId'] = key()
    p = api('PUT', f'/partner-products/{pid}', p, token)
    actualsales = api('POST', f'/partner-operations/products/{pid}/listing-confirmations', {'version': p['version'], 'requestId': key(), 'supplierCode': code, 'specIds': [spec]}, sales['token'])
    check('real overseas-sales role can save a new listing confirmation without partner-write or repeated QC', actualsales['status'] == 'not_connected' and actualsales['id'] != listing['id'])
    api('GET', '/partner-operations/tasks', reject=True)
    check('login is mandatory even when factory has no account')
    current_product = api('GET', f'/partner-products/{pid}', token=token)
    api('PUT', f'/partner-products/{pid}', {**current_product, 'listing': 'listed', 'requestId': key()}, token, reject=True)
    check('ordinary product editing cannot manually bypass quality and external listing confirmation', api('GET', f'/partner-products/{pid}', token=token)['listing'] == 'unrecorded')
    legacy_id = int(sql(f"insert into plm_partner_record(kind,code,name,status,data,created_by,updated_by,owner_user_id) values('sale',{quote('QA-XS-'+run)},{quote('QA历史未发记录 '+run)},'confirmed','{{\"confirmed\":true,\"currency\":\"MXN\",\"lines\":[]}}'::jsonb,'QA','QA',{admin_id}) returning record_id;"))
    api('POST', f'/partner-sales/{legacy_id}/actions/ship', {'version': 0, 'requestId': key(), 'qty': 1, 'date': today}, token, reject=True)
    check('legacy unshipped orders cannot bypass new batch quality/signature through the old ship endpoint', sql(f'select count(*) from plm_partner_event where record_id={legacy_id};') == '0')
    pending_qc = create('inspection', {'name': 'QA手机现场签字验收 '+run, 'parentId': sample['id'], 'previousId': qc['id']})
    issue_photo = upload(f'/partner-operations/inspection/{pending_qc["id"]}/files')
    pending_qc = action('inspection', pending_qc['id'], 'confirm', {'factory': 'QA工厂', 'date': today, 'onsite': True, 'checks': 'QA模拟复检内容，未涉及未列规格', 'result': 'fail', 'issues': 'QA边框问题，需工厂整改，本次不通过', 'rectification': '保留原通过记录及原签名，另记录本次问题', 'scope': [{'specId': spec, 'checkedQty': 1}], 'refs': [{'id': issue_photo['attachmentId']}]})
    api('POST', f'/partner-operations/products/{pid}/listing-confirmations', {'version': p['version'], 'requestId': key(), 'supplierCode': code, 'specIds': [spec]}, sales['token'], reject=True)
    check('a later failed/reinspection never falls back to an older passed sample for listing')
    draft_qc = create('inspection', {'name': 'QA待填写样品质检 '+run, 'parentId': sample['id']})
    draft_qc = action('inspection', draft_qc['id'], 'save_draft', {'checks': 'QA只填写了一半，待继续检验', 'scope': [], 'date': today, 'refs': []})
    check('incomplete quality content persists as a draft without a pass or factory signature', draft_qc['status'] == 'draft' and draft_qc['draftResult']['checks'] == 'QA只填写了一半，待继续检验' and 'resultSnapshot' not in draft_qc and 'factorySignature' not in draft_qc)
    for snapshot in legacy.values():
        snapshot['after'] = sql(snapshot['sql'])
    check('all pre-existing product, inventory, attachment, partner, specification and supplier rows remain byte-for-byte equivalent as JSONB', all(s['before'] == s['after'] for s in legacy.values()))
    (args.output / 'legacy-row-fingerprints.json').write_text(json.dumps({table: {'before': s['before'], 'after': s['after']} for table, s in legacy.items()}, indent=2))
    fixture = {'productId': pid, 'specId': spec, 'supplierCode': code, 'sampleId': sample['id'], 'inspectionId': qc['id'], 'pendingInspectionId': pending_qc['id'], 'draftInspectionId': draft_qc['id'], 'bulkInspectionId': bulk['id'], 'fulfilmentId': batch['id'], 'quoteId': q['id'], 'feeId': fee['id'], 'listingId': listing['id']}
    (args.output / 'fixtures.json').write_text(json.dumps(fixture, ensure_ascii=False, indent=2))
    (args.output / 'report.json').write_text(json.dumps({'checks': checks, 'count': len(checks), 'database': DATABASE, 'oldCounts': old_counts, 'noExternalWrites': True}, ensure_ascii=False, indent=2))
    print(json.dumps({'checksPassed': len(checks), 'database': DATABASE, 'noExternalWrites': True}, ensure_ascii=False))


if __name__ == '__main__':
    main()
