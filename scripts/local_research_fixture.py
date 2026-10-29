#!/usr/bin/env python3
"""Verify current new-product research against a disposable clone of the LOCAL PLM database.

No external integrations run and the source database is read only. --keep leaves the
isolated server available for browser QA; --cleanup removes that saved fixture.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import secrets
import signal
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlencode
import uuid

CONTAINER = 'yuewei-plm-postgres-local'
DB_PORT = 15432
CLONE_CONTAINER = 'plm-launch-unification-20261023'
CLONE_PORT = 15461
CLONE_TARGETS={(CLONE_CONTAINER,CLONE_PORT,18162),('plm-eight-step-20261023',15468,18178)}
ROOT = Path(__file__).resolve().parents[1]


def sql(database, statement):
    return subprocess.check_output(['docker', 'exec', '-i', CONTAINER, 'psql', '-X', '-U', 'postgres',
        '-d', database, '-qAt', '-v', 'ON_ERROR_STOP=1'], input=('set search_path=plm,public;\n' + statement).encode(),
        stderr=subprocess.PIPE).decode().strip()


def quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def multipart_payload(name, content, fields):
    boundary = 'Research' + uuid.uuid4().hex
    chunks = [f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode() for key, value in fields.items()]
    chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: image/png\r\n\r\n'.encode() + content + b'\r\n')
    chunks.append(f'--{boundary}--\r\n'.encode())
    return b''.join(chunks), 'multipart/form-data; boundary=' + boundary


def cleanup(output):
    global CONTAINER, DB_PORT
    runtime = json.loads((output / 'runtime.json').read_text())
    if not re.fullmatch(r'plm_research_verify_[0-9]+', runtime['database']):
        raise RuntimeError('Unexpected fixture database')
    container, port = runtime.get('dbContainer', 'yuewei-plm-postgres-local'), runtime.get('dbPort', 15432)
    if (container, port) not in {('yuewei-plm-postgres-local',15432),*((c,p) for c,p,_ in CLONE_TARGETS)}:
        raise RuntimeError('Unexpected fixture database container')
    CONTAINER, DB_PORT = container, port
    try:
        os.kill(runtime['pid'], signal.SIGTERM)
    except ProcessLookupError:
        pass
    for _ in range(30):
        try:
            os.kill(runtime['pid'], 0)
        except ProcessLookupError:
            break
        time.sleep(.1)
    sql('postgres', 'DROP DATABASE IF EXISTS ' + runtime['database'] + ' WITH (FORCE);')
    (output / 'browser-session.json').unlink(missing_ok=True)
    (output / 'browser-login.json').unlink(missing_ok=True)


def main(extra_checks):
    global CONTAINER, DB_PORT
    parser = argparse.ArgumentParser()
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--port', type=int, default=18086)
    parser.add_argument('--keep', action='store_true')
    parser.add_argument('--cleanup', action='store_true')
    parser.add_argument('--db-container')
    parser.add_argument('--db-port', type=int)
    parser.add_argument('--source-dump', type=Path)
    args = parser.parse_args()
    output = args.output.resolve()
    if args.cleanup:
        cleanup(output)
        print('Isolated research fixture removed')
        return
    if not args.jar or not args.jar.is_file():
        parser.error('--jar must point to the built backend')
    if any(value is not None for value in (args.db_container, args.db_port, args.source_dump)):
        if (args.db_container,args.db_port,args.port) not in CLONE_TARGETS or not args.source_dump or not args.source_dump.is_file() or args.source_dump.name != 'production-clone.dump':
            parser.error('Explicit clone mode requires --db-container plm-launch-unification-20261023 --db-port 15461 --source-dump /.../production-clone.dump together')
        CONTAINER, DB_PORT = args.db_container, args.db_port
    else:
        CONTAINER, DB_PORT = 'yuewei-plm-postgres-local', 15432
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    database = 'plm_research_verify_' + str(os.getpid())
    dump = args.source_dump.resolve() if args.source_dump else output / 'local-source.dump'
    if not args.source_dump:
        with dump.open('wb') as destination:
            subprocess.run(['docker', 'exec', CONTAINER, 'pg_dump', '-U', 'postgres', '-d', 'plm_data_management',
                '-Fc', '-n', 'plm'], stdout=destination, stderr=subprocess.PIPE, check=True)
    sql('postgres', 'CREATE DATABASE ' + database)
    process = None
    checks = []
    (output / "report.json").unlink(missing_ok=True)
    (output / "launch-fixtures.json").unlink(missing_ok=True)
    try:
        with dump.open('rb') as source:
            subprocess.run(['docker', 'exec', '-i', CONTAINER, 'pg_restore', '-U', 'postgres',
                '--no-owner', '--no-acl', '--exit-on-error', '-d', database], stdin=source, capture_output=True, check=True)
        info = json.loads(subprocess.check_output(['docker', 'inspect', CONTAINER]))[0]
        settings = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
        env = {**os.environ, 'DB_URL': f'jdbc:postgresql://127.0.0.1:{DB_PORT}/{database}?currentSchema=plm',
            'DB_USERNAME': 'postgres', 'DB_PASSWORD': settings['POSTGRES_PASSWORD'],
            'SERVER_PORT': str(args.port), 'SERVER_ADDRESS': '127.0.0.1', 'SPRING_PROFILES_ACTIVE': 'dev',
            'APP_SECURITY_ENABLED': 'true', 'APP_MOLD_APPLICATION_ENABLED': 'true',
            'APP_MOLD_APPLICATION_READ_SYNC_ENABLED': 'false', 'APP_MOLD_APPLICATION_WRITE_ENABLED': 'false',
            'APP_PHONE_WATCH_ENABLED': 'false', 'DINGTALK_INTEGRATION_ENABLED': 'false',
            'PLM_LOCAL_NO_OUTBOUND': 'true', 'PLM_PILOT_DELIVERY_ENABLED': 'false',
            'APP_STORAGE_LOCAL_ROOT': str(output / 'uploads')}
        for key in list(env):
            if key.startswith(('DINGTALK_', 'CRM_', 'MES_')):
                env.pop(key, None)
        env['DINGTALK_INTEGRATION_ENABLED'] = 'false'
        frozen_jar=output/'backend.jar'
        shutil.copy2(args.jar.resolve(),frozen_jar)
        with (output / 'server.log').open('w') as log:
            process = subprocess.Popen(['java', '-jar', str(frozen_jar),
                '--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration'],
                cwd=ROOT / 'plm-server', env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        base = f'http://127.0.0.1:{args.port}/api/v1'
        (output / 'runtime.json').write_text(json.dumps({'pid': process.pid, 'database': database, 'baseUrl': base, 'dbContainer': CONTAINER, 'dbPort': DB_PORT, 'sourceDump': str(dump)}))
        for _ in range(120):
            if process.poll() is not None:
                raise RuntimeError('Isolated backend did not start; inspect server.log')
            try:
                urllib.request.urlopen(base + '/health', timeout=1)
                break
            except Exception:
                time.sleep(.5)
        else:
            raise RuntimeError('Isolated backend startup timed out')

        def api(method, path, body=None, token=None, content_type='application/json', success=True):
            headers = {'Content-Type': content_type}
            if path.startswith('/product-research'):
                headers['X-PLM-Research-Model'] = 'eight_step'
            if token:
                headers['Authorization'] = 'Bearer ' + token
            data = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
            request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    payload = json.load(response)
            except urllib.error.HTTPError as failure:
                payload = json.load(failure)
            if success:
                if payload.get('code') != 0:
                    raise AssertionError(f'{method} {path}: {payload.get("message")}')
                return payload.get('data')
            if payload.get('code') == 0:
                raise AssertionError('Unexpected success: ' + method + ' ' + path)
            return payload

        def check(name, condition):
            if not condition:
                (output / 'failure-check.json').write_text(json.dumps({'failed':name,'passed':checks},ensure_ascii=False,indent=2))
                raise AssertionError(name)
            checks.append(name)
            (output / 'checks-progress.json').write_text(json.dumps({'checks':checks,'count':len(checks)},ensure_ascii=False,indent=2))

        def account(name, role=None, permissions=None, all_objects=False):
            password = secrets.token_urlsafe(24)
            result = subprocess.run(['/usr/sbin/htpasswd', '-niB', '-C', '4', name], input=password + '\n', text=True, capture_output=True, check=True)
            hashed = result.stdout.split(':', 1)[1].strip()
            uid = int(sql(database, 'select coalesce(max(user_id),0)+100 from sys_user;'))
            sql(database, f"""insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions)
                values({uid},{quote(name)},{quote(name)},{quote(name)},{quote(name)},{quote(name)},{quote(hashed)},'active',{'true' if role is None else 'false'});""")
            if role:
                sql(database, f"insert into plm_pilot_account(user_id,role_code,permissions,all_objects,created_by,updated_by) values({uid},{quote(role)},{quote(json.dumps(permissions))}::jsonb,{'true' if all_objects else 'false'},'fixture','fixture');")
            session = api('POST', '/auth/login', {'username': name, 'password': password})
            if name == 'research-qa-admin':
                private_login=output/'browser-login.json'
                private_login.write_text(json.dumps({'username':name,'password':password}))
                private_login.chmod(0o600)
            return uid, session

        owner_id, session = account('research-qa-admin')
        token = session['token']
        browser_session = output / 'browser-session.json'
        browser_session.write_text(json.dumps(session))
        browser_session.chmod(0o600)
        old_id = int(sql(database, "select product_id from plm_product where product_type='product_line' and development_mode is null and deleted_flag=0 order by product_id limit 1;"))
        original_timeline = api('GET', f'/projects/{old_id}/timeline', token=token)
        legacy = api('POST', '/products', {'productName':'原立项入口兼容验收','productType':'product_line','versionNo':'V1','createdBy':'fixture'}, token)
        check('the original initiation entry retains its original workflow', len(api('GET',f'/projects/{legacy["productId"]}/timeline',token=token)['nodes']) == len(original_timeline['nodes']))
        def upload(path, name, content, fields):
            body, content_type = multipart_payload(name, content, fields)
            return api('POST', path, body, token, content_type)

        limited_id,limited=account('research-qa-limited','domestic',['project:view','product:view','documents:write','documents:publish','file:read'],False)
        limited_token=limited['token']
        def detail(pid):return api('GET',f'/product-research/{pid}',token=token)
        def act(action,data,pid,request=None):return api('POST',f'/product-research/{pid}/workflow',{'action':action,'version':detail(pid)['workflow']['version'],'requestId':request or uuid.uuid4().hex,'data':data},token)
        def reject_action(action,data,pid):
            before=detail(pid)
            result=api('POST',f'/product-research/{pid}/workflow',{'action':action,'version':before['workflow']['version'],'data':data},token,success=False)
            check('wrong stage action is transactional '+action,'当前不在本操作所属阶段' in result.get('message','') and detail(pid)==before)
        image=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==')
        api('POST','/products',{'developmentMode':'lightweight','productName':'不应创建的旧流程','productType':'product_line','versionNo':'V1','createdBy':'fixture'},token,success=False)
        api('GET','/product-research?flow=lightweight',token=token,success=False)
        retired=int(sql(database,"select count(*) from plm_product where development_mode='lightweight' and deleted_flag=0"))
        check('obsolete flow projects are retired and cannot be created or listed',retired==0)
        _,supplier=account('research-qa-supplier','supplier',['project:view','documents:write'],False)
        api('GET','/product-research',token=supplier['token'],success=False)
        check('external suppliers cannot enter new product research',True)
        extra_checks(locals())
        check('original model workflow remains unchanged',api('GET',f'/projects/{old_id}/timeline',token=token)==original_timeline)
        (output/'report.json').write_text(json.dumps({'checks':checks,'count':len(checks),'historicalProductId':old_id,'isolatedDatabase':database,'backend':base},ensure_ascii=False,indent=2))
        print(json.dumps({'passed':len(checks),'report':str(output/'report.json')},ensure_ascii=False))
    finally:
        if process is not None and not args.keep:cleanup(output)
        elif process is None:sql('postgres','DROP DATABASE IF EXISTS '+database+' WITH (FORCE);')
