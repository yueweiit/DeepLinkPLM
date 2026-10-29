#!/usr/bin/env python3
"""Read CRM quantities through SSH; never modifies CRM, MES, users or configuration.

Password authentication, when needed, uses SSH_ASKPASS and a protected local file.
The snapshot contains no customer contact, payment or address information.
"""
import argparse, gzip, hashlib, json, os, re, shlex, subprocess, sys, tempfile
from datetime import datetime, timezone
from zoneinfo import ZoneInfo
from pathlib import Path
if __package__:
    from .source_ssh import command as ssh_command, environment as ssh_environment
else:
    from source_ssh import command as ssh_command, environment as ssh_environment

SQL = r"""
SET SESSION MAX_EXECUTION_TIME=120000;
SET SESSION TRANSACTION READ ONLY;
START TRANSACTION WITH CONSISTENT SNAPSHOT;
SELECT JSON_OBJECT('kind','header','format',1,'organization',(SELECT MIN(organization_id) FROM order_main),
 'organizations',(SELECT COUNT(DISTINCT organization_id) FROM order_main),'rows',(SELECT COUNT(*) FROM order_item i JOIN order_main o ON o.id=i.order_id WHERE __SCOPE__),
 'orders',(SELECT COUNT(*) FROM order_main o WHERE __SCOPE__),'periodStart','__FROM__','periodEnd','__TO__','dateMissingOrders',(SELECT COUNT(*) FROM order_main o WHERE __DATE__ IS NULL),'capturedAt',DATE_FORMAT(UTC_TIMESTAMP(),'%Y-%m-%dT%H:%i:%sZ'));
SELECT JSON_OBJECT('kind','line','id',i.id,'orderId',o.id,'orderNo',o.order_no,'status',o.status,
 'approvalStatus',o.approval_status,'deleted',o.deleted+0,'date',COALESCE(o.order_date_str,''),
 'orderDate',o.order_date,'updatedAt',GREATEST(o.update_time,i.update_time),
 'businessDate',CAST(__DATE__ AS CHAR),'code',i.item_code,'name',COALESCE(i.item_name,i.product_name),'spec',i.product_spec,
 'quantity',CAST(i.quantity AS CHAR),'unit',i.unit,'phoneId',i.phone_model_id,'seriesId',i.product_series_id,
 'skuId',i.crm_product_id,'seriesCode',s.series_code,'phoneCode',p.model_code)
 FROM order_item i JOIN order_main o ON o.id=i.order_id
 LEFT JOIN product_series s ON s.id=i.product_series_id AND s.organization_id=o.organization_id
 LEFT JOIN phone_model p ON p.id=i.phone_model_id AND p.organization_id=o.organization_id WHERE __SCOPE__ ORDER BY i.id;
SELECT JSON_OBJECT('kind','footer','rows',(SELECT COUNT(*) FROM order_item i JOIN order_main o ON o.id=i.order_id WHERE __SCOPE__),'complete',true);
ROLLBACK;
"""

def scoped_sql(until=None):
    until=until or datetime.now(ZoneInfo('Asia/Shanghai')).date().isoformat()
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}',until):raise ValueError('Invalid cutoff date')
    business_date="CASE WHEN o.order_date_str REGEXP '^[0-9]{4}[-/][0-9]{2}[-/][0-9]{2}' THEN STR_TO_DATE(REPLACE(LEFT(o.order_date_str,10),'/','-'),'%Y-%m-%d') WHEN o.order_date>0 THEN DATE(FROM_UNIXTIME(o.order_date/1000)) ELSE NULL END"
    scope="("+business_date+") BETWEEN '2025-01-01' AND '"+until+"'"
    return SQL.replace('__SCOPE__',scope).replace('__DATE__',business_date).replace('__FROM__','2025-01-01').replace('__TO__',until)

def command(host, control=None, password_file=None):
    cli='MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --default-character-set=utf8mb4 --batch --raw --skip-column-names -u root --database=cordys-crm'
    return ssh_command(host,'docker exec -i shop-crm-mysql sh -c '+shlex.quote(cli),control,bool(password_file))

def export(output,host,control=None,password_file=None):
    output=Path(output).resolve();output.mkdir(parents=True,exist_ok=True,mode=0o700)
    stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')
    with ssh_environment(password_file) as env:
        path=output/('crm-sales-'+stamp+'-'+os.urandom(4).hex()+'.jsonl.gz');temp=path.with_suffix('.partial')
        try:
            with tempfile.TemporaryFile() as query,temp.open('xb') as dest,tempfile.TemporaryFile() as errors:
                query.write(scoped_sql().encode());query.seek(0)
                process=subprocess.Popen(command(host,control,password_file),stdin=query,stdout=subprocess.PIPE,stderr=errors,env=env,start_new_session=True)
                with gzip.GzipFile(fileobj=dest,mode='wb',mtime=0) as compressed:
                    while chunk:=process.stdout.read(1024*1024):compressed.write(chunk)
                if process.wait(timeout=180)!=0:raise ValueError('CRM 只读连接失败，请核对服务器连接')
            header=None;footer=None;count=0
            with gzip.open(temp,'rt') as data:
                for line in data:
                    row=json.loads(line)
                    if row['kind']=='header':
                        if header is not None or count:raise ValueError('Snapshot header repeated')
                        header=row
                    elif row['kind']=='line':
                        if header is None or footer is not None:raise ValueError('Snapshot order invalid')
                        count+=1
                    elif row['kind']=='footer':footer=row
                    else:raise ValueError('Unknown snapshot record')
            if not header or header['organizations']!=1 or not footer or not footer['complete'] or count!=header['rows'] or count!=footer['rows']:raise ValueError('CRM snapshot incomplete')
            temp.replace(path);path.chmod(0o600)
            return {'file':path.name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'rows':count,'capturedAt':header['capturedAt'],'remoteWrites':0}
        except BaseException:
            temp.unlink(missing_ok=True)
            raise

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--host',default='yuewei@155.138.234.129');p.add_argument('--control',type=Path);p.add_argument('--password-file',type=Path);a=p.parse_args()
    try:print(json.dumps(export(a.output,a.host,a.control,a.password_file),ensure_ascii=False))
    except Exception as e:print('CRM只读取数未完成：'+str(e),file=sys.stderr);sys.exit(1)
