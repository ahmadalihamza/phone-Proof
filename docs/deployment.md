# Local and hosted deployment

## Local Python

From repository root (PowerShell):

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -c backend\requirements-tested.txt -r backend\requirements-dev.txt
Copy-Item backend\.env.example backend\.env
Set-Location backend
..\.venv\Scripts\python.exe -m alembic upgrade head
..\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

For two phones on the same LAN, set PUBLIC_REPORT_BASE_URL=http://COMPUTER_LAN_IP:8000 before startup, and set that host plus `/api/v1/` in each debug app's Account connection screen. Allow the selected private-network port in the host firewall. Never use local HTTP for production or real customer data.

## Docker with PostgreSQL and Redis

```powershell
Copy-Item infra\.env.example infra\.env
# Replace the example passwords/secrets and set the public report origin.
docker compose --env-file infra/.env -f infra/docker-compose.yml up --build -d
docker compose --env-file infra/.env -f infra/docker-compose.yml logs api
```

The API performs migrations before serving; PostgreSQL and Redis health checks gate startup. The API container probes `/ready` using an allowed Host header; `/health` remains a DB smoke check. Images persist in a named volume in local development. The optional `ml` profile starts a worker only after its dependencies and actual validated model image/weights are configured; the base worker image deliberately does not include generic YOLO weights.

## Selected hosting path: Render

The repository already included Render; it remains the proposed host. `render.yaml` now describes an API, PostgreSQL 16 and a persistent Redis-compatible Key Value service in Singapore. Database and cache public access are blocked. Plans in the template are paid starting configurations, subject to load testing and account review. Automatic deployment is off. No account, paid resource or public deployment was created. The file passes the [published Render schema](https://render.com/schema/render.yaml.json); account-side validation and deployment remain separate. See [Blueprints](https://render.com/docs/blueprint-spec), [Postgres](https://render.com/docs/postgresql-creating-connecting) and [Key Value](https://render.com/docs/key-value).

1. Connect the reviewed Git repository to an authorized Render workspace. Review the proposed resources and charges before creating the Blueprint. Use separate staging and production resources.
2. Supply a stable random `JWT_SECRET` of at least 48 characters and a separate 64-hex-character `MFA_ENCRYPTION_KEY` through secret management. Do not use Blueprint `generateValue` for the hex key. Both must be backed up.
3. Generate an Ed25519 key with `python tools/create_signing_key.py PRIVATE_PATH`. Add it as a Render secret file named `report-signing.pem`; the configured path is `/etc/secrets/report-signing.pem`. Preserve this key across deployments. The private file is intentionally absent from YAML/Git. See [Render secrets](https://render.com/docs/configure-environment-variables).
4. Use a private AWS S3 bucket, initially in the same region as the API. Set `S3_BUCKET`, `S3_REGION` and protected access credentials; leave `S3_ENDPOINT` empty for AWS. Enable [Block Public Access](https://docs.aws.amazon.com/AmazonS3/latest/userguide/access-control-block-public-access.html). The application requests [AES256 server-side encryption](https://docs.aws.amazon.com/AmazonS3/latest/userguide/serv-side-encryption.html). Grant only the required object read/write/delete permissions, including `healthcheck/*`. Other S3-compatible providers require their own acceptance with these exact operations; local MinIO success does not certify an untested provider.
5. Set `PUBLIC_REPORT_BASE_URL` to the owned HTTPS origin and `ALLOWED_HOSTS` to its exact hostname plus the Render hostname needed for platform probes. Set `TRUSTED_PROXY_IPS` to verified ingress proxy addresses/networks. Confirm two separate clients retain distinct rate-limit identities. Do not guess a proxy allowlist or turn on blanket trust.
6. Deploy staging, confirm `/live` and `/ready`, then register/login, sync real observations and finish the two-phone report/revocation journey. The managed PostgreSQL URL is normalized to the installed psycopg driver automatically. The Render pre-deploy command performs migrations before API startup. See [Docker deployment](https://render.com/docs/docker).
7. Add supervised workers using the same image, database, stable JWT/MFA keys, public origin and signing-key secret file. Mail command: `python -m app.mail_delivery --watch --interval 60`. Payment reconciliation command: `python -m app.reconcile_payments --watch --interval 60`. Enable mail only after SMTP acceptance, and payment reconciliation only after a real provider adapter passes its sandbox contract. Compose contains equivalent opt-in profiles.

`/live` checks the process. `/ready` checks database, Redis, storage write/read/delete and signing, with bounded probe timeouts. Legacy `/health` remains a database smoke endpoint. Configure load-balancer health against `/ready`; monitor worker exits and dependency failures separately. `HEALTHCHECK_HOST` can override the internal Host header when needed.

Enable encrypted DB backups/PITR, object versioning/lifecycle, and independent signing-key backups. Restore all three into an isolated hosted environment and verify historical report signatures before releasing. A local synthetic PostgreSQL dump/restore drill is available through `tools/verify_services.py --postgres-bin PATH --backup-restore`; it does not establish hosted RPO/RTO. Target RPO 24 hours/RTO 4 hours remains an operational target.

No production deployment occurred in this workspace: host credentials, domain configuration, storage and release signing were not supplied. Do not promote the development runtime or test fixtures into customer reports.

## Email recovery and verification

Set `SMTP_HOST`, `SMTP_FROM`, `SMTP_PORT` and, when required, `SMTP_USERNAME`/`SMTP_PASSWORD` through the secret manager. SMTP always verifies TLS certificates: port 587 uses STARTTLS by default; use `SMTP_IMPLICIT_TLS=true` for an implicit TLS endpoint such as port 465. The API queues an encrypted message in the account transaction and never sends email during that transaction. Both API and mail worker must share the same stable `MFA_ENCRYPTION_KEY`; protect it with backups and do not replace it while encrypted messages or administrator seeds still depend on it.

From the backend directory, deliver one bounded batch with `python -m app.mail_delivery`, or run `python -m app.mail_delivery --watch --interval 60` as a separate supervised process. Compose enables the optional worker with `docker compose --env-file infra/.env -f infra/docker-compose.yml --profile mail up --build -d`. Hosted containers need the same command, database, secrets and signing-key mount as the API. Do not start the worker until SMTP is configured. Monitor pending/retry/failed queue counts and worker exits; SMTP acceptance does not guarantee arrival in an inbox. Rehearse delivery, expiry and resend with a controlled staging inbox before enabling customer recovery.

## Payment reconciliation

The opt-in `payments` Compose profile runs `python -m app.reconcile_payments --watch --interval 60`. Run a bounded local batch from backend with `python -m app.reconcile_payments --limit 100`. It uses a separate database transaction per candidate and reuses the immutable transaction ID for uncertain provider initiation. Monitor pending age, attempt errors, fulfillment review and worker failures. No real adapter is installed; starting the worker does not enable merchant payments. See payment-readiness.md for credentials, protocol implementation, sandbox acceptance and historical-record handling. The Compose profile has not been started against production services here.
