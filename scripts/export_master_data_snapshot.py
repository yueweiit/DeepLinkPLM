#!/usr/bin/env python3
"""Read-only CRM API + MES SQL snapshot over an existing SSH connection.

No credentials are accepted in argv or written into the snapshot. This script
does not log in, create users, trigger synchronisation, or call mutation APIs.
The remote Python snippets run in the existing MES container without saving
files there. All MES reads share a REPEATABLE READ, READ ONLY transaction.
"""
import argparse
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid
if __package__:
    from .source_ssh import command as ssh_command, environment as ssh_environment
else:
    from source_ssh import command as ssh_command, environment as ssh_environment


CRM_READ_PATHS = frozenset({
    '/api/external/product/series/list', '/api/external/product/sku/list',
    '/api/external/product/brand/list', '/api/external/product/model/list',
    '/api/external/product/color/list',
})
CRM_REMOTE = r'''
import asyncio,json,sys
from decimal import Decimal
import httpx
from app.config import get_settings
s=get_settings()
PATHS={
 'series':('POST','/api/external/product/series/list'),
 'skus':('POST','/api/external/product/sku/list'),
 'brands':('GET','/api/external/product/brand/list'),
 'phones':('POST','/api/external/product/model/list'),
 'colors':('POST','/api/external/product/color/list'),
}
def unwrap(v):
 if not isinstance(v,dict):raise ValueError('response_shape')
 if v.get('code')==100200:
  v=v.get('data')
 if not isinstance(v,dict) or type(v.get('code')) is not int or v['code']!=200:
  raise ValueError('business_code')
 return v.get('data')
async def read_pages(c,name,method,path):
 rows=[];seen=set();total=None;page=1
 while True:
  body=None if method=='GET' else ({'page':page,'pageSize':500} if name in {'series','skus','colors'} else {})
  r=await c.request(method,s.crm_base_url.rstrip('/')+path,headers={'X-API-Key':s.crm_api_key},json=body)
  if r.status_code!=200:raise ValueError('http_status')
  v=unwrap(r.json(parse_float=Decimal))
  if isinstance(v,list):items=v;n=len(v)
  elif isinstance(v,dict):
   items=next((v[k] for k in ('list','records','items') if isinstance(v.get(k),list)),None);n=v.get('total')
  else:raise ValueError('page_shape')
  if not isinstance(items,list) or type(n) is not int or n<0:raise ValueError('page_shape')
  if total is not None and total!=n:raise ValueError('total_changed')
  total=n
  for item in items:
   key=str(item.get('id',''))
   if not key or key in seen:raise ValueError('duplicate_source_id')
   seen.add(key);rows.append(item)
  if len(rows)==total:break
  if not items or len(rows)>total or page>=10000:raise ValueError('incomplete_page')
  page+=1
 return rows,page
def canonical(rows):return {str(v['id']):json.dumps(v,ensure_ascii=False,sort_keys=True,default=str) for v in rows}
async def main():
 if not s.crm_base_url or not s.crm_api_key:raise ValueError('crm_read_config_missing')
 result={};proof=[]
 async with httpx.AsyncClient(timeout=httpx.Timeout(15,connect=2),follow_redirects=False) as c:
  for name,(method,path) in PATHS.items():
   rows,page=await read_pages(c,name,method,path)
   result[name]=rows;proof.append({'path':path,'method':method,'pages':page,'total':len(rows),'http':200})
  # Full identity AND value comparison catches same-count replacement/edits too.
  for name,(method,path) in PATHS.items():
   checked,_=await read_pages(c,name,method,path)
   if canonical(result[name])!=canonical(checked):raise ValueError('source_changed')
 result['api_proof']=proof
 print(json.dumps(result,ensure_ascii=False,default=str))
try:asyncio.run(main())
except Exception as e:
 print('CRM read snapshot failed: '+type(e).__name__,file=sys.stderr);sys.exit(1)
'''

