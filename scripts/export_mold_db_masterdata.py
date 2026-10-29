#!/usr/bin/env python3
"""Consistent read-only snapshots through the dedicated SSH tunnel."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import pg8000.native

TABLES = {
    'mould_approval': ['approval_code','generated_codes','model','mould','mould_mapping','phone','phone_models'],
    'product_and_model_management': ['model','model_product','model_product_v2','model_copy1','model_copy2','generated_codes_log','material_sync','brand_mapping'],
    'plm_data_management': ['plm.plm_product','plm.plm_product_mold_code','plm.plm_integration_record','plm.plm_project_requirement_form']
}

def main():
    os.umask(0o077)
    settings = json.loads((Path.home()/'.agents/config/plm-mold-db.json').read_text())
    password = subprocess.check_output(['security','find-generic-password','-s',settings.pop('keychainService'),'-a',settings['user'],'-w'],text=True).strip()
    root = Path.home()/'Library/Application Support/YueweiPLM/dingtalk-mold-backups/20261013'
    output=root/'database'
    output.mkdir(parents=True,exist_ok=True,mode=0o700)
    report={}
    for database,tables in TABLES.items():
        conn=pg8000.native.Connection(**settings,password=password,database=database,timeout=20)
        conn.run('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY')
        if conn.run('SHOW transaction_read_only')[0][0] != 'on':
            raise RuntimeError('Database connection is not read-only')
        report[database]={}
        for table in tables:
            values=conn.run('select * from '+table)
            columns=[x['name'] for x in conn.columns]
            # All table identifiers are source-owned constants above.
            path=output/(database+'__'+table+'.ndjson')
            with path.open('w') as stream:
                for row in values:
                    stream.write(json.dumps(dict(zip(columns,row)),ensure_ascii=False,default=str)+'\n')
            report[database][table]={'rows':len(values),'columns':columns}
        conn.run('ROLLBACK');conn.close()
    (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    manifest=[]
    for path in sorted(root.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS':
            manifest.append(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+str(path.relative_to(root)))
    (root/'SHA256SUMS').write_text('\n'.join(manifest)+'\n')
    print(json.dumps({db:{table:details['rows'] for table,details in tables.items()} for db,tables in report.items()},ensure_ascii=False,indent=2))

if __name__=='__main__': main()
