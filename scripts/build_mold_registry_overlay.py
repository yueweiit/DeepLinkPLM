"""Compile registry sources against the running jar; preserve every baseline entry.

No bytecode transformations, old class replacements, migrations or business consumers.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import subprocess
import zipfile

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--jdk',required=True,type=Path)
    parser.add_argument('--lombok',required=True,type=Path)
    parser.add_argument('--baseline',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    root=Path(__file__).resolve().parents[1]/'plm-server'
    output=args.output.resolve()
    output.mkdir(parents=True,exist_ok=True)
    extracted=output/'baseline'
    extracted.mkdir(exist_ok=True)
    with zipfile.ZipFile(args.baseline) as jar:
        jar.extractall(extracted)
        old={entry.filename:hashlib.sha256(jar.read(entry)).hexdigest() for entry in jar.infolist()}
    classes=output/'compiled'
    classes.mkdir(exist_ok=True)
    source=root/'src/main/java/com/yuewei/plm'
    lombok=output/'lombok.jar'
    shutil.copyfile(args.lombok,lombok)
    classpath=[extracted/'BOOT-INF/classes',*sorted((extracted/'BOOT-INF/lib').glob('*.jar')),lombok]
    subprocess.run([str(args.jdk/'bin/javac.exe'),'-parameters','-encoding','UTF-8','-cp',';'.join(map(str,classpath)),
                    '-processorpath',str(lombok),'-d',str(classes),
                    str(source/'module/moldsharing/MoldShareMappingRegistryService.java'),
                    str(source/'module/moldsharing/MoldShareMappingRegistryController.java')],check=True)
    additions={f'BOOT-INF/classes/{p.relative_to(classes).as_posix()}':p for p in classes.rglob('*.class')}
    expected={
        'com/yuewei/plm/module/moldsharing/MoldShareMappingRegistryService.class',
        'com/yuewei/plm/module/moldsharing/MoldShareMappingRegistryService$WriteInput.class',
        'com/yuewei/plm/module/moldsharing/MoldShareMappingRegistryController.class',
    }
    if set(additions)!={'BOOT-INF/classes/'+p for p in expected}:
        raise RuntimeError('Unexpected compiled classes')
    if set(additions)&set(old):
        raise RuntimeError('Registry overlay would replace existing classes; use source baseline merge')
    with zipfile.ZipFile(args.baseline) as baseline,zipfile.ZipFile(output/'app.jar','w') as result:
        for entry in baseline.infolist():
            result.writestr(entry,baseline.read(entry))
        for name,path in additions.items():
            result.write(path,name,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(output/'app.jar') as result:
        if any(hashlib.sha256(result.read(name)).hexdigest()!=digest for name,digest in old.items()):
            raise RuntimeError('A baseline entry changed')
        if set(result.namelist())!=set(old)|set(additions):
            raise RuntimeError('Unexpected jar entries')
    report={'unchangedEntries':len(old),'addedEntries':sorted(additions),'baselineSha256':hashlib.sha256(args.baseline.read_bytes()).hexdigest(),'candidateSha256':hashlib.sha256((output/'app.jar').read_bytes()).hexdigest()}
    (output/'whitelist-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    main()