MES_REMOTE = r'''
import asyncio,json,sys
from datetime import date,datetime
from decimal import Decimal
import sqlalchemy as sa
from app.database import engine
from app.services.material_unit_policy import effective_material_unit
codes=json.loads(sys.argv[1])
def encode(v):
 if isinstance(v,Decimal):return str(v)
 if isinstance(v,(date,datetime)):return v.isoformat()
 raise TypeError(type(v).__name__)
async def main():
 out={}
 async with engine.connect() as c:
  tx=await c.begin()
  try:
   await c.execute(sa.text('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY'))
   await c.execute(sa.text("SET LOCAL statement_timeout='15s'"))
   async def rows(sql,params=None):
    value=(await c.execute(sa.text(sql),params or {})).mappings().all()
    return [dict(x) for x in value]
   params={'codes':codes}
   out['products']=await rows('select * from mes_product where product_code=any(:codes) order by product_code',params)
   out['boms']=await rows("""select b.* from bom_main b where b.is_active and
    (b.parent_code=any(:codes) or exists(select 1 from mes_product_bom_binding r
      where r.bom_code=b.bom_code and r.product_code=any(:codes) and r.status='enabled'))
    order by b.bom_code""",params)
   bp={'boms':[b['bom_code'] for b in out['boms']]}
   out['bom_items']=await rows('select * from bom_child where bom_code=any(:boms) order by bom_code,line_no,id',bp)
   out['bom_bindings']=await rows("select * from mes_product_bom_binding where bom_code=any(:boms) and status='enabled' order by id",bp)
   out['bom_process_mappings']=await rows('select * from mes_bom_process_mapping where bom_code=any(:boms) order by id',bp)
   out['route_bindings']=await rows("select * from product_route_binding where product_code=any(:codes) and status='enabled' order by id",params)
   rp={'routes':sorted({r['route_code'] for r in out['route_bindings']})}
   out['routes']=await rows('select * from process_flows where route_code=any(:routes) order by route_code',rp)
   out['nodes']=await rows('select * from process_flow_steps where route_code=any(:routes) order by route_code,step_no,id',rp)
   out['edges']=await rows('select * from process_flow_step_edges where route_code=any(:routes) order by route_code,id',rp)
   op={'operations':sorted({n['operation_code'] for n in out['nodes']})}
   out['operations']=await rows('select * from operation_templates where operation_code=any(:operations) order by operation_code',op)
   mp={'materials':sorted({r['child_code_normalized'] or r['child_code'] for r in out['bom_items']})}
   out['materials']=await rows('select * from material_items where material_code=any(:materials) order by material_code',mp)
   out['unit_projections']=await rows("select record_key,external_values->'quantity_unit_projection' as projection from mes_master_data_override where table_name='material_items' and record_key=any(:materials) and external_values ? 'quantity_unit_projection' order by record_key",mp)
   out['effective_units']={m['material_code']:effective_material_unit(m['material_code'],m['unit']) for m in out['materials']}
   out['item_codes']=await rows('select * from mes_product_item_code where product_code=any(:codes) or base_code=any(:materials) order by base_code',{**params,**mp})
   ip={'bases':[r['base_code'] for r in out['item_codes']]}
   out['item_colors']=await rows('select * from mes_product_item_color where base_code=any(:bases) order by base_code,color_code',ip)
   tables=['mes_product','mes_product_item_code','mes_product_item_color','material_items','bom_main','bom_child','mes_product_bom_binding','mes_bom_process_mapping','operation_templates','process_flows','process_flow_steps','process_flow_step_edges','product_route_binding']
   out['schema']=await rows("select table_name,column_name,udt_name,is_nullable,numeric_precision,numeric_scale,column_default from information_schema.columns where table_schema='public' and table_name=any(:tables) order by table_name,ordinal_position",{'tables':tables})
   out['constraints']=await rows("select t.relname as table_name,c.conname as name,pg_get_constraintdef(c.oid) as definition from pg_constraint c join pg_class t on t.oid=c.conrelid join pg_namespace n on n.oid=t.relnamespace where n.nspname='public' and t.relname=any(:tables) order by t.relname,c.conname",{'tables':tables})
   out['snapshot']=await rows('select now() as transaction_time,txid_current_snapshot()::text as transaction_snapshot')
  finally:await tx.rollback()
 print(json.dumps(out,ensure_ascii=False,default=encode))
 await engine.dispose()
try:asyncio.run(main())
except Exception as e:
 print('MES read snapshot failed: '+type(e).__name__,file=sys.stderr);sys.exit(1)
'''

