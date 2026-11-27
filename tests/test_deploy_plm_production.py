import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("plm_deploy", ROOT / "deploy-plm-production.py")
DEPLOY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEPLOY)


class DeployScriptTests(unittest.TestCase):
    def git(self, directory, *args):
        return subprocess.check_output(["git", "-C", str(directory), *args], text=True).strip()

    def make_repo(self, files):
        directory = Path(tempfile.mkdtemp(prefix="plm-deploy-test-"))
        self.git(directory, "init", "-b", "main")
        self.git(directory, "config", "user.email", "deploy-test@example.invalid")
        self.git(directory, "config", "user.name", "Deploy test")
        for name, content in files.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
        self.git(directory, "add", ".")
        self.git(directory, "commit", "-m", "base")
        return directory, self.git(directory, "rev-parse", "HEAD")

    def commit_file(self, repo, name, content):
        path = repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        self.git(repo, "add", name)
        self.git(repo, "commit", "-m", "update " + name)
        return self.git(repo, "rev-parse", "HEAD")

    def test_service_map_targets_only_changed_applications(self):
        repo, old = self.make_repo({"README.md": "start\n"})
        self.commit_file(repo, "plm-server/src/main/java/Example.java", "class Example {}\n")
        self.commit_file(repo, "plm-web/src/Example.ts", "export {}\n")
        new = self.commit_file(repo, "services/phone-watch/phone_watch/example.py", "pass\n")
        targets, paths = DEPLOY.classify_changes(repo, old, new)
        self.assertEqual(targets, ["master-data-sync", "phone-watch", "phone-watch-worker", "server", "web"])
        self.assertEqual(len(paths), 3)

    def test_service_map_refuses_compose_and_bind_mounted_script_changes(self):
        for changed_path in ("docker-compose.baota.yml", "scripts/export_master_data_snapshot.py"):
            with self.subTest(path=changed_path):
                repo, old = self.make_repo({"README.md": "start\n"})
                new = self.commit_file(repo, changed_path, "changed\n")
                with self.assertRaises(DEPLOY.DeployError):
                    DEPLOY.classify_changes(repo, old, new)

    def test_migration_history_allows_only_new_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar_path = root / "running.jar"
            migration_dir = root / "migration"
            migration_dir.mkdir()
            original = b"create table example(id bigint);\n"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/db/migration/V1__base.sql", original)
            (migration_dir / "V1__base.sql").write_bytes(original)
            (migration_dir / "V2__additive.sql").write_text("alter table example add column note text;\n")
            self.assertEqual(DEPLOY.verify_migration_history(jar_path, migration_dir), ["V2__additive.sql"])
            (migration_dir / "V1__base.sql").write_bytes(original + b"-- altered\n")
            with self.assertRaises(DEPLOY.DeployError):
                DEPLOY.verify_migration_history(jar_path, migration_dir)

    def test_private_deployed_migration_is_restored_after_git_fast_forward(self):
        deployed_bytes = b"create table example(id bigint);\n-- private production bytes\n"
        repo, _ = self.make_repo({"README.md": "base\n", "plm-server/src/main/resources/db/migration/V1__base.sql": "public source bytes\n"})
        migration_path = repo / "plm-server/src/main/resources/db/migration/V1__base.sql"
        migration_path.write_bytes(deployed_bytes)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            jar_path = root / "running.jar"
            archive = root / "archived"
            with zipfile.ZipFile(jar_path, "w") as jar:
                jar.writestr("BOOT-INF/classes/db/migration/V1__base.sql", deployed_bytes)
            hashes = DEPLOY.archive_running_migrations(jar_path, archive)
            DEPLOY.restore_tracked_migrations_for_pull(
                repo, repo / "plm-server/src/main/resources/db/migration", archive, hashes
            )
            self.assertNotEqual(migration_path.read_bytes(), deployed_bytes)
            restored = migration_path.read_bytes().replace(b"\r\n", b"\n")
            expected = self.git(repo, "show", "HEAD:plm-server/src/main/resources/db/migration/V1__base.sql").encode()
            self.assertEqual(restored.rstrip(b"\n"), expected)
            DEPLOY.restore_running_migrations(
                repo / "plm-server/src/main/resources/db/migration", archive, hashes
            )
            self.assertEqual(
                migration_path.read_bytes(),
                deployed_bytes,
            )

    def test_image_retag_preserves_secret_values_and_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            compose = root / "compose.yml"
            env_file = root / ".env"
            compose.write_text("image: yuewei-plm-web:20261120-main-aaaaaaaaaaaa\n")
            env_file.write_text(
                "PLM_SERVER_IMAGE=yuewei-plm-server:20261120-main-bbbbbbbbbbbb\n"
                "DINGTALK_APP_SECRET=keep-this-value\n"
            )
            os.chmod(env_file, 0o600)
            DEPLOY.replace_image_refs(compose, env_file, {
                "yuewei-plm-web:20261120-main-aaaaaaaaaaaa": "yuewei-plm-web:20261121-main-cccccccccccc",
                "yuewei-plm-server:20261120-main-bbbbbbbbbbbb": "yuewei-plm-server:20261121-main-cccccccccccc",
            })
            self.assertIn("20261121-main-cccccccccccc", compose.read_text())
            self.assertIn("20261121-main-cccccccccccc", env_file.read_text())
            self.assertIn("DINGTALK_APP_SECRET=keep-this-value", env_file.read_text())
            if os.name != "nt":
                self.assertEqual(env_file.stat().st_mode & 0o777, 0o600)

    def test_partial_release_tracks_global_commit_and_independent_service_images(self):
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "deploy-state"
            current = state / "current"
            current.mkdir(parents=True)
            manifest = {
                "commit": "new-source-commit",
                "images": {
                    "server": "yuewei-plm-server:20261120-main-aaaaaaaaaaaa",
                    "master-data-sync": "yuewei-plm-server:20261120-main-aaaaaaaaaaaa",
                    "web": "yuewei-plm-web:20261121-main-bbbbbbbbbbbb",
                    "phone-watch": "yuewei-plm-phone-watch:20261120-main-cccccccccccc",
                    "phone-watch-worker": "yuewei-plm-phone-watch:20261120-main-cccccccccccc",
                },
            }
            (current / "manifest.json").write_text(json.dumps(manifest))
            config = {"services": {name: {"image": image} for name, image in manifest["images"].items()}}
            DEPLOY.verify_current_snapshot(state, config)
            config["services"]["server"]["image"] = "yuewei-plm-server:unexpected"
            with self.assertRaises(DEPLOY.DeployError):
                DEPLOY.verify_current_snapshot(state, config)

    def test_compose_validation_allows_independent_backend_and_sync_images(self):
        config = {
            "services": {
                "redis": {"image": "redis:7-alpine"},
                "server": {"image": "yuewei-plm-server:20261120-mold-sales-subsecond-r5"},
                "master-data-sync": {"image": "yuewei-plm-server:20261120-main-8b3ce75ed8d0", "profiles": ["integrations"]},
                "web": {"image": "yuewei-plm-web:20261120-mold-sales-speed-r3"},
                "phone-watch": {"image": "yuewei-plm-phone-watch:20261120-main-8b3ce75ed8d0"},
                "phone-watch-worker": {"image": "yuewei-plm-phone-watch:20261120-main-8b3ce75ed8d0"},
            }
        }
        DEPLOY.validate_mounts_and_services(config)

    def test_image_pruning_scope_covers_dated_legacy_and_current_release_tags(self):
        self.assertRegex("yuewei-plm-web:20261112-batch-listing-r1", DEPLOY.IMAGE_TAG_PATTERN)
        self.assertRegex("yuewei-plm-web:20261117-receipt-no-phone-fc235eda3fd6", DEPLOY.IMAGE_TAG_PATTERN)
        self.assertRegex("yuewei-plm-server:20261120-main-8b3ce75ed8d0", DEPLOY.IMAGE_TAG_PATTERN)
        self.assertNotRegex("yuewei-plm-web:latest", DEPLOY.IMAGE_TAG_PATTERN)
        self.assertNotRegex("other-project-web:20261120-main-8b3ce75ed8d0", DEPLOY.IMAGE_TAG_PATTERN)

    def test_post_deploy_checks_use_health_endpoints_without_fixed_start_log(self):
        config = {"services": {"web": {"ports": [{"published": 8111}]}}}

        def inspect(container, fmt):
            if fmt == "{{.State.Status}}":
                return "running"
            if fmt == "{{.State.StartedAt}}":
                return "2026-11-23T10:00:00.000000000Z"
            raise AssertionError(fmt)

        def json_check(url):
            if url.endswith("/api/v1/health"):
                return 200, {"code": 0, "data": {"status": "UP"}}
            if url.endswith("/api/v1/health/db"):
                return 200, {"code": 0, "data": {"status": "UP", "database": "UP"}}
            raise AssertionError(url)

        with patch.object(DEPLOY, "container_inspect", side_effect=inspect), \
                patch.object(DEPLOY, "compose_config", return_value=config), \
                patch.object(DEPLOY, "http_status", return_value="200"), \
                patch.object(DEPLOY, "http_json", side_effect=json_check), \
                patch.object(DEPLOY, "run", return_value="Flyway started without the old marker"):
            DEPLOY.post_deploy_checks(Path("/root"), Path("/root/compose.yml"), Path("/root/.env"), ["server"])

    def test_http_json_reads_body_and_status_separately(self):
        written = []

        def curl(args, *, capture=False, env=None):
            temporary = Path(args[args.index("-o") + 1])
            written.append(temporary)
            temporary.write_text('{"code":0,"data":{"status":"UP"}}', encoding="utf-8")
            return "200"

        with patch.object(DEPLOY, "run", side_effect=curl):
            self.assertEqual(
                DEPLOY.http_json("http://127.0.0.1:8111/api/v1/health"),
                (200, {"code": 0, "data": {"status": "UP"}}),
            )
        self.assertEqual(len(written), 1)
        self.assertFalse(written[0].exists())

    def test_backend_health_retries_transient_proxy_response(self):
        responses = [
            DEPLOY.DeployError("HTTP check returned invalid JSON"),
            (200, {"code": 0, "data": {"status": "UP"}}),
            (200, {"code": 0, "data": {"status": "UP", "database": "UP"}}),
        ]
        with patch.object(DEPLOY, "http_json", side_effect=responses), \
                patch.object(DEPLOY.time, "sleep") as sleep:
            DEPLOY.wait_for_backend_health("http://127.0.0.1:8111", timeout_seconds=1)
        sleep.assert_called_once_with(5)


if __name__ == "__main__":
    unittest.main()
