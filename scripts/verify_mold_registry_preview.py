"""Checks only the isolated local acceptance database and HTTP service."""
import bcrypt, json, secrets, subprocess, urllib.request
from pathlib import Path

DOCKER = r'C:\Users\46733\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
DB = 'plm_mold_registry_acceptance_20261110'
OUT = Path(__file__).resolve().parents[1] / 'plm-server' / 'registry-release-r2'
def sql(statement):
    result=subprocess.run([DOCKER,'exec','-i','plm-local-20261106-postgres-1','psql','-U','postgres','-d',DB,'-At','-v','ON_ERROR_STOP=1'],input='set search_path=plm;\n'+statement,text=True,encoding='utf-8',capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return result.stdout.strip().removeprefix('SET').strip()
def api(path, data=None, token=None, method=None):
    headers={'Content-Type':'application/json'}
    if token: headers['Authorization']='Bearer '+token
    req=urllib.request.Request('http://127.0.0.1:8113/api/v1'+path,data=json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
    with urllib.request.urlopen(req,timeout=30) as response: result=json.load(response)
    assert result['code']==0, result
    return result['data']
def main():
    name='registry-acceptance-'+secrets.token_hex(4)
    password=secrets.token_urlsafe(24)
    hashed=bcrypt.hashpw(password.encode(),bcrypt.gensalt(rounds=4)).decode()
    uid=sql('select coalesce(max(user_id),0)+100 from sys_user;')
    sql(f"insert into sys_user(user_id,user_code,user_name,login_name,username,display_name,password_hash,status,all_permissions) values({uid},'{name}','{name}','{name}','{name}','隔离共模验收','{hashed}','active',true);")
    session=api('/auth/login',{'username':name,'password':password})
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'browser-session.private.json').write_text(json.dumps(session),encoding='utf-8')
    listing=api('/mold-share-mappings',token=session['token'])
    assert listing['canWrite'] is True
    print(json.dumps({'listSuccess':True,'canWrite':listing['canWrite'],'totalElements':listing['totalElements']},ensure_ascii=False))
if __name__=='__main__': main()