SHOP_SQL = """SET SESSION MAX_EXECUTION_TIME=15000;
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
SELECT JSON_ARRAYAGG(s.series_phone_model_id)
 FROM series_phone_model s
 JOIN product_series p ON p.product_series_id=s.product_series_id AND p.status='ACTIVE'
 JOIN phone_model m ON m.phone_model_id=s.phone_model_id AND m.status='ACTIVE'
 JOIN phone_brand b ON b.phone_brand_id=m.phone_brand_id AND b.status='ACTIVE'
 WHERE s.status='ACTIVE' AND (COALESCE(p.is_combo,0)=0 OR EXISTS(
  SELECT 1 FROM product_combo c WHERE c.product_series_id=s.product_series_id
   AND c.combo_code=s.color_code AND c.status='ACTIVE'));
ROLLBACK;
"""


def external_data(value):
    if isinstance(value, dict) and value.get('code') == 100200:
        value = value.get('data')
    if not isinstance(value, dict) or type(value.get('code')) is not int or value['code'] != 200:
        raise ValueError('CRM response business code is not integer 200')
    return value.get('data')


def validate_crm(crm):
    skus = crm['skus']
    ids = [str(s.get('id', '')) for s in skus]
    if not skus or any(not i for i in ids) or len(set(ids)) != len(ids):
        raise ValueError('CRM source SKU identities are missing or repeated')
    organizations = {s.get('organizationId') for s in skus}
    if len(organizations) != 1 or None in organizations or '' in organizations:
        raise ValueError('Snapshot must contain one explicit CRM organization')
    organization = next(iter(organizations))
    for name in ('series', 'phones', 'brands'):
        if any(r.get('organizationId') != organization for r in crm[name]):
            raise ValueError('CRM organization mismatch')
        keys = [r.get('id') for r in crm[name]]
        if any(not k for k in keys) or len(keys) != len(set(keys)):
            raise ValueError('CRM master identities are missing or repeated')
    series = {r['id'] for r in crm['series']}
    phones = {r['id'] for r in crm['phones']}
    if any(s['productSeriesId'] not in series or s['phoneModelId'] not in phones for s in skus):
        raise ValueError('CRM SKU has a missing series or phone reference')
    return organization


def scope_snapshot(crm, mes, listed_ids):
    organization = validate_crm(crm)
    all_ids = {s['id'] for s in crm['skus']}
    if not set(listed_ids).issubset(all_ids):
        raise ValueError('Shop directory contains identities outside this CRM snapshot')
    phone_ids = {s['phoneModelId'] for s in crm['skus']}
    series_ids = {s['productSeriesId'] for s in crm['skus']}
    crm['phones'] = [p for p in crm['phones'] if p['id'] in phone_ids]
    crm['series'] = [s for s in crm['series'] if s['id'] in series_ids]
    brand_ids = {p['phoneBrandId'] for p in crm['phones']}
    crm['brands'] = [b for b in crm['brands'] if b['id'] in brand_ids]
    if brand_ids != {b['id'] for b in crm['brands']}:
        raise ValueError('CRM phone has a missing brand reference')
    return {'formatVersion': 1, 'capturedAt': datetime.now(timezone.utc).isoformat(),
            'organizationId': organization, 'complete': True, 'crm': crm, 'mes': mes,
            'shop': {'listedSkuIds': sorted(set(listed_ids)),
                     'rule': 'active SKU/series/model/brand and valid combo; source IDs remain CRM-owned'},
            'classification': {'purchasedSeriesCodes': ['NSF4030']}}


def ssh(control, host, command, payload, env=None, password_file=None):
    args = ssh_command(host, command, control, bool(password_file))
    value = subprocess.run(args, input=payload.encode(), stdout=subprocess.PIPE,
                           stderr=subprocess.PIPE, timeout=180, env=env)
    if value.returncode:
        # Never print arbitrary process output or configuration values.
        raise RuntimeError('Read-only source export failed; SSH/container status ' + str(value.returncode))
    if len(value.stdout) > 64 * 1024 * 1024:
        raise RuntimeError('Snapshot exceeds the allowed size')
    return json.loads(value.stdout, parse_float=Decimal)


