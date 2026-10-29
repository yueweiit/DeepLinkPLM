#!/usr/bin/env python3
"""Restore a local backup and run the real application migrations in an isolated database.

This is verification orchestration, not a second identity or approval implementation.
No production URI, credentials in arguments, or external approval writes are accepted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

CONTAINER = "yuewei-plm-postgres-local"


def sql(database, query):
    result = subprocess.run(
        ["docker", "exec", CONTAINER, "psql", "-U", "postgres", "-d", database,
         "-v", "ON_ERROR_STOP=1", "-At", "-c", query],
        check=True, capture_output=True, text=True,
    )
    return result.stdout.strip()


def snapshot(database, legacy_columns=None):
    result = {}
    for table in ["plm_phone_model_member", "plm_mold_application", "plm_product",
                  "plm_collaboration_item", "plm_collaboration_mold_set",
                  "plm_collaboration_mold_part", "plm_inventory", "plm_product_mold_code"]:
        columns = (legacy_columns or {}).get(table)
        if columns is None:
            columns = json.loads(sql(database, "SELECT json_agg(column_name ORDER BY ordinal_position) "
                "FROM information_schema.columns WHERE table_schema='plm' AND table_name='" + table + "'"))
            if table == "plm_product":
                columns = [name for name in columns if name != "phone_model_member_id"]
        if not all(re.fullmatch(r"[a-zA-Z0-9_]+", name) for name in columns):
            raise RuntimeError("Unexpected column identifier")
        selected = ",".join('"' + name + '"' for name in columns)
        # Sort the row JSON itself, avoiding dependence on different legacy primary-key names.
        value = sql(database, f"SELECT json_build_object('count',count(*),'checksum',"
                    f"md5(coalesce(string_agg(to_jsonb(t)::text,'|' "
                    f"ORDER BY to_jsonb(t)::text),''))) FROM (SELECT {selected} FROM plm.{table}) t")
        result[table] = json.loads(value)
        result[table]["columns"] = columns
    result["migrations"] = json.loads(sql(database,
        "SELECT coalesce(json_agg(t ORDER BY installed_rank),'[]'::json) FROM "
        "(SELECT installed_rank,version,description,success FROM plm.flyway_schema_history) t"))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backup", type=Path, required=True)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--port", type=int, default=18081)
    args = parser.parse_args()
    backup, jar, output = args.backup.resolve(), args.jar.resolve(), args.output.resolve()
    if not backup.is_file() or not jar.is_file():
        parser.error("Backup and application jar must exist")
    manifest = backup.parent / "manifest.json"
    if not manifest.is_file():
        parser.error("A SHA-256 backup manifest is required")
    hashes = json.loads(manifest.read_text())["sha256"]
    for name, expected in hashes.items():
        path = backup.parent / name
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError("Backup checksum mismatch: " + name)
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    database = "plm_phone_preflight_" + str(os.getpid())
    if not re.fullmatch(r"plm_phone_preflight_[0-9]+", database):
        raise RuntimeError("Invalid isolated database name")
    sql("postgres", "CREATE DATABASE " + database)
    process = None
    try:
        with backup.open("rb") as source:
            subprocess.run(["docker", "exec", "-i", CONTAINER, "pg_restore", "-U", "postgres",
                            "--no-owner", "--no-acl", "--exit-on-error", "-d", database],
                           stdin=source, check=True, capture_output=True)
        before = snapshot(database)
        info = json.loads(subprocess.check_output(["docker", "inspect", CONTAINER]))[0]
        settings = dict(entry.split("=", 1) for entry in info["Config"]["Env"] if "=" in entry)
        env = {**os.environ, "DB_URL": f"jdbc:postgresql://127.0.0.1:15432/{database}?currentSchema=plm",
               "DB_USERNAME": "postgres", "DB_PASSWORD": settings["POSTGRES_PASSWORD"],
               "SERVER_PORT": str(args.port), "SERVER_ADDRESS": "127.0.0.1",
               "SPRING_PROFILES_ACTIVE": "dev", "APP_SECURITY_ENABLED": "true",
               "APP_MOLD_APPLICATION_ENABLED": "true", "APP_MOLD_APPLICATION_READ_SYNC_ENABLED": "false",
               "APP_MOLD_APPLICATION_WRITE_ENABLED": "false", "APP_MOLD_APPLICATION_CENTRAL_CODE_ENABLED": "false",
               "APP_MOLD_APPLICATION_IMPORT_DIRECTORY": "", "APP_PHONE_WATCH_ENABLED": "false",
               "DINGTALK_INTEGRATION_ENABLED": "false", "APP_STORAGE_LOCAL_ROOT": str(output / "uploads")}
        log_path = output / "migration.log"
        with log_path.open("w") as log:
            process = subprocess.Popen(["java", "-jar", str(jar),
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration"],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                text = log_path.read_text()
                if "Started PlmServerApplication" in text or re.search(r"Started \w+Application in", text):
                    break
                if process.poll() is not None:
                    raise RuntimeError("Isolated application startup failed; inspect " + str(log_path))
                time.sleep(0.5)
            else:
                raise RuntimeError("Isolated application startup timed out")
            # Preserve original fields exactly, while reporting additive columns from parallel work.
            after = snapshot(database, {table: value["columns"] for table, value in before.items() if table != "migrations"})
            for table in ["plm_collaboration_mold_set", "plm_collaboration_mold_part", "plm_inventory", "plm_product_mold_code", "plm_product"]:
                if before[table] != after[table]:
                    raise RuntimeError("Unexpected historical data change: " + table)
            for table in ["plm_mold_application", "plm_collaboration_item"]:
                if before[table]["count"] != after[table]["count"]:
                    raise RuntimeError("Unexpected historical record creation: " + table)
            history = json.loads(sql(database, "SELECT coalesce(json_agg(detail_json::jsonb),'[]'::json) "
                "FROM plm.plm_operation_log WHERE business_type='PHONE_IDENTITY_HISTORY'"))
            applications = json.loads(sql(database, "SELECT coalesce(json_agg(t ORDER BY application_id),'[]'::json) FROM "
                "(SELECT application_id,external_instance_id as instance_id,model,phone_model_id,project_id,item_id,mold_set_id,"
                "approval_granted,authorization_json,execution_status,provisioning_status FROM plm.plm_mold_application "
                "WHERE deleted_flag=0) t"))
            unverified = json.loads(sql(database, "SELECT coalesce(json_agg(t ORDER BY item_id),'[]'::json) FROM "
                "(SELECT item_id,mold_name,compatible_models,engraved_code FROM plm.plm_collaboration_item i "
                "WHERE deleted_flag=0 AND NOT EXISTS (SELECT 1 FROM plm.plm_collaboration_phone_set_compatibility c "
                "WHERE c.item_id=i.item_id AND (c.pc_confirmed OR c.tpu_confirmed))) t"))
            report = {"backupSha256": hashes[backup.name], "before": before, "after": after,
                      "identityDecisions": history, "applications": applications,
                      "archivesWithoutConfirmedFit": unverified, "historicalPhysicalRecordsUnchanged": True,
                      "realApprovalWritesEnabled": False}
            destination = output / "preflight.json"
            destination.write_text(json.dumps(report, ensure_ascii=False, indent=2))
            destination.chmod(0o600)
            print(json.dumps({"report": str(destination), "phonesBefore": before["plm_phone_model_member"]["count"],
                              "phonesAfter": after["plm_phone_model_member"]["count"],
                              "moldSets": after["plm_collaboration_mold_set"]["count"],
                              "applications": after["plm_mold_application"]["count"]}, ensure_ascii=False))
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        sql("postgres", "DROP DATABASE " + database + " WITH (FORCE)")


if __name__ == "__main__":
    main()
