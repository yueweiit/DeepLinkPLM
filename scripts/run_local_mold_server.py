#!/usr/bin/env python3
"""Run against the LOCAL Docker database only; secrets never appear in argv or Git."""
import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import re

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--port',type=int,default=8081)
    p.add_argument('--jar',type=Path,help='Optional isolated Maven build artifact')
    p.add_argument('--import-backup',type=Path)
    p.add_argument('--write-enabled',action='store_true',help='Requires an explicitly confirmed submitter ID')
    p.add_argument('--submitter-id',type=int)
    p.add_argument('--database',default='plm_data_management',help='Existing LOCAL database; never a remote URL')
    p.add_argument('--db-container',default='yuewei-plm-postgres-local',help='Local Docker container; isolated cooperation QA is also supported')
    p.add_argument('--db-port',type=int,default=15432,help='Loopback-only local PostgreSQL port')
    p.add_argument('--master-data-root',type=Path,help='Private immutable CRM/MES snapshot parent')
    p.add_argument('--storage-root',type=Path,help='Optional isolated local attachment directory')
    p.add_argument('--offline',action='store_true',help='Disable all remote integrations, writes and automatic read-sync')
    p.add_argument('--phone-watch-local',action='store_true',help='Keep the existing loopback-only phone service, requiring its local-only and no-DingTalk-write guards')
    args=p.parse_args()
    if args.write_enabled and not args.submitter_id:p.error('An explicitly confirmed submitter is required')
    if not re.fullmatch(r'plm_[a-z0-9_]+',args.database):p.error('Only an explicit local PLM database name is accepted')
    if args.offline and args.write_enabled:p.error('Offline acceptance cannot enable DingTalk writes')
    if args.db_container!='yuewei-plm-postgres-local':
        if not (args.offline and args.db_container=='plm-cooperation-qa-20261019' and args.db_port==15439 and args.database=='plm_cooperation_qa'):
            p.error('Alternate database must be the explicit isolated, offline cooperation acceptance container')
    elif args.db_port!=15432:p.error('The shared local database port cannot be changed here')
    info=json.loads(subprocess.check_output(['docker','inspect',args.db_container]))[0]
    settings=dict(item.split('=',1) for item in info['Config']['Env'] if '=' in item)
    env={**os.environ,'SERVER_PORT':str(args.port),'SERVER_ADDRESS':'127.0.0.1','DB_URL':f'jdbc:postgresql://127.0.0.1:{args.db_port}/{args.database}?currentSchema=plm',
        'DB_USERNAME':'postgres','DB_PASSWORD':settings['POSTGRES_PASSWORD'],'SPRING_PROFILES_ACTIVE':'dev',
        'APP_STORAGE_LOCAL_ROOT':str(args.storage_root.resolve()) if args.storage_root else str(ROOT/'plm-server/data/uploads'),'APP_MOLD_APPLICATION_ENABLED':'true',
        'APP_MOLD_APPLICATION_READ_SYNC_ENABLED':str(not args.offline).lower(),'APP_MOLD_APPLICATION_WRITE_ENABLED':str(args.write_enabled).lower(),
        'APP_MOLD_APPLICATION_CENTRAL_CODE_ENABLED':'false','APP_MOLD_APPLICATION_ALLOWED_SUBMITTERS':str(args.submitter_id or ''),
        'APP_MOLD_APPLICATION_IMPORT_DIRECTORY':str(args.import_backup.resolve()) if args.import_backup else '',
        'DINGTALK_INTEGRATION_ENABLED':'false','APP_SECURITY_ENABLED':'true',
        'PLM_PILOT_DELIVERY_ENABLED':'false','PLM_LOCAL_NO_OUTBOUND':str(args.offline).lower(),
        'APP_MASTER_DATA_ENABLED':str(bool(args.master_data_root)).lower(),
        'APP_MASTER_DATA_SNAPSHOT_ROOT':str(args.master_data_root.resolve()) if args.master_data_root else ''}
    if args.offline:
        # Do not inherit credentials or endpoints from an unrelated terminal session.
        for key in list(env):
            if key.startswith('DINGTALK_') or key.startswith('MES_') or key.startswith('CRM_'):env.pop(key,None)
        env.update(DINGTALK_INTEGRATION_ENABLED='false',APP_PHONE_WATCH_ENABLED='false')
    # Disable legacy automatic DingTalk mutation; the new controlled submitter has its own write gate.
    config=Path.home()/'.agents/config/dingtalk.json'
    if config.exists() and not args.offline:
        ding=json.loads(config.read_text())
        for key,target in [('appKey','DINGTALK_APP_KEY'),('appSecret','DINGTALK_APP_SECRET'),('agentId','DINGTALK_AGENT_ID'),('corpId','DINGTALK_CORP_ID')]:
            if ding.get(key):env[target]=str(ding[key])
    service='YueweiPLM-mold-connector-local'
    found=subprocess.run(['security','find-generic-password','-s',service,'-a','connector','-w'],capture_output=True,text=True)
    token=found.stdout.strip()
    if found.returncode or not token:
        token=secrets.token_urlsafe(48)
        subprocess.run(['security','add-generic-password','-U','-s',service,'-a','connector','-w',token],check=True,capture_output=True)
    env['APP_MOLD_APPLICATION_CONNECTOR_TOKEN']=token
    env['DINGTALK_CALLBACK_TOKEN']=token
    phone=ROOT/'services/phone-watch/.env.local'
    if args.phone_watch_local and not phone.is_file():raise SystemExit('Local phone-watch config is missing')
    if phone.exists() and (not args.offline or args.phone_watch_local):
        values=dict(line.split('=',1) for line in phone.read_text().splitlines() if '=' in line and not line.lstrip().startswith('#'))
        if args.phone_watch_local:
            local=values.get('PHONE_WATCH_LOCAL_ONLY','').strip('"\'').lower()
            ding_write=values.get('PHONE_WATCH_DINGTALK_WRITE_ENABLED','').strip('"\'').lower()
            host=values.get('PHONE_WATCH_PG_HOST','').strip('"\'')
            if local!='true' or ding_write!='false' or host not in {'phone-watch-db','127.0.0.1','localhost'}:raise SystemExit('Phone-watch must explicitly use its local database and disable DingTalk writes')
        phone_token=values.get('PHONE_WATCH_SERVICE_TOKEN') or values.get('INTERNAL_API_TOKEN') or values.get('PHONE_WATCH_INTERNAL_TOKEN')
        if args.phone_watch_local and not phone_token:raise SystemExit('Local phone-watch token is missing')
        if phone_token:env.update(APP_PHONE_WATCH_ENABLED='true',APP_PHONE_WATCH_BASE_URL='http://127.0.0.1:18010',APP_PHONE_WATCH_TOKEN=phone_token.strip('"\''))
    jar=args.jar.resolve() if args.jar else ROOT/'plm-server/target/plm-server-0.0.1-SNAPSHOT.jar'
    if not jar.is_file():raise SystemExit('Build the backend with mvn package first')
    # An in-place Maven rebuild invalidates Spring Boot's lazy-loaded nested jars.
    # Run an immutable copy so building the next version never corrupts this process.
    runtime=Path(tempfile.mkdtemp(prefix='yuewei-plm-mold-runtime-'))
    frozen=runtime/jar.name
    shutil.copy2(jar,frozen)
    os.chdir(ROOT/'plm-server')
    os.execvpe('java',['java','-jar',str(frozen),'--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration'],env)

if __name__=='__main__':main()