def private_json(path, value):
    data = (json.dumps(value, ensure_ascii=False, indent=2, default=str) + '\n').encode()
    with path.open('xb') as stream:
        stream.write(data)
    path.chmod(0o600)
    return hashlib.sha256(data).hexdigest()


def export(output, host='yuewei@155.138.234.129', control=None, password_file=None):
    output = Path(output).expanduser().resolve()
    repo = Path(__file__).resolve().parents[1]
    if output == repo or repo in output.parents:
        raise ValueError('Private source snapshots must be outside the Git workspace')
    if output.exists():
        raise ValueError('Output must be a new immutable snapshot directory')
    os.umask(0o077)
    with ssh_environment(password_file) as env:
        crm = ssh(control, host, 'docker exec -i deeplink-mes-api-1 python -', CRM_REMOTE, env, password_file)
        validate_crm(crm)
        codes = sorted({s['seriesCode'] for s in crm['series'] if s.get('seriesCode')})
        command = 'docker exec -i deeplink-mes-api-1 python - ' + shlex.quote(json.dumps(codes))
        mes = ssh(control, host, command, MES_REMOTE, env, password_file)
        command = "docker exec -i shop-crm-mysql sh -c 'MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --default-character-set=utf8mb4 --batch --skip-column-names -u root --database=shop_ordering'"
        listed = ssh(control, host, command, SHOP_SQL, env, password_file)
    snapshot = scope_snapshot(crm, mes, listed or [])
    output.mkdir(parents=True, mode=0o700)
    digest = private_json(output / 'source.json', snapshot)
    counts = {system: {key: len(value) for key, value in snapshot[system].items() if isinstance(value, list)}
              for system in ('crm', 'mes')}
    duplicates = Counter(s.get('itemCode') for s in crm['skus'])
    manifest = {'formatVersion': 1, 'complete': True, 'capturedAt': snapshot['capturedAt'],
                'organizationId': snapshot['organizationId'], 'sourceFile': 'source.json', 'sha256': digest,
                'counts': counts, 'listedSkuCount': len(set(listed or [])),
                'unlistedSkuCount': len(crm['skus']) - len(set(listed or [])),
                'duplicateItemCodeGroups': sum(n > 1 for n in duplicates.values()),
                'remoteBusinessWrites': 0, 'mesTransport': 'ssh-readonly-repeatable-read',
                'crmTransport': 'existing-read-query-api', 'mesAuthenticatedHttpVerified': False}
    private_json(output / 'manifest.json', manifest)
    return {'snapshot': str(output), **manifest}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--ssh-control', type=Path)
    parser.add_argument('--ssh-host', default='yuewei@155.138.234.129')
    parser.add_argument('--password-file', type=Path, default=os.getenv('PLM_SOURCE_SSH_PASSWORD_FILE') or None)
    outputs = parser.add_mutually_exclusive_group(required=True)
    outputs.add_argument('--output', type=Path)
    outputs.add_argument('--output-root', type=Path)
    parser.add_argument('--interval-seconds', type=int, default=0)
    args = parser.parse_args()
    if args.interval_seconds < 0 or 0 < args.interval_seconds < 60:
        parser.error('Interval must be zero or at least 60 seconds')
    if args.interval_seconds and args.output:
        parser.error('Scheduled export requires --output-root')
    while True:
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        output = args.output or args.output_root / ('crm-mes-' + stamp + '-' + uuid.uuid4().hex[:8])
        try:
            result = export(output, args.ssh_host, args.ssh_control, args.password_file)
            print(json.dumps(result, ensure_ascii=False), flush=True)
        except (ValueError, RuntimeError, subprocess.TimeoutExpired, OSError) as error:
            if not args.interval_seconds:
                raise
            # Keep earlier immutable snapshots, and never log remote output or credentials.
            print('Source snapshot refresh failed: ' + type(error).__name__, file=sys.stderr, flush=True)
        if not args.interval_seconds:
            return
        time.sleep(args.interval_seconds)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, subprocess.TimeoutExpired, OSError) as error:
        print(type(error).__name__ + ': ' + str(error), file=sys.stderr)
        sys.exit(1)
