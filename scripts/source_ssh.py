"""Shared transport for existing read-only CRM/MES exporters."""
from contextlib import contextmanager
import os
from pathlib import Path
import re
import tempfile


def command(host, remote_command, control=None, password=False):
    if not re.fullmatch(r'[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+', host):
        raise ValueError('Invalid SSH host')
    args = ['ssh', '-T', '-o', 'StrictHostKeyChecking=yes', '-o', 'UpdateHostKeys=no',
            '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=20']
    if control:
        args += ['-S', str(control)]
    args += ['-o', 'BatchMode=no', '-o', 'NumberOfPasswordPrompts=1'] if password else ['-o', 'BatchMode=yes']
    return args + [host, remote_command]


@contextmanager
def environment(password_file=None):
    env = dict(os.environ)
    env['LC_ALL'] = 'C'
    if not password_file:
        yield env
        return
    secret = Path(password_file).expanduser().resolve()
    if not secret.is_file() or secret.stat().st_mode & 0o077:
        raise ValueError('SSH password file must be private (0600)')
    with tempfile.TemporaryDirectory(prefix='plm-source-askpass-') as directory:
        helper = Path(directory) / 'askpass'
        helper.write_text('#!/bin/sh\nexec cat -- "$PLM_SOURCE_SSH_PASSWORD_FILE"\n')
        helper.chmod(0o700)
        env.update(SSH_ASKPASS=str(helper), SSH_ASKPASS_REQUIRE='force', DISPLAY='plm:0',
                   PLM_SOURCE_SSH_PASSWORD_FILE=str(secret))
        yield env
