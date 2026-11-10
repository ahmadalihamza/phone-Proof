Run the ordinary suite from `backend` with `python -m pytest -q`. Each process
creates its own temporary SQLite database, signing key and media directory.

`test_postgresql.py` runs only when `TEST_POSTGRES_URL` names a dedicated
PostgreSQL test database (for example,
`postgresql+psycopg://phoneproof:acceptance-only@localhost:5432/phoneproof_test`).
Run it with `python -m pytest -q tests/test_postgresql.py`.

The PostgreSQL suite creates a random `phoneproof_acceptance_...` schema, runs
the real Alembic upgrade and drift check there, exercises overlapping account and
inspection workflows on separate connections, and drops only that schema in
cleanup. The database user needs schema-creation permission. CI supplies a
disposable PostgreSQL 16 service. A skipped local run does not validate these
PostgreSQL guarantees.

`test_object_storage.py` uses a real S3-compatible service when
`TEST_S3_ENDPOINT`, `TEST_S3_ACCESS_KEY`, and `TEST_S3_SECRET_KEY` are supplied.
Each test owns and removes a random private bucket. The service must support the
application's AES256 server-side encryption requirement. It tests an accepted
24 MP image whose sanitized JPEG exceeds the upload byte limit, metadata
removal, anonymous access denial, read overflow rejection, and production
storage readiness cleanup.

From the repository root, `python tools/verify_services.py --postgres-bin
.tools/postgresql-16.15/pgsql/bin` starts an isolated portable PostgreSQL server
and executes the real PostgreSQL tests. `--object-store .tools/minio/minio.exe`
similarly starts a portable MinIO executable with temporary encryption keys and
runs the real S3 tests. Both options can be combined. The helper binds services
only to loopback on free ports, records logs/JUnit output, stops the owned
processes, and removes their temporary data. No existing service is contacted.

Add `--backup-restore` to the PostgreSQL command to migrate an empty database,
create a visibly synthetic signed report, dump it with `pg_dump`, restore into a
fresh database with `pg_restore`, and verify the exact hash/signature, ownership,
quota record and raw-SQL report immutability guards. A separately copied fixture
signing key is checked and both throwaway private keys are removed afterward.
This rehearses the local mechanism, not production PITR, storage restoration or
the deployment's RPO/RTO.

Payment acceptance tests use development simulation or in-memory adapters and
do not establish merchant integration readiness. `test_media_storage.py` uses a
fake S3 client; `test_object_storage.py` is the separate opt-in real-service
check. Acceptance against the configured deployment bucket remains necessary.
