#!/usr/bin/env python3
"""Generate a dedicated read-only DB login; keep its password in macOS Keychain."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import subprocess

def main():
    os.umask(0o077)
    path = Path.home() / '.agents/config/plm-mold-db.json'
    service = 'YueweiPLM-mold-db-reader-20261013'
    result = subprocess.run(['security', 'find-generic-password', '-s', service, '-a', 'plm_mold_reader', '-w'], capture_output=True, text=True)
    password = result.stdout.strip() if result.returncode == 0 else secrets.token_urlsafe(36)
    if result.returncode != 0:
        subprocess.run(['security', 'add-generic-password', '-U', '-s', service, '-a', 'plm_mold_reader', '-w', password], check=True, capture_output=True)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_text(json.dumps({'host':'127.0.0.1','port':15433,'user':'plm_mold_reader','keychainService':service}))
    path.chmod(0o600)
    salt = secrets.token_bytes(16)
    salted = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 4096)
    client = hmac.new(salted, b'Client Key', hashlib.sha256).digest()
    stored = hashlib.sha256(client).digest()
    server = hmac.new(salted, b'Server Key', hashlib.sha256).digest()
    b64 = lambda v: base64.b64encode(v).decode()
    verifier = 'SCRAM-SHA-256$4096:' + b64(salt) + '$' + b64(stored) + ':' + b64(server)
    # CREATE is deliberately not idempotent: an existing role is never silently recredentialed.
    print("CREATE ROLE plm_mold_reader LOGIN PASSWORD '" + verifier + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 4;")
    print("ALTER ROLE plm_mold_reader SET default_transaction_read_only=on;")
    print("ALTER ROLE plm_mold_reader SET statement_timeout='30s';")
    print("GRANT CONNECT ON DATABASE mould_approval,product_and_model_management,plm_data_management TO plm_mold_reader;")

if __name__ == '__main__':
    main()
