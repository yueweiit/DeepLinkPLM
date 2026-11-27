"""Overlay only the compiled mold sharing branch on the current local runtime."""
import hashlib
import copy
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
build = root / 'plm-server/target-local-8111'
prefixes = (
    'BOOT-INF/classes/com/yuewei/plm/module/moldsharing/',
    'BOOT-INF/classes/com/yuewei/plm/module/manufacturing/ManufacturingIdentityService',
    'BOOT-INF/classes/com/yuewei/plm/common/util/ProductBusinessCodeGenerator',
    'BOOT-INF/classes/com/yuewei/plm/common/util/ManufacturingHole',
)
migrations = (
    'BOOT-INF/classes/db/migration/V20261111_1100__mold_entity_mapping_codes.sql',
    'BOOT-INF/classes/db/migration/V20261112_1000__mold_mapping_code_shared_phone.sql',
)
with zipfile.ZipFile(build / 'baseline-8111.jar') as baseline, zipfile.ZipFile(build / 'plm-server-0.0.1-SNAPSHOT.jar') as compiled:
    additions = {n: compiled.read(n) for n in compiled.namelist() if n.startswith(prefixes) or n in migrations}
    assert all(migration in additions for migration in migrations)
    changed = sorted(n for n, data in additions.items() if n not in baseline.namelist() or baseline.read(n) != data)
    with zipfile.ZipFile(build / 'app.jar', 'w') as output:
        for entry in baseline.infolist():
            if entry.filename not in additions:
                output.writestr(copy.copy(entry), baseline.read(entry))
        for name, data in additions.items():
            output.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(build / 'app.jar') as output:
        for name in baseline.namelist():
            if name not in additions:
                assert output.read(name) == baseline.read(name), name
report = {'changedEntries': changed, 'baselineSha256': hashlib.sha256((build / 'baseline-8111.jar').read_bytes()).hexdigest()}
(build / 'overlay-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print(json.dumps(report, indent=2))
