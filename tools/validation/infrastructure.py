"""Use existing Docker infrastructure; mutate only databases created by this run."""

from pathlib import Path
import hashlib
import subprocess
import uuid


PACKAGES = ("util", "catalog", "account", "analytics", "beatrix", "subscription",
            "payment", "invoice", "entitlement", "usage", "meter", "tenant")


def ddl_files(root, testing):
    files = [root / "killbill/util/src/main/resources/org/killbill/billing/util/ddl-postgresql.sql"]
    for package in PACKAGES:
        names = ("ddl_test.sql", "ddl.sql") if testing else ("ddl.sql",)
        for name in names:
            # Match DBTestingHelper: test DDL precedes production DDL for each package.
            for module in (root / "killbill").iterdir():
                for source in ("main", "test") if testing else ("main",):
                    path = module / f"src/{source}/resources/org/killbill/billing/{package}/{name}"
                    if path.is_file():
                        files.append(path)
    return files


class Infrastructure:
    def __init__(self, options, output, record):
        self.options, self.output, self.record = options, output, record
        self.owned = set()

    def postgres(self, database, *arguments, sql=None):
        command = ["docker", "exec", "-i", self.options.postgres_container, "psql",
                   "-X", "-v", "ON_ERROR_STOP=1", "-U", self.options.db_user,
                   "-d", database, *arguments]
        return subprocess.run(command, input=sql, text=True, capture_output=True,
                              check=True, timeout=120).stdout.strip()

    def probe(self):
        version = self.postgres("postgres", "-Atc", "SELECT version()")
        redis = subprocess.run(["docker", "exec", self.options.redis_container,
                                "redis-cli", "PING"], text=True, capture_output=True,
                               check=True, timeout=15).stdout.strip()
        if redis != "PONG":
            raise RuntimeError("Redis PING did not return PONG")
        self.record("infrastructure", postgres=version, redis=redis)

    def create(self, root, label, testing=False):
        database = "kb_accept_" + uuid.uuid4().hex
        self.postgres("postgres", "-c", f'CREATE DATABASE "{database}" TEMPLATE template0')
        self.owned.add(database)
        self.record("database-created", purpose=label, database=database)
        files = ddl_files(root, testing)
        manifest = [{"path": str(p.relative_to(root)),
                     "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in files]
        sql = "\n".join(p.read_text() for p in files)
        try:
            self.postgres(database, "--single-transaction", sql=sql)
        except subprocess.CalledProcessError as error:
            (self.output / f"{label}-ddl-error.log").write_text(error.stderr)
            raise RuntimeError(f"DDL failed for {database}; see {label}-ddl-error.log") from error
        count = self.postgres(database, "-Atc", "SELECT count(*) FROM information_schema.tables "
                              "WHERE table_schema='public' AND table_type='BASE TABLE'")
        self.record("schema", purpose=label, database=database, tables=int(count), ddl=manifest)
        return database

    def drop(self, database):
        if database not in self.owned:
            raise ValueError("Refusing to drop a database not created by this run")
        self.postgres("postgres", "-c", f'DROP DATABASE "{database}"')
        self.owned.remove(database)
        self.record("database-dropped", database=database)
