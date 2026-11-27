#!/usr/bin/env python3
"""Fast-forward and deploy DeepLinkPLM from its fixed production checkout."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from datetime import datetime
from urllib.parse import urlsplit
import zipfile

try:
    import fcntl
except ImportError:  # pragma: no cover - deployment host is Linux; keeps local tests importable on Windows.
    fcntl = None


PROJECT = "yuewei-plm"
REMOTE_MATCH = "github.com/jdb123-JDB/DeepLinkPLM.git"
SCRIPT_HASH_AT_START = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
IMAGE_TAG_PATTERN = re.compile(r"^yuewei-plm-(?:server|web|phone-watch):20\d{6}-.+$")
SERVICE_CONTAINERS = {
    "server": "plm-server",
    "master-data-sync": "yuewei-plm-master-data-sync-1",
    "web": "plm-web",
    "phone-watch": "phone-watch",
    "phone-watch-worker": "phone-watch-worker",
    "redis": "plm-redis",
}


class DeployError(RuntimeError):
    pass


def run(args: list[str], *, capture: bool = False, env: dict[str, str] | None = None) -> str:
    result = subprocess.run(
        args,
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.PIPE if capture else None,
        env=env,
    )
    if result.returncode:
        detail = (result.stderr or result.stdout or "").strip() if capture else ""
        raise DeployError(f"Command failed ({result.returncode}): {' '.join(args)}" + (f"\n{detail}" if detail else ""))
    return result.stdout.strip() if capture else ""


def git(root: Path, *args: str, capture: bool = True) -> str:
    return run(["git", "-C", str(root), *args], capture=capture)


def container_inspect(container: str, fmt: str) -> str:
    return run(["docker", "inspect", container, "--format", fmt], capture=True)


def container_env(container: str) -> dict[str, str]:
    raw = container_inspect(container, "{{range .Config.Env}}{{println .}}{{end}}")
    result: dict[str, str] = {}
    for line in raw.splitlines():
        key, separator, value = line.partition("=")
        if separator:
            result[key] = value
    return result


def atomic_write(path: Path, content: str, mode: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as stream:
            stream.write(content)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def compose_command(root: Path, compose_file: Path, env_file: Path, *args: str) -> list[str]:
    return [
        "docker", "compose", "--profile", "integrations",
        "--env-file", str(env_file), "--project-directory", str(root),
        "-p", PROJECT, "-f", str(compose_file), *args,
    ]


def compose_config(root: Path, compose_file: Path, env_file: Path) -> dict:
    output = run(compose_command(root, compose_file, env_file, "config", "--format", "json"), capture=True)
    return json.loads(output)


def validate_mounts_and_services(config: dict) -> None:
    services = config.get("services", {})
    required = set(SERVICE_CONTAINERS)
    missing = sorted(required - set(services))
    if missing:
        raise DeployError("Production Compose is missing services: " + ", ".join(missing))
    if "integrations" not in services["master-data-sync"].get("profiles", []):
        raise DeployError("master-data-sync must remain in the integrations profile")
    for name in required:
        if not services[name].get("image"):
            raise DeployError("Production Compose must resolve an image for service: " + name)
    for name, service in services.items():
        for mount in service.get("volumes", []):
            if mount.get("type") == "bind":
                source = mount.get("source")
                if not source or not Path(source).exists():
                    raise DeployError(f"Missing bind source for {name}: {source}")


def migration_inventory(jar_path: Path) -> dict[str, str]:
    prefix = "BOOT-INF/classes/db/migration/"
    with zipfile.ZipFile(jar_path) as jar:
        return {
            name[len(prefix):]: hashlib.sha256(jar.read(name)).hexdigest()
            for name in jar.namelist()
            if name.startswith(prefix) and name.endswith(".sql")
        }


def archive_running_migrations(jar_path: Path, archive_dir: Path) -> dict[str, str]:
    prefix = "BOOT-INF/classes/db/migration/"
    archive_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(archive_dir, 0o700)
    with zipfile.ZipFile(jar_path) as jar:
        resources = {
            name[len(prefix):]: jar.read(name)
            for name in jar.namelist()
            if name.startswith(prefix) and name.endswith(".sql")
        }
    if not resources:
        raise DeployError("Running JAR contains no Flyway migration files")
    hashes: dict[str, str] = {}
    for name, content in resources.items():
        if Path(name).name != name or not re.fullmatch(r"V[A-Za-z0-9_.-]+\.sql", name):
            raise DeployError("Unexpected migration resource path in the running JAR: " + name)
        (archive_dir / name).write_bytes(content)
        os.chmod(archive_dir / name, 0o600)
        hashes[name] = hashlib.sha256(content).hexdigest()
    return hashes


def is_git_tracked(root: Path, relative: str) -> bool:
    return subprocess.run(
        ["git", "-C", str(root), "ls-files", "--error-unmatch", "--", relative],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0


def restore_tracked_migrations_for_pull(root: Path, migration_dir: Path, archive_dir: Path, hashes: dict[str, str]) -> None:
    for name, expected_hash in hashes.items():
        path = migration_dir / name
        relative = path.relative_to(root).as_posix()
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() != expected_hash:
            raise DeployError("Local deployed migration differs from the running JAR; refusing to overwrite: " + name)
        if is_git_tracked(root, relative):
            run(["git", "-C", str(root), "restore", "--worktree", "--", relative], capture=False)


def restore_running_migrations(migration_dir: Path, archive_dir: Path, hashes: dict[str, str]) -> None:
    migration_dir.mkdir(parents=True, exist_ok=True)
    for name, expected_hash in hashes.items():
        source = archive_dir / name
        content = source.read_bytes()
        if hashlib.sha256(content).hexdigest() != expected_hash:
            raise DeployError("Archived deployed migration checksum changed: " + name)
        destination = migration_dir / name
        destination.write_bytes(content)
        if os.name != "nt":
            os.chmod(destination, 0o644)


def verify_migration_history(jar_path: Path, migration_dir: Path) -> list[str]:
    deployed = migration_inventory(jar_path)
    local = {
        path.name: hashlib.sha256(path.read_bytes()).hexdigest()
        for path in migration_dir.glob("*.sql")
    }
    missing = sorted(deployed.keys() - local.keys())
    changed = sorted(name for name in deployed.keys() & local.keys() if deployed[name] != local[name])
    if missing or changed:
        details = []
        if missing:
            details.append("missing deployed migrations: " + ", ".join(missing))
        if changed:
            details.append("modified deployed migrations: " + ", ".join(changed))
        raise DeployError("Migration history does not match the running JAR; " + "; ".join(details))
    return sorted(local.keys() - deployed.keys())


def classify_changes(root: Path, old_commit: str, new_commit: str) -> tuple[list[str], list[str]]:
    status = git(root, "diff", "--name-status", old_commit, new_commit)
    targets: set[str] = set()
    paths: list[str] = []
    unsupported: list[str] = []
    for line in status.splitlines():
        columns = line.split("\t")
        state, path = columns[0], columns[-1]
        paths.append(path)
        if path.startswith("plm-server/src/main/resources/db/migration/") and state != "A":
            raise DeployError(f"Flyway migrations may only be added, not modified/deleted: {state} {path}")
        if path.startswith("plm-server/"):
            targets.update(("server", "master-data-sync"))
        elif path.startswith("plm-web/"):
            targets.add("web")
        elif path.startswith("services/phone-watch/"):
            targets.update(("phone-watch", "phone-watch-worker"))
        elif path.startswith("scripts/"):
            unsupported.append(path)
        elif path.startswith("docker-compose") or path.startswith("compose."):
            unsupported.append(path)
        elif path.startswith(("docs/", ".github/", "tests/")) or path in {
            "README.md", "AGENTS.md", ".gitignore", "coding-rules.md",
            "coding-rules-system-implementation.md", "deploy-plm-production.py",
        }:
            continue
        else:
            unsupported.append(path)
    if unsupported:
        raise DeployError(
            "This update changes deployment/runtime paths that need a separate review before checkout: "
            + ", ".join(unsupported)
        )
    return sorted(targets), paths


def parse_db_url(value: str) -> tuple[str, int, str]:
    if not value.startswith("jdbc:"):
        raise DeployError("DB_URL is not a JDBC URL")
    parsed = urlsplit(value[5:])
    database = parsed.path.lstrip("/").split("/", 1)[0]
    if parsed.scheme != "postgresql" or not parsed.hostname or not database:
        raise DeployError("Could not parse PostgreSQL host/database from DB_URL")
    return parsed.hostname, parsed.port or 5432, database


def make_backup(backup_dir: Path, commit: str) -> Path:
    values = container_env("plm-server")
    for key in ("DB_URL", "DB_USERNAME", "DB_PASSWORD"):
        if not values.get(key):
            raise DeployError(f"Running plm-server has no {key}")
    host, port, database = parse_db_url(values["DB_URL"])
    if subprocess.run(["docker", "image", "inspect", "postgres:17-alpine"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        raise DeployError("postgres:17-alpine is not cached; refusing an unexpected image pull for the backup")
    backup_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(backup_dir, 0o700)
    name = f"plm-predeploy-{commit[:12]}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.dump"
    backup = backup_dir / name
    if backup.exists():
        raise DeployError("Backup path already exists: " + str(backup))
    env = dict(os.environ)
    env["PGPASSWORD"] = values["DB_PASSWORD"]
    try:
        run([
            "docker", "run", "--rm", "--network", "host", "--env", "PGPASSWORD",
            "-v", f"{backup_dir}:/out", "postgres:17-alpine", "pg_dump", "-Fc",
            "--no-owner", "--no-acl", "-h", host, "-p", str(port), "-U", values["DB_USERNAME"],
            "-d", database, "-f", f"/out/{name}",
        ], env=env)
        os.chmod(backup, 0o600)
        verification = subprocess.run(
            ["docker", "run", "--rm", "-v", f"{backup_dir}:/out:ro", "postgres:17-alpine", "pg_restore", "--list", f"/out/{name}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        if verification.returncode:
            raise DeployError("Database backup could not be read by pg_restore")
    except BaseException:
        backup.unlink(missing_ok=True)
        raise
    checksum = run(["sha256sum", str(backup)], capture=True).split()[0]
    print(f"Verified database backup: {backup} sha256={checksum}")
    return backup


def replace_image_refs(compose_file: Path, env_file: Path, replacements: dict[str, str]) -> None:
    counts = {old: 0 for old in replacements}
    contents = {path: path.read_text(encoding="utf-8") for path in (compose_file, env_file)}
    for old, new in replacements.items():
        for text in contents.values():
            counts[old] += text.count(old)
    missing = [old for old, count in counts.items() if count == 0]
    if missing:
        raise DeployError("Could not update image references in the active Compose/.env: " + ", ".join(missing))
    for path, text in contents.items():
        for old, new in replacements.items():
            text = text.replace(old, new)
        atomic_write(path, text, 0o600 if path == env_file else (path.stat().st_mode & 0o777))


def snapshot(directory: Path, compose_file: Path, env_file: Path, manifest: dict) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)
    shutil.copy2(compose_file, directory / "compose.yml")
    shutil.copy2(env_file, directory / ".env")
    os.chmod(directory / "compose.yml", 0o600)
    os.chmod(directory / ".env", 0o600)
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    os.chmod(directory / "manifest.json", 0o600)


def current_container_images_match(config: dict) -> None:
    for service, container in SERVICE_CONTAINERS.items():
        expected = config["services"][service]["image"]
        actual = container_inspect(container, "{{.Config.Image}}")
        if actual != expected:
            raise DeployError(f"{container} runs {actual}, Compose resolves {expected}; reconcile before deployment")


def config_images(config: dict) -> dict[str, str]:
    return {name: service["image"] for name, service in config["services"].items() if service.get("image")}


def http_status(url: str) -> str:
    return run(
        ["curl", "-sS", "-o", os.devnull, "-w", "%{http_code}", "--max-time", "20", url],
        capture=True,
    )


def http_json(url: str) -> tuple[int, dict]:
    fd, temporary = tempfile.mkstemp(prefix="plm-health-")
    os.close(fd)
    try:
        status_text = run(
            ["curl", "-sS", "-o", temporary, "-w", "%{http_code}", "--max-time", "20", url],
            capture=True,
        )
        body = Path(temporary).read_text(encoding="utf-8")
        try:
            status = int(status_text)
        except (TypeError, ValueError) as error:
            raise DeployError(f"HTTP check returned no valid status for {url}") from error
        try:
            payload = json.loads(body)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise DeployError(f"HTTP check returned invalid JSON for {url} (HTTP {status})") from error
        if not isinstance(payload, dict):
            raise DeployError(f"HTTP check returned a non-object response for {url} (HTTP {status})")
        return status, payload
    finally:
        Path(temporary).unlink(missing_ok=True)


def wait_for_backend_health(base_url: str, timeout_seconds: int = 120) -> None:
    deadline = time.monotonic() + timeout_seconds
    reported_wait = False
    while True:
        try:
            health_code, health_payload = http_json(f"{base_url}/api/v1/health")
            health_data = health_payload.get("data")
            if (
                health_code != 200
                or health_payload.get("code") != 0
                or not isinstance(health_data, dict)
                or health_data.get("status") != "UP"
            ):
                raise DeployError(f"Backend health check failed: HTTP {health_code}")

            database_code, database_payload = http_json(f"{base_url}/api/v1/health/db")
            database_data = database_payload.get("data")
            if (
                database_code != 200
                or database_payload.get("code") != 0
                or not isinstance(database_data, dict)
                or database_data.get("status") != "UP"
                or database_data.get("database") != "UP"
            ):
                raise DeployError(f"Backend database health check failed: HTTP {database_code}")
            return
        except DeployError:
            if time.monotonic() >= deadline:
                raise
            if not reported_wait:
                print("Waiting for backend health endpoints to become ready...", file=sys.stderr)
                reported_wait = True
            time.sleep(5)


def post_deploy_checks(root: Path, compose_file: Path, env_file: Path, targets: list[str]) -> None:
    for service, container in SERVICE_CONTAINERS.items():
        state = container_inspect(container, "{{.State.Status}}")
        if state != "running":
            raise DeployError(f"{container} is not running after deploy: {state}")
    config = compose_config(root, compose_file, env_file)
    port = config["services"]["web"].get("ports", [{}])[0].get("published", "8111")
    base_url = f"http://127.0.0.1:{port}"
    code = http_status(f"{base_url}/")
    if code != "200":
        raise DeployError(f"Web smoke check returned HTTP {code}")
    if "server" in targets:
        wait_for_backend_health(base_url)

        started_at = container_inspect("plm-server", "{{.State.StartedAt}}")
        logs = run(["docker", "logs", "--since", started_at, "plm-server"], capture=True)
        flyway_failure = re.search(
            r"(?im)(?:flyway|dbmigrate|migration).*?(?:error|exception|failed)",
            logs,
        )
        if flyway_failure:
            raise DeployError("Flyway reported a migration failure in the new backend logs")
        if "Started PlmApplication" not in logs:
            print(
                "Backend startup log marker was not found; health endpoints passed.",
                file=sys.stderr,
            )
    details = f"containers running, web HTTP {code}"
    if "server" in targets:
        details += ", backend and database healthy"
    print(f"Health checks passed: {details}.")


def prune_images(state_dir: Path) -> None:
    keep: set[str] = set()
    for name in ("current", "previous"):
        manifest_path = state_dir / name / "manifest.json"
        if manifest_path.exists():
            keep.update(json.loads(manifest_path.read_text()).get("images", {}).values())
    all_refs = run(["docker", "image", "ls", "--format", "{{.Repository}}:{{.Tag}}"], capture=True).splitlines()
    all_containers = run(["docker", "ps", "-aq"], capture=True).split()
    used_ids: set[str] = set()
    if all_containers:
        used_ids.update(run(["docker", "inspect", *all_containers, "--format", "{{.Image}}"], capture=True).splitlines())
    for ref in all_refs:
        if ref in keep or not IMAGE_TAG_PATTERN.fullmatch(ref):
            continue
        image_id = run(["docker", "image", "inspect", "--format", "{{.Id}}", ref], capture=True)
        if image_id not in used_ids:
            subprocess.run(["docker", "image", "rm", ref], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


def prune_backups(backup_dir: Path) -> None:
    files = sorted(backup_dir.glob("plm-predeploy-*.dump"), key=lambda item: item.stat().st_mtime, reverse=True)
    for path in files[2:]:
        path.unlink()


def load_environment() -> tuple[Path, Path, Path]:
    root = Path(os.environ.get("PLM_DEPLOY_ROOT", Path(__file__).resolve().parent)).resolve()
    project = container_inspect("plm-server", '{{index .Config.Labels "com.docker.compose.project"}}')
    working = Path(container_inspect("plm-server", '{{index .Config.Labels "com.docker.compose.project.working_dir"}}')).resolve()
    config_files = container_inspect("plm-server", '{{index .Config.Labels "com.docker.compose.project.config_files"}}')
    if project != PROJECT:
        raise DeployError(f"Expected running Compose project {PROJECT}, found {project}")
    if root != working:
        raise DeployError(f"Script checkout {root} is not the fixed Compose workdir {working}")
    if "," in config_files:
        raise DeployError("Running project still references multiple Compose files")
    compose_file = Path(config_files).resolve(strict=True)
    if compose_file.parent != root:
        raise DeployError("Active Compose file must be beside the fixed source checkout")
    tracked_compose = subprocess.run(
        ["git", "-C", str(root), "ls-files", "--error-unmatch", str(compose_file.relative_to(root))],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0
    if tracked_compose:
        raise DeployError("Active production Compose must be a server-local untracked file")
    env_file = compose_file.parent / ".env"
    if not env_file.is_file() or (env_file.stat().st_mode & 0o777) != 0o600:
        raise DeployError("Production .env must exist beside Compose with permissions 600")
    return root, compose_file, env_file


def infer_deployed_commit(root: Path, state_dir: Path) -> str:
    manifest = state_dir / "current" / "manifest.json"
    if manifest.is_file():
        state = json.loads(manifest.read_text())
        commit = state.get("commit")
        if commit:
            return commit
    image = container_inspect("plm-server", "{{.Config.Image}}")
    match = re.search(r"([0-9a-f]{12})$", image)
    if not match:
        raise DeployError("Cannot infer deployed Git commit from current image; initialize deployment state first")
    return git(root, "rev-parse", match.group(1) + "^{commit}")


def verify_project_labels(compose_file: Path, root: Path) -> None:
    for container in SERVICE_CONTAINERS.values():
        project = container_inspect(container, '{{index .Config.Labels "com.docker.compose.project"}}')
        workdir = Path(container_inspect(container, '{{index .Config.Labels "com.docker.compose.project.working_dir"}}')).resolve()
        files = container_inspect(container, '{{index .Config.Labels "com.docker.compose.project.config_files"}}')
        if project != PROJECT or workdir != root or Path(files) != compose_file:
            raise DeployError(f"Container {container} does not match the fixed single-file Compose project")
        if container_inspect(container, "{{.State.Status}}") != "running":
            raise DeployError(f"Container {container} is not running")


def ensure_checkout_identity(root: Path) -> None:
    branch = git(root, "branch", "--show-current")
    if branch != "main":
        raise DeployError(f"Fixed production checkout must be on main; found {branch}")
    remote = git(root, "remote", "get-url", "origin")
    if REMOTE_MATCH not in remote:
        raise DeployError("origin does not point to the expected DeepLinkPLM repository")


def ensure_clean_checkout(root: Path, compose_file: Path) -> None:
    dirty = git(root, "status", "--porcelain", "--untracked-files=no")
    permitted = {"docker-compose.baota.yml"}
    unexpected = []
    for line in dirty.splitlines():
        path = line[2:].lstrip()
        if path not in permitted:
            unexpected.append(line)
    if unexpected:
        raise DeployError("Tracked source files have local changes; refusing to pull:\n" + "\n".join(unexpected))


def initialize_current_snapshot(root: Path, compose_file: Path, env_file: Path, state_dir: Path, commit: str, config: dict, *, allow_commit_mismatch: bool = False) -> None:
    current = state_dir / "current"
    if current.exists():
        manifest_path = current / "manifest.json"
        if manifest_path.exists():
            previous = json.loads(manifest_path.read_text())
            if previous.get("commit") != commit and not allow_commit_mismatch:
                raise DeployError("Recorded current commit differs from image tag; reconcile deployment state before proceeding")
            return
    snapshot(current, compose_file, env_file, {"commit": commit, "services": [], "rollback_services": [], "images": config_images(config)})


def verify_current_snapshot(state_dir: Path, config: dict) -> None:
    manifest_path = state_dir / "current" / "manifest.json"
    if not manifest_path.is_file():
        raise DeployError("Deployment state manifest is missing")
    saved = json.loads(manifest_path.read_text()).get("images", {})
    active = config_images(config)
    differences = [
        service for service, image in active.items()
        if service in saved and saved[service] != image
    ]
    if differences:
        raise DeployError(
            "Saved current release does not match active Compose images for: "
            + ", ".join(differences)
            + ". Inspect/reconcile the interrupted release before deploying again."
        )


def update_current_commit(state_dir: Path, commit: str) -> None:
    manifest_path = state_dir / "current" / "manifest.json"
    if not manifest_path.is_file():
        raise DeployError("Cannot advance source state without a current deployment manifest")
    manifest = json.loads(manifest_path.read_text())
    manifest["commit"] = commit
    atomic_write(manifest_path, json.dumps(manifest, indent=2) + "\n", 0o600)


def rollback(root: Path, compose_file: Path, env_file: Path, state_dir: Path) -> None:
    active_config = compose_config(root, compose_file, env_file)
    validate_mounts_and_services(active_config)
    verify_project_labels(compose_file, root)
    current_container_images_match(active_config)
    current, previous = state_dir / "current", state_dir / "previous"
    manifest_path = previous / "manifest.json"
    if not manifest_path.is_file():
        raise DeployError("No previous deployment is retained")
    manifest = json.loads(manifest_path.read_text())
    if not manifest.get("rollback_safe", True):
        raise DeployError(
            "The previous backend release introduced database migrations. Automatic rollback is blocked; "
            "review Flyway compatibility and live writes first. Do not restore the dump blindly."
        )
    targets = manifest.get("rollback_services", [])
    if not targets:
        raise DeployError("Previous deployment has no rollback service list")
    config_backup = state_dir / ("rollback-current." + str(os.getpid()))
    current_manifest = json.loads((current / "manifest.json").read_text()) if (current / "manifest.json").is_file() else {}
    snapshot(config_backup, compose_file, env_file, current_manifest)
    shutil.copy2(previous / "compose.yml", compose_file)
    shutil.copy2(previous / ".env", env_file)
    os.chmod(env_file, 0o600)
    try:
        config = compose_config(root, compose_file, env_file)
        validate_mounts_and_services(config)
        for service in targets:
            run(["docker", "image", "inspect", config["services"][service]["image"]])
        run(compose_command(root, compose_file, env_file, "up", "-d", "--no-build", "--no-deps", "--force-recreate", "--wait", "--wait-timeout", "240", *targets))
        status = {name: container_inspect(container, "{{.State.Status}}") for name, container in SERVICE_CONTAINERS.items()}
        failed = [name for name, state in status.items() if state != "running"]
        if failed:
            raise DeployError("Containers not running after rollback: " + ", ".join(failed))
        post_deploy_checks(root, compose_file, env_file, targets)
        shutil.rmtree(current)
        os.replace(previous, current)
        os.replace(config_backup, previous)
        previous_manifest = json.loads((previous / "manifest.json").read_text())
        previous_manifest["rollback_safe"] = True
        (previous / "manifest.json").write_text(json.dumps(previous_manifest, indent=2) + "\n", encoding="utf-8")
        os.chmod(previous / "manifest.json", 0o600)
        print("Rollback completed. Database dumps/migrations were not restored or reversed.")
        prune_images(state_dir)
    except BaseException:
        shutil.copy2(config_backup / "compose.yml", compose_file)
        shutil.copy2(config_backup / ".env", env_file)
        shutil.rmtree(config_backup, ignore_errors=True)
        try:
            run(compose_command(root, compose_file, env_file,
                                "up", "-d", "--no-build", "--no-deps", "--force-recreate",
                                "--wait", "--wait-timeout", "240", *targets), capture=False)
        except Exception as restore_error:
            print("Could not restore containers after rollback failure: " + str(restore_error), file=sys.stderr)
        raise


def deploy(root: Path, compose_file: Path, env_file: Path, state_dir: Path, backup_dir: Path, *, adopt_current: bool = False) -> None:
    ensure_checkout_identity(root)
    if not compose_file.is_file():
        raise DeployError("Active Compose file disappeared")
    if subprocess.run(["docker", "compose", "up", "--help"], capture_output=True, text=True).returncode:
        raise DeployError("Could not inspect Docker Compose up options")
    help_text = subprocess.run(["docker", "compose", "up", "--help"], capture_output=True, text=True).stdout
    if "--wait" not in help_text:
        raise DeployError("Docker Compose lacks --wait; upgrade Compose before using this deploy script")

    before_config = compose_config(root, compose_file, env_file)
    validate_mounts_and_services(before_config)
    verify_project_labels(compose_file, root)
    current_container_images_match(before_config)
    deployed_commit = git(root, "rev-parse", "HEAD") if adopt_current else infer_deployed_commit(root, state_dir)
    initialize_current_snapshot(
        root,
        compose_file,
        env_file,
        state_dir,
        deployed_commit,
        before_config,
        allow_commit_mismatch=adopt_current,
    )
    verify_current_snapshot(state_dir, before_config)

    temp_root = Path(tempfile.mkdtemp(prefix="plm-deploy-check-", dir=state_dir))
    os.chmod(temp_root, 0o700)
    old_jar = temp_root / "running.jar"
    migration_archive = temp_root / "deployed-migrations"
    old_compose = temp_root / "compose.before.yml"
    old_env = temp_root / ".env.before"
    mutation_started = False
    container_update_started = False
    added: list[str] = []
    migration_hashes: dict[str, str] = {}
    migrations_restored = False
    try:
        run(["docker", "cp", "plm-server:/app/app.jar", str(old_jar)])
        migration_dir = root / "plm-server/src/main/resources/db/migration"
        migration_hashes = archive_running_migrations(old_jar, migration_archive)
        restore_tracked_migrations_for_pull(root, migration_dir, migration_archive, migration_hashes)
        ensure_clean_checkout(root, compose_file)
        print("Fetching origin/main. Enter your GitHub username and Personal Access Token if prompted.")
        fetched_pin = os.environ.pop("PLM_DEPLOY_FETCHED_COMMIT", None)
        if fetched_pin:
            remote_commit = git(root, "rev-parse", "origin/main")
            if remote_commit != fetched_pin:
                raise DeployError("Fetched main changed while restarting the updated deploy script; retry deployment")
        else:
            git_env = dict(os.environ)
            git_env["GIT_TERMINAL_PROMPT"] = "1"
            git_env.pop("GIT_ASKPASS", None)
            git_env.pop("SSH_ASKPASS", None)
            run([
                "git", "-C", str(root), "-c", "credential.helper=", "fetch", "--progress", "origin",
                "+refs/heads/main:refs/remotes/origin/main",
            ], capture=False, env=git_env)
            remote_commit = git(root, "rev-parse", "origin/main")
        checkout_commit = git(root, "rev-parse", "HEAD")
        if remote_commit == deployed_commit and not adopt_current:
            print("Already deployed:", remote_commit[:12])
            return
        if subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", checkout_commit, remote_commit]).returncode:
            raise DeployError("The fixed checkout contains unpublished commits; reconcile it with origin/main before deployment")
        if subprocess.run(["git", "-C", str(root), "merge-base", "--is-ancestor", deployed_commit, remote_commit]).returncode:
            raise DeployError("origin/main is not a fast-forward from the deployed commit")
        targets, changed_paths = classify_changes(root, deployed_commit, remote_commit)
        if adopt_current:
            targets = ["server", "master-data-sync", "web", "phone-watch", "phone-watch-worker"]
            changed_paths = ["<adopt-current> full application baseline"]
        added_migrations = git(
            root, "diff", "--name-status", deployed_commit, remote_commit,
            "--", "plm-server/src/main/resources/db/migration",
        )
        migration_additions = [
            line.split("\t")[-1]
            for line in added_migrations.splitlines()
            if line.startswith("A\t")
        ]
        collisions = [
            path.rsplit("/", 1)[-1]
            for path in migration_additions
            if path.rsplit("/", 1)[-1] in migration_hashes
        ]
        if collisions:
            raise DeployError("Incoming migrations reuse already deployed version filenames: " + ", ".join(collisions))
        if not targets:
            print("Changes:", ", ".join(changed_paths))
            print("No runtime service changes detected.")
            answer = input("Fast-forward main without rebuilding services? [y/N] ").strip().lower()
            if answer not in ("y", "yes"):
                print("Cancelled. Only origin/main was fetched; source and services are unchanged.")
                return
            run(["git", "-C", str(root), "merge", "--ff-only", remote_commit], capture=False)
            restore_running_migrations(migration_dir, migration_archive, migration_hashes)
            migrations_restored = True
            if hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != SCRIPT_HASH_AT_START:
                print("Deployment tool updated; restarting with the newly fetched script.")
                shutil.rmtree(temp_root, ignore_errors=True)
                os.environ["PLM_DEPLOY_FETCHED_COMMIT"] = remote_commit
                os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]])
            update_current_commit(state_dir, remote_commit)
            print("Fast-forwarded documentation-only changes; no services were restarted.")
            return
        print("Changes:", ", ".join(changed_paths))
        print("Services to update:", ", ".join(targets))
        if migration_additions:
            print("New Flyway migrations to be applied on backend startup:")
            for migration in migration_additions:
                print("  " + migration.rsplit("/", 1)[-1])
        elif "server" in targets:
            print("New Flyway migrations: none")
        answer = input("Proceed with fast-forward, backup/build, and service replacement? [y/N] ").strip().lower()
        if answer not in ("y", "yes"):
            print("Cancelled. Only origin/main was fetched; source and services are unchanged.")
            return
        # The active script reloads a changed deployment tool after fast-forward.

        short = remote_commit[:12]
        date_tag = datetime.now().strftime("%Y%m%d")
        new_tag = f"{date_tag}-main-{short}"
        replacements: dict[str, str] = {}
        old_images = config_images(before_config)
        for service in targets:
            old = old_images[service]
            replacements[old] = old.rsplit(":", 1)[0] + ":" + new_tag

        run(["git", "-C", str(root), "merge", "--ff-only", remote_commit], capture=False)
        restore_running_migrations(migration_dir, migration_archive, migration_hashes)
        migrations_restored = True
        actual_commit = git(root, "rev-parse", "HEAD")
        if actual_commit != remote_commit:
            raise DeployError("Fast-forward result differs from the inspected origin/main commit")
        if hashlib.sha256(Path(__file__).read_bytes()).hexdigest() != SCRIPT_HASH_AT_START:
            print("Deployment tool updated; restarting with the newly fetched script before building services.")
            shutil.rmtree(temp_root, ignore_errors=True)
            os.environ["PLM_DEPLOY_FETCHED_COMMIT"] = remote_commit
            os.execv(sys.executable, [sys.executable, str(Path(__file__).resolve()), *sys.argv[1:]])
        added = verify_migration_history(old_jar, root / "plm-server/src/main/resources/db/migration")
        if added:
            print("New Flyway migrations:", ", ".join(added))

        shutil.copy2(compose_file, old_compose)
        shutil.copy2(env_file, old_env)
        os.chmod(old_compose, 0o600)
        os.chmod(old_env, 0o600)
        mutation_started = True
        replace_image_refs(compose_file, env_file, replacements)
        new_config = compose_config(root, compose_file, env_file)
        validate_mounts_and_services(new_config)
        for service in targets:
            new_ref = replacements.get(old_images[service])
            if new_ref and new_config["services"][service]["image"] != new_ref:
                raise DeployError(f"Image tag did not resolve as expected for {service}")
        current_container_images_match(before_config)

        build_targets = sorted(set(targets))
        run(["docker", "compose", "--progress", "plain", "--profile", "integrations",
             "--env-file", str(env_file), "--project-directory", str(root), "-p", PROJECT,
             "-f", str(compose_file), "build", "--pull=false", *build_targets], capture=False)

        backup = None
        if "server" in targets:
            backup = make_backup(backup_dir, remote_commit)

        current = state_dir / "current"
        previous = state_dir / "previous"
        old_manifest = json.loads((current / "manifest.json").read_text())
        old_manifest["rollback_services"] = targets
        old_manifest["rollback_safe"] = not bool("server" in targets and added)
        previous_new = temp_root / "previous.new"
        snapshot(previous_new, old_compose, old_env, old_manifest)

        container_update_started = True
        run(compose_command(root, compose_file, env_file,
                            "up", "-d", "--no-build", "--no-deps", "--force-recreate",
                            "--wait", "--wait-timeout", "240", *targets), capture=False)
        post_deploy_checks(root, compose_file, env_file, targets)

        current_manifest = {
            "commit": remote_commit,
            "services": targets,
            "rollback_services": targets,
            "rollback_safe": not bool("server" in targets and added),
            "images": config_images(new_config),
            "backup": str(backup) if backup else None,
            "deployed_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        }
        current_new = temp_root / "current.new"
        snapshot(current_new, compose_file, env_file, current_manifest)
        older_previous = temp_root / "previous.older"
        current_old = temp_root / "current.old"
        current_moved = False
        previous_moved = False
        previous_new_installed = False
        current_new_installed = False
        try:
            if current.exists():
                os.replace(current, current_old)
                current_moved = True
            if previous.exists():
                os.replace(previous, older_previous)
                previous_moved = True
            os.replace(previous_new, previous)
            previous_new_installed = True
            os.replace(current_new, current)
            current_new_installed = True
        except BaseException:
            if current_new_installed and current.exists():
                shutil.rmtree(current, ignore_errors=True)
            if current_moved and current_old.exists():
                if current.exists():
                    shutil.rmtree(current, ignore_errors=True)
                os.replace(current_old, current)
            if previous_new_installed and previous.exists():
                shutil.rmtree(previous, ignore_errors=True)
            if previous_moved and older_previous.exists():
                os.replace(older_previous, previous)
            raise
        for retired in (current_old, older_previous):
            if retired.exists():
                try:
                    shutil.rmtree(retired)
                except OSError as cleanup_error:
                    print("Deployment succeeded, but a retired state snapshot could not be cleaned: " + str(retired), file=sys.stderr)
        try:
            prune_images(state_dir)
        except Exception as cleanup_error:
            print("Deployment succeeded, but old image-tag cleanup needs review: " + str(cleanup_error), file=sys.stderr)
        try:
            prune_backups(backup_dir)
        except Exception as cleanup_error:
            print("Deployment succeeded, but old backup cleanup needs review: " + str(cleanup_error), file=sys.stderr)
        print("Deployment completed:", remote_commit[:12])
        print("Rollback command: python3 deploy-plm-production.py --rollback")
        if backup:
            print("Verified pre-deploy backup:", backup)
    except BaseException as error:
        if mutation_started and old_compose.is_file() and old_env.is_file():
            if container_update_started and "server" in targets and added:
                print(
                    "Deployment failed after a backend migration may have run. The script is leaving the new Compose/image selection in place for inspection and will not downgrade the backend or database.",
                    file=sys.stderr,
                )
                raise error
            shutil.copy2(old_compose, compose_file)
            shutil.copy2(old_env, env_file)
            os.chmod(env_file, 0o600)
            if container_update_started:
                print("Deployment failed; trying to restore the prior application images.", file=sys.stderr)
                try:
                    run(compose_command(root, compose_file, env_file,
                                        "up", "-d", "--no-build", "--no-deps", "--force-recreate",
                                        "--wait", "--wait-timeout", "240", *targets), capture=False)
                    print("Prior app containers were recreated. Database migrations, if applied, were not reversed.", file=sys.stderr)
                except Exception as rollback_error:
                    print("Automatic app rollback failed: " + str(rollback_error), file=sys.stderr)
            try:
                prune_images(state_dir)
            except Exception as cleanup_error:
                print("Could not prune failed-build image tags: " + str(cleanup_error), file=sys.stderr)
            raise error
        raise
    finally:
        if migration_hashes and not migrations_restored:
            restore_running_migrations(root / "plm-server/src/main/resources/db/migration", migration_archive, migration_hashes)
        # Keep the check directory only while executing; deployment state is stored separately.
        shutil.rmtree(temp_root, ignore_errors=True)


def main() -> int:
    global COMPOSE_FILE, ENV_FILE
    os.umask(0o077)
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print("Usage: python3 deploy-plm-production.py [--check|--rollback|--adopt-current]")
        return 0
    if len(sys.argv) > 1 and sys.argv[1] not in ("--check", "--rollback", "--adopt-current"):
        print("Usage: python3 deploy-plm-production.py [--check|--rollback|--adopt-current]", file=sys.stderr)
        return 2
    mode = sys.argv[1] if len(sys.argv) > 1 else "deploy"
    root = Path(os.environ.get("PLM_DEPLOY_ROOT", Path(__file__).resolve().parent)).resolve()
    plm_home = Path(os.environ.get("PLM_HOME", root.parent.parent)).resolve()
    state_dir = Path(os.environ.get("PLM_DEPLOY_STATE_DIR", plm_home / "deploy-state"))
    if mode != "--check":
        state_dir.mkdir(parents=True, exist_ok=True)
        os.chmod(state_dir, 0o700)
    lock_stream = None
    if mode != "--check":
        lock_path = state_dir / ".deploy.lock"
        lock_stream = lock_path.open("a+")
        os.chmod(lock_path, 0o600)
        if fcntl is not None:
            try:
                fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise DeployError("Another PLM deployment/rollback is already running") from error
    load_root, COMPOSE_FILE, ENV_FILE = load_environment()
    if load_root != root:
        raise DeployError("Script root and active production checkout differ")
    if mode == "--check":
        config = compose_config(root, COMPOSE_FILE, ENV_FILE)
        validate_mounts_and_services(config)
        verify_project_labels(COMPOSE_FILE, root)
        current_container_images_match(config)
        print("Production preflight passed. No source, config, containers, or database were changed.")
        return 0
    if mode == "--rollback":
        rollback(root, COMPOSE_FILE, ENV_FILE, state_dir)
        return 0
    deploy(
        root,
        COMPOSE_FILE,
        ENV_FILE,
        state_dir,
        plm_home / "backups",
        adopt_current=mode == "--adopt-current",
    )
    if lock_stream is not None:
        lock_stream.close()
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except DeployError as error:
        print("DEPLOY STOPPED:", error, file=sys.stderr)
        sys.exit(1)
