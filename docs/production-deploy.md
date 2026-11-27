# Production Deployment

The production deployment uses one fixed Git checkout and one server-local Compose file with its adjacent `.env`. The operator command is:

```bash
python3 deploy-plm-production.py
```

The script runs in the foreground. It fetches `origin/main` so it can inspect the change set before updating the checkout, then fast-forwards with `git merge --ff-only`. GitHub may prompt for the account name and Personal Access Token during fetch. The script disables Git credential helpers for that fetch, so it does not store the token. After showing the affected services, the script asks before changing the checkout, building, or replacing containers. If the deployment script itself is part of the fetched commit, it reloads the updated script before service builds.

## Preconditions

- Run from the fixed production checkout on branch `main`, with `origin` pointing to `jdb123-JDB/DeepLinkPLM`.
- The running `yuewei-plm` containers must all use the same single Compose file and checkout directory.
- The active Compose file is server-local and untracked. Its `.env` is beside it and has mode `600`.
- The Compose model includes `redis`, `server`, `web`, `phone-watch`, `phone-watch-worker`, and `master-data-sync` in the `integrations` profile. Each service has an explicit image; service image tags may differ when a production image overlay is in use.
- Existing bind sources and Docker named volumes must remain available. The script refuses missing bind sources or mismatches between running containers and Compose.
- Tracked files in the checkout must be clean. The server-local `docker-compose.baota.single.v2.yml`, `.env`, and private ignored migration files are not committed.

The active `docker-compose.baota.single.v2.yml` is server-local. Its legacy tracked `docker-compose.baota.yml` may remain locally modified while the single-file production Compose is active. If an incoming commit changes Compose source, deployment stops before fast-forwarding for a deliberate production-config review.

Run a read-only preflight with:

```bash
python3 deploy-plm-production.py --check
```

When the existing production images do not contain a Git revision, run the one-time adoption mode after the image-overlay normalization has passed `--check`:

```bash
python3 deploy-plm-production.py --adopt-current
```

This treats the current GitHub `main` checkout as the new source baseline, backs up the database, builds and replaces `server`, `master-data-sync`, `web`, `phone-watch`, and `phone-watch-worker`, then records the per-service image map. It is intentionally explicit because it can replace unpublished code that exists only in an old production image. Redis is not rebuilt. The command asks for confirmation after showing the target commit and affected services.

## One-Time Image Overlay Normalization

If the running containers have different historical `compose.images.yml` files, do not pass those files together: later image overrides can silently replace the backend image. First create one server-local override from the images that are actually running, then produce one merged Compose file and recreate the six services once.

For the currently inspected production state, the image map is:

```yaml
services:
  server:
    image: yuewei-plm-server:20261120-mold-sales-subsecond-r5
  master-data-sync:
    image: yuewei-plm-server:20261120-main-8b3ce75ed8d0
  web:
    image: yuewei-plm-web:20261120-mold-sales-speed-r3
  phone-watch:
    image: yuewei-plm-phone-watch:20261120-main-8b3ce75ed8d0
  phone-watch-worker:
    image: yuewei-plm-phone-watch:20261120-main-8b3ce75ed8d0
```

Save it as a server-local `compose.images.current.yml` beside the active base Compose file, merge it with `docker compose config --no-interpolate --no-env-resolution --no-path-resolution`, and validate the resulting image/mount list before recreating containers. The merged file must remain untracked, have mode `600`, and sit beside the production `.env`. Do not delete the two historical overlay files until the six containers have been recreated and their Compose labels show only the merged file.

## Deploy Behavior

The script classifies changes under `plm-server/`, `plm-web/`, and `services/phone-watch/`, then builds and replaces only affected services. A backend update replaces both `server` and `master-data-sync`, even when those services currently use different image tags; each target receives the new release tag. Redis is not restarted during application updates.

A host-side lock prevents two deploy/rollback script runs from racing. The `--check` preflight is read-only.

It stops before checkout if Compose files or bind-mounted `scripts/` change; those changes need a reviewed runtime-config update. Before fetch, the script archives the exact migration resources from the running JAR, restores only tracked migration paths to the Git version so fast-forward can proceed, and reapplies the deployed bytes after checkout. This preserves the private and byte-different migration files found in the current production JAR without committing them. Flyway migrations may only be added. Before a server update, the script compares existing migration files byte-for-byte with the running JAR, then makes and verifies a custom-format PostgreSQL backup before replacing containers. It never restores a database dump automatically.

The active Compose file and `.env` are retagged to the new commit's image tags. BuildKit cache is left intact; it is shared by service builds and may also benefit other projects on the host, so it is not a per-release artifact that can safely be trimmed to two versions. The script does not run Docker-wide prune commands.

After recreating services, the script checks that every production container is running, the Web root returns HTTP 200, and the backend's unauthenticated `/api/v1/health` and `/api/v1/health/db` endpoints report `UP`. It retries those backend checks for up to two minutes to allow the reverse proxy and application to settle after container replacement. It also scans logs from the current backend container start for explicit Flyway migration failures. A particular Spring startup log line is diagnostic only, so a slow build or a changed log format cannot trigger a false rollback when the health endpoints are healthy.

## Retention and Rollback

`/var/lib/yuewei-plm/deploy-state/current` and `previous` retain the active and prior Compose files, protected environment snapshots, commit information, affected service list, and image references. The script prunes older `yuewei-plm` application image tags only after a successful deploy and only when no container uses them. After a successful backend deploy, only the two newest `plm-predeploy-*.dump` files are retained. A failed deployment does not rotate the saved Compose/image versions or remove earlier database backups.

Rollback the last deployment with:

```bash
python3 deploy-plm-production.py --rollback
```

Rollback recreates only services changed by that deployment and keeps the database as-is. If the deployment added Flyway migrations, automatic rollback is blocked because the old JAR may not validate against the newer schema. If a deployment fails after starting such a backend replacement, the script leaves the new Compose/image selection for inspection instead of downgrading automatically. Review application compatibility and live writes before choosing a recovery action.

The fixed source checkout does not create a directory per deployment. The script also does not delete the older `20261017-c856` directory, because current runtime bind mounts still use its Nginx and integration files.

Run the script tests on a development machine with:

```bash
python -m unittest discover -s tests -p test_deploy_plm_production.py -v
```
