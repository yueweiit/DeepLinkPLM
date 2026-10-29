#!/usr/bin/env python3
"""Read-only OA acquisition. Private backups are resumable and never contain credentials."""
import argparse
import collections
import concurrent.futures
import hashlib
import json
import mimetypes
import os
from pathlib import Path
import time
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import urllib.request
import urllib.parse

PROCESS_CODE = 'PROC-89949932-19DE-48EB-A20B-2595CB0F804D'
TZ = ZoneInfo('Asia/Shanghai')
MAX_FILE = 50 * 1024 * 1024


def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    payload = data if isinstance(data, bytes) else (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode()
    temporary = path.with_name(path.name + '.part')
    temporary.write_bytes(payload)
    temporary.chmod(0o600)
    temporary.replace(path)


class Client:
    def __init__(self, config):
        # The OA skill requires this token and both warm-up calls before querying.
        self.token = self.request('https://oapi.dingtalk.com/gettoken?' + urllib.parse.urlencode(
            {'appkey': config['appKey'], 'appsecret': config['appSecret']}))['access_token']
        for path in ('getbyuserid', 'listbyuserid'):
            try:
                self.old('/topapi/process/' + path, {'userid': 'manager1'})
            except RuntimeError:
                pass

    def request(self, url, payload=None, headers=None):
        for attempt in range(4):
            try:
                req = urllib.request.Request(url, data=None if payload is None else json.dumps(payload).encode(),
                    headers={'Content-Type': 'application/json', **(headers or {})})
                with urllib.request.urlopen(req, timeout=25) as response:
                    value = json.loads(response.read(8 * 1024 * 1024))
                if value.get('errcode', 0) != 0:
                    raise RuntimeError('DingTalk error code ' + str(value.get('errcode')))
                return value
            except Exception:
                if attempt == 3:
                    # Do not include request URLs or tokens in exceptions or logs.
                    raise RuntimeError('DingTalk request failed after retries') from None
                time.sleep(1 + attempt)

    def old(self, path, payload):
        return self.request('https://oapi.dingtalk.com' + path + '?access_token=' + self.token, payload)

    def schema(self):
        return self.request('https://api.dingtalk.com/v1.0/workflow/forms/schemas/processCodes?' +
            urllib.parse.urlencode({'processCode': PROCESS_CODE}), headers={'x-acs-dingtalk-access-token': self.token})['result']

    def ids(self, start, end):
        result = []
        while start < end:
            stop = min(start + timedelta(days=30), end)
            cursor = 0
            seen = set()
            while True:
                page = self.old('/topapi/processinstance/listids', {'process_code': PROCESS_CODE,
                    'start_time': int(start.timestamp() * 1000), 'end_time': int(stop.timestamp() * 1000),
                    'cursor': cursor, 'size': 10})['result']
                result.extend(page.get('list') or [])
                next_cursor = page.get('next_cursor')
                if next_cursor in (None, 0, '0'):
                    break
                if str(next_cursor) in seen:
                    raise RuntimeError('DingTalk pagination repeated a cursor')
                seen.add(str(next_cursor))
                cursor = next_cursor
            print('Listed', start.date(), stop.date(), flush=True)
            start = stop
        return list(dict.fromkeys(result))


def media_from(detail):
    for field in detail.get('form_component_values') or []:
        kind = field.get('component_type')
        if kind not in ('DDPhotoField', 'DDAttachment') or field.get('value') in (None, '', 'null'):
            continue
        try:
            values = json.loads(field['value']) if isinstance(field['value'], str) else field['value']
        except ValueError:
            continue
        for value in values if isinstance(values, list) else []:
            item = {'sourceUrl': value} if isinstance(value, str) else dict(value)
            item.update({'componentId': field.get('id'), 'category': 'key_photo' if field.get('id') ==
                'DDPhotoField-IH4TZP1K' else 'sample_photo' if kind == 'DDPhotoField' else 'attachment'})
            yield item


def download(item, root):
    url = item.get('sourceUrl') or item.get('downloadUrl')
    parsed = urllib.parse.urlparse(url or '')
    if parsed.scheme != 'https' or not (parsed.hostname == 'static.dingtalk.com' or
        (parsed.hostname or '').endswith('.dingtalk.com') or (parsed.hostname or '').endswith('.alicdn.com')):
        return {**item, 'downloadStatus': 'pending_authorization'}
    key = hashlib.sha256(url.encode()).hexdigest()
    ext = Path(parsed.path).suffix[:10] or '.bin'
    file = root / 'media' / (key + ext)
    try:
        if not file.exists():
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=30) as response:
                # Only trust files from the approval media hosts, including after redirects.
                final = urllib.parse.urlparse(response.url)
                if final.scheme != 'https' or not ((final.hostname or '').endswith('.dingtalk.com') or
                    (final.hostname or '').endswith('.alicdn.com')):
                    raise RuntimeError('Media redirect host rejected')
                content = response.read(MAX_FILE + 1)
            if len(content) > MAX_FILE:
                raise RuntimeError('Media exceeds size limit')
            save(file, content)
        content = file.read_bytes()
        return {**item, 'sourceUrl': url, 'path': str(file.relative_to(root)),
            'fileName': item.get('fileName') or Path(parsed.path).name, 'fileSize': len(content),
            'contentType': mimetypes.guess_type(file.name)[0] or 'application/octet-stream',
            'sha256': hashlib.sha256(content).hexdigest(), 'downloadStatus': 'downloaded'}
    except Exception:
        return {**item, 'sourceUrl': url, 'downloadStatus': 'failed'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, default=Path.home() / 'Library/Application Support/YueweiPLM/dingtalk-mold-backups/20261013')
    parser.add_argument('--config', type=Path, default=Path.home() / '.agents/config/dingtalk.json')
    parser.add_argument('--retry-media', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    root = args.output.expanduser()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    if args.retry_media:
        records = [json.loads(line) for line in (root / 'instances.ndjson').read_text().splitlines() if line]
        for record in records:
            record['media'] = [download(f, root) if f['downloadStatus'] != 'downloaded' else f for f in record['media']]
            save(root / 'instances' / (record['instanceId'] + '.json'), record)
        save(root / 'instances.ndjson', ('\n'.join(json.dumps(r, ensure_ascii=False) for r in records) + '\n').encode())
        report = json.loads((root / 'import-report.json').read_text())
        report['downloadStates'] = dict(collections.Counter(f['downloadStatus'] for r in records for f in r['media']))
        save(root / 'import-report.json', report)
        save(root / 'SHA256SUMS', ('\n'.join(hashlib.sha256(f.read_bytes()).hexdigest() + '  ' + str(f.relative_to(root))
            for f in sorted(root.rglob('*')) if f.is_file() and f.name != 'SHA256SUMS') + '\n').encode())
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return
    client = Client(json.loads(args.config.read_text()))
    schema = client.schema()
    save(root / 'schema.json', schema)
    end = datetime.now(TZ)
    ids = client.ids(datetime(2026, 1, 1, tzinfo=TZ), end)
    save(root / 'instance-ids.json', ids)
    users, departments = {}, {}
    records, failures = [], []
    for n, instance_id in enumerate(ids, 1):
        try:
            detail = client.old('/topapi/processinstance/get', {'process_instance_id': instance_id})['process_instance']
            user_id, dept_id = detail.get('originator_userid'), detail.get('originator_dept_id')
            if user_id and user_id not in users:
                try:
                    user = client.old('/topapi/v2/user/get', {'userid': user_id})['result']
                    users[user_id] = {k: user.get(k) for k in ('userid', 'name', 'dept_id_list')}
                except RuntimeError:
                    users[user_id] = {'userid': user_id, 'name': str(detail.get('title', '')).split('提交的')[0]}
            if dept_id and str(dept_id) not in departments:
                try:
                    dept = client.old('/topapi/v2/department/get', {'dept_id': dept_id})['result']
                    departments[str(dept_id)] = {k: dept.get(k) for k in ('dept_id', 'name', 'parent_id')}
                except RuntimeError:
                    departments[str(dept_id)] = {'dept_id': dept_id}
            media = list(media_from(detail))
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                files = list(pool.map(lambda value: download(value, root), media))
            record = {'processCode': PROCESS_CODE, 'instanceId': instance_id, 'detail': detail,
                'originator': users.get(user_id), 'department': departments.get(str(dept_id)), 'media': files}
            save(root / 'instances' / (instance_id + '.json'), record)
            records.append(record)
            print('Archived', n, '/', len(ids), flush=True)
        except RuntimeError:
            failures.append(instance_id)
    save(root / 'directory.json', {'users': users, 'departments': departments})
    save(root / 'instances.ndjson', ('\n'.join(json.dumps(r, ensure_ascii=False) for r in records) + '\n').encode())
    media = [f for r in records for f in r['media']]
    report = {'processCode': PROCESS_CODE, 'from': '2026-01-01T00:00:00+08:00', 'until': end.isoformat(),
        'listed': len(ids), 'archived': len(records), 'failedInstances': failures,
        'states': dict(collections.Counter(r['detail'].get('status') for r in records)),
        'uniqueMedia': len({f.get('sourceUrl') for f in media}),
        'downloadStates': dict(collections.Counter(f['downloadStatus'] for f in media))}
    save(root / 'import-report.json', report)
    manifest = []
    for file in sorted(root.rglob('*')):
        if file.is_file() and file.name != 'SHA256SUMS':
            manifest.append(hashlib.sha256(file.read_bytes()).hexdigest() + '  ' + str(file.relative_to(root)))
    save(root / 'SHA256SUMS', ('\n'.join(manifest) + '\n').encode())
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if failures or any(f['downloadStatus'] != 'downloaded' for f in media):
        raise SystemExit(2)


if __name__ == '__main__':
    main()
