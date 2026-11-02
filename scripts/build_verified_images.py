"""Build code-only releases on a fixed, byte-verified runtime, never the last release."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile


CONFIG_FIELDS = ('User', 'Env', 'Entrypoint', 'Cmd', 'WorkingDir', 'ExposedPorts',
                 'Volumes', 'Healthcheck', 'StopSignal', 'OnBuild', 'Labels')
PAYLOADS = {'server': ('app/app.jar',),
            'web': ('usr/share/nginx/html', 'opt/plm-frontend')}


def output(command):
    return subprocess.check_output(command, text=True).strip()


def image(reference):
    return json.loads(output(['docker', 'image', 'inspect', reference]))[0]


def inventory(reference, excluded=()):
    container = output(['docker', 'create', '--network=none', '--entrypoint=/bin/true', reference])
    process = None
    try:
        process = subprocess.Popen(['docker', 'cp', container + ':/.', '-'], stdout=subprocess.PIPE)
        result = {}
        with tarfile.open(fileobj=process.stdout, mode='r|') as stream:
            for member in stream:
                name = member.name.removeprefix('./').rstrip('/')
                if name in ('', '.', 'etc/hosts', 'etc/hostname', 'etc/resolv.conf', '.dockerenv'):
                    continue
                if any(name == part or name.startswith(part + '/')
                       for part in ('dev', 'proc', 'sys', *excluded)):
                    continue
                digest = ''
                if member.isfile():
                    checksum = hashlib.sha256()
                    handle = stream.extractfile(member)
                    for chunk in iter(lambda: handle.read(1024 * 1024), b''):
                        checksum.update(chunk)
                    digest = checksum.hexdigest()
                result[name] = [member.type.decode(), member.mode, member.uid, member.gid,
                                member.linkname, member.size, digest]
        if process.wait() != 0:
            raise RuntimeError('Image filesystem inspection failed')
        return result
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            process.wait()
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)


def runtime_settings(config):
    if any(config.get(field) for field in ('Volumes', 'Healthcheck', 'OnBuild')):
        raise ValueError('Review unsupported runtime directives before flattening')
    lines = []
    for item in config.get('Env') or []:
        key, value = item.split('=', 1)
        lines.append('ENV ' + key + '=' + json.dumps(value))
    for key, directive in [('WorkingDir', 'WORKDIR'), ('User', 'USER'), ('StopSignal', 'STOPSIGNAL')]:
        if config.get(key):
            lines.append(directive + ' ' + config[key])
    for port in config.get('ExposedPorts') or {}:
        lines.append('EXPOSE ' + port)
    for key in ('Entrypoint', 'Cmd'):
        if config.get(key) is not None:
            lines.append(key.upper() + ' ' + json.dumps(config[key]))
    return '\n'.join(lines) + '\n'


def require_same_settings(old, new):
    if (old['Architecture'], old['Os']) != (new['Architecture'], new['Os']):
        raise ValueError('Image platform changed')
    changed = [key for key in CONFIG_FIELDS if old['Config'].get(key) != new['Config'].get(key)]
    if changed:
        raise ValueError('Runtime settings changed: ' + ', '.join(changed))


def build(context, tag, labels=None):
    command = ['docker', 'build', '--pull=false', '--network=none', '-t', tag]
    for key, value in (labels or {}).items():
        command.extend(['--label', key + '=' + value])
    subprocess.run([*command, str(context)], check=True)


def runtime(service, source, current, tag, context):
    source_image, current_image = image(source), image(current)
    excluded = PAYLOADS[service]
    expected = inventory(current_image['Id'], excluded)
    if inventory(source_image['Id'], excluded) != expected:
        raise ValueError('Source runtime differs from the currently deployed runtime')
    context.mkdir(mode=0o700)
    removals = ' '.join('/' + name for name in excluded)
    dockerfile = ('FROM ' + source_image['RepoTags'][0] + ' AS source\nUSER root\nRUN rm -rf ' + removals
                  + '\nFROM scratch\nCOPY --from=source / /\n' + runtime_settings(current_image['Config']))
    (context / 'Dockerfile').write_text(dockerfile)
    build(context, tag, current_image['Config'].get('Labels'))
    actual = image(tag)
    require_same_settings(current_image, actual)
    if inventory(tag) != expected:
        raise ValueError('Flattened runtime bytes or permissions changed')
    return {'image': actual['Id'], 'bytes': actual['Size'], 'layers': len(actual['RootFS']['Layers']),
            'runtimePathsVerified': len(expected), 'source': source_image['Id']}


def release(service, base, current, tag, context):
    base_image = image(base)
    current_image = image(current)
    require_same_settings(current_image, base_image)
    excluded = PAYLOADS[service]
    if inventory(base_image['Id']) != inventory(current_image['Id'], excluded):
        raise ValueError('Fixed runtime no longer matches the deployed runtime')
    copies = ('COPY app.jar /app/app.jar\n' if service == 'server' else
              'COPY dist/ /usr/share/nginx/html/\nCOPY dist-sha256.txt /opt/plm-frontend/dist-sha256.txt\n')
    (context / 'Dockerfile').write_text('FROM ' + base_image['RepoTags'][0] + '\n' + copies)
    build(context, tag)
    actual = image(tag)
    require_same_settings(current_image, actual)
    if inventory(actual['Id'], excluded) != inventory(base_image['Id']):
        raise ValueError('Release changed runtime files')
    return {'image': actual['Id'], 'bytes': actual['Size'], 'layers': len(actual['RootFS']['Layers']),
            'runtime': base_image['Id']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('runtime', 'release'))
    parser.add_argument('service', choices=tuple(PAYLOADS))
    parser.add_argument('--source', required=True)
    parser.add_argument('--current', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--context', type=Path, required=True)
    args = parser.parse_args()
    action = runtime if args.action == 'runtime' else release
    print(json.dumps(action(args.service, args.source, args.current, args.tag, args.context)))


if __name__ == '__main__':
    main()
