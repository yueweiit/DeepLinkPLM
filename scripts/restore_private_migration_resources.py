"""Restore private historical data resources from an authorized local release JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--jar', required=True, type=Path)
args = parser.parse_args()
expected = json.loads((root / 'docs/private-migration-resources.json').read_text())
output = root / 'plm-server/src/main/resources/db/migration'
with zipfile.ZipFile(args.jar) as jar:
    resources = {name: jar.read('BOOT-INF/classes/db/migration/' + name) for name in expected}
for name, content in resources.items():
    if hashlib.sha256(content).hexdigest() != expected[name]:
        raise SystemExit('Private migration resource checksum differs: ' + name)
    path = output / name
    if path.exists() and path.read_bytes() != content:
        raise SystemExit('Existing migration resource differs: ' + name)
for name, content in resources.items():
    (output / name).write_bytes(content)
print('Verified and restored the private historical migration resources.')
