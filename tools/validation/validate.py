#!/usr/bin/env python3
"""Reproducible local release acceptance using existing Docker PostgreSQL/Redis."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import uuid
import zipfile

from infrastructure import Infrastructure
from war_node import WarNode, check_queue_shutdown, stop_process
from http_smoke import business_flow, check_plugin, verify_business

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
SKIPS = [f"-Dcheck.skip-{name}=true" for name in (
    "spotbugs", "dependency", "dependency-scope", "dependency-versions",
    "duplicate-finder", "enforcer", "rat")]
JVM = ["-Xmx1024m", "-Duser.language=en", "-Duser.country=US"] + [
    f"--add-opens=java.base/{name}=ALL-UNNAMED" for name in (
        "java.lang", "java.io", "java.util", "java.base")]


def options():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-container", default="dev-shared-postgres")
    parser.add_argument("--redis-container", default="dev-shared-redis")
    parser.add_argument("--db-user", default="dev")
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", default=5432, type=int)
    parser.add_argument("--maven", default="/Users/wayne/maven/apache-maven-3.6.3/bin/mvn"
                        if Path("/Users/wayne/maven/apache-maven-3.6.3/bin/mvn").is_file() else shutil.which("mvn"))
    parser.add_argument("--java-home", help="JDK 21; defaults to macOS java_home -v 21 or JAVA_HOME")
    parser.add_argument("--skip-build", action="store_true", help="Use existing local build; explicitly reported as skipped")
    parser.add_argument("--keep-databases", action="store_true")
    parser.add_argument("--output", type=Path, help="New directory, defaults to var/release-validation/<timestamp>-<id>")
    result = parser.parse_args()
    if not result.java_home and sys.platform == "darwin":
        result.java_home = subprocess.check_output(["/usr/libexec/java_home", "-v", "21"], text=True).strip()
    if not result.java_home:
        result.java_home = os.environ.get("JAVA_HOME")
    if not result.java_home or not result.maven:
        parser.error("JDK 21 JAVA_HOME and a real Maven executable are required")
    result.password = os.environ.get("KB_VALIDATION_DB_PASSWORD", "dev")
    if any(char.isspace() for char in result.password):
        parser.error("Test fork password must not contain whitespace")
    return result


class Validation:
    def __init__(self, args):
        self.args = args
        run_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8]
        self.output = (args.output or ROOT / "var/release-validation" / run_id).resolve()
        if not args.skip_build and self.output.is_relative_to(ROOT) and "target" in self.output.relative_to(ROOT).parts:
            raise ValueError("Build output must be outside Maven target directories")
        self.output.mkdir(parents=True, mode=0o700, exist_ok=False)
        self.report = {"status": "running", "stages": [], "static_checks_skipped": SKIPS}
        self.environment = {**os.environ, "JAVA_HOME": str(Path(args.java_home).resolve())}
        self.environment["PATH"] = str(Path(args.java_home) / "bin") + os.pathsep + self.environment.get("PATH", "")
        # An inherited Java/Maven option must not silently change the target DB/configuration.
        for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "MAVEN_OPTS", "MAVEN_ARGS"):
            self.environment.pop(key, None)
        self.server = None
        self.infrastructure = Infrastructure(args, self.output, self.record)
        self.record("source", commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                    dirty=bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT)))

    def record(self, stage, **data):
        self.report["stages"].append({"stage": stage, **data})
        self.save()
        print(stage + (": " + data.get("status", "") if "status" in data else ""), flush=True)

    def save(self):
        self.report["retained_databases"] = sorted(self.infrastructure.owned) if hasattr(self, "infrastructure") else []
        temporary = self.output / "report.json.tmp"
        temporary.write_text(json.dumps(self.report, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(self.output / "report.json")

    def command(self, stage, command, timeout=1200):
        safe = [re.sub(r"localDb.password=\S+", "localDb.password=<redacted>", str(c)) for c in command]
        log = self.output / (stage + ".log")
        self.record(stage, status="running", command=safe, log=str(log))
        with log.open("w") as output:
            process = subprocess.Popen(command, cwd=ROOT, env=self.environment,
                                       stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            self.record(stage + "-process", pid=process.pid)
            try:
                code = process.wait(timeout=timeout)
            except (subprocess.TimeoutExpired, KeyboardInterrupt):
                stop_process(process)
                self.record(stage, status="failed", reason="timeout or interruption", exit_code=process.returncode)
                raise
        self.record(stage, status="passed" if code == 0 else "failed", exit_code=code)
        if code:
            raise RuntimeError(f"{stage} exited {code}; see {log}")
        return log

    def maven(self, stage, *arguments):
        return self.command(stage, [self.args.maven, "-B", "-ff", *SKIPS, *arguments])

    def prepare_build(self):
        java = str(Path(self.args.java_home) / "bin/java")
        log = self.command("java-version", [java, "-version"])
        if not re.search(r'version "21[.\"]', log.read_text()):
            raise RuntimeError("Acceptance requires JDK 21")
        if self.args.skip_build:
            self.record("build", status="skipped", reason="--skip-build; existing local artifacts")
        else:
            log = self.maven("build", "-T", "1C", "clean", "install", "-DskipTests=true")
            text = log.read_text()
            self.record("build-summary", tests="skipped", reactor_success=len(re.findall(r"^\[INFO\].* SUCCESS \[", text, re.M)),
                        abi_passed=text.count("Plugin ABI check passed:"))
        cp = self.output / "dependencies.txt"
        self.maven("container-classpath", "-pl", "killbill/profiles/killbill", "dependency:build-classpath",
                   "-Dcheck.skip-dependency=false", "-Dmdep.includeScope=test", f"-Dmdep.outputFile={cp}")
        jars = [p for p in cp.read_text().strip().split(os.pathsep) if
                any(group in p for group in ("/org/eclipse/jetty/", "/jakarta/servlet/", "/org/slf4j/slf4j-api/"))]
        if not any("jetty-webapp-" in p for p in jars):
            raise RuntimeError("Managed Jetty webapp dependency missing")
        self.classpath = os.pathsep.join([str(self.output), *jars])
        self.command("compile-container", [str(Path(self.args.java_home) / "bin/javac"), "--release", "21",
                                           "-cp", self.classpath, "-d", str(self.output), str(TOOLS / "WarServer.java")])
        self.war = artifact(ROOT / "killbill/profiles/killbill/target", "*.war")
        self.record("war", path=str(self.war), sha256=hashlib.sha256(self.war.read_bytes()).hexdigest())

    def database_tests(self):
        if self.args.db_host not in ("127.0.0.1", "localhost") or self.args.db_port != 5432:
            raise ValueError("Existing slow DB test helper requires localhost:5432")
        database = self.infrastructure.create(ROOT, "slow-tests", testing=True)
        for label, module, tests in (
            ("postgres-account", "killbill/account", "TestAccountDao#testBasic+testMinimalFields"),
            ("postgres-business", "killbill/beatrix", "TestIntegration#testBasePlanCompleteWithBillingDayInPast"),
            ("postgres-queue-shutdown", "killbill-commons/queue", "TestNotificationQueueShutdown"),
            ("postgres-durable-events", "killbill-platform/plugin-runtime", "ITPostgresqlDurableEvents"),
            ("postgres-durable-cluster", "killbill-platform/plugin-runtime", "ITPostgresqlDurableEventsCluster"),
            ("postgres-durable-stale", "killbill-platform/plugin-runtime", "ITPostgresqlDurableEventsStaleWorker")):
            if label in ("postgres-queue-shutdown", "postgres-durable-events", "postgres-durable-cluster", "postgres-durable-stale"):
                database = self.infrastructure.create(ROOT, label)
            properties = " ".join(JVM + [
                "-Dorg.killbill.billing.dbi.test.postgresql=true", "-Dorg.killbill.billing.dbi.test.useLocalDb=true",
                f"-Dorg.killbill.billing.dbi.test.localDb.database={database}",
                f"-Dorg.killbill.billing.dbi.test.localDb.username={self.args.db_user}",
                f'-Dorg.killbill.billing.dbi.test.localDb.output="{self.output}"',
                f"-Dorg.killbill.billing.dbi.test.localDb.password={self.args.password}"])
            self.maven(label, "-pl", module, "test", "-Ppostgresql", f"-Dtest={tests}", f"-DsurefireArgLine={properties}")
            self.check_test_reports(label)

    def check_test_reports(self, label):
        totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        log = (self.output / f"{label}.log").read_text()
        if "Using PostgreSQL local database" not in log:
            raise RuntimeError("Test JVM did not confirm PostgreSQL")
        # Surefire prints one aggregate summary per invocation, without the timing/class suffix.
        summaries = re.findall(r"^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", log, re.M)
        for summary in summaries:
            for key, value in zip(totals, summary):
                totals[key] += int(value)
        self.record(label + "-results", **totals)
        if not totals["tests"] or any(totals[k] for k in ("failures", "errors", "skipped")):
            raise RuntimeError(f"Unsuccessful test results: {totals}")

    def configure_server(self, database, install=None, name="killbill"):
        install = install or self.output / "plugins"
        plugins = install / "hello-world/1.0.0"
        plugins.mkdir(parents=True)
        jar = artifact(ROOT / "plugins/hello-world-plugin/target", "hello-world-plugin-*.jar")
        shutil.copy2(jar, plugins / "plugin.jar")
        with zipfile.ZipFile(jar) as archive:
            (plugins / "plugin.yaml").write_bytes(archive.read("plugin.yaml"))
        values = {
            "org.killbill.dao.url": f"jdbc:postgresql://{self.args.db_host}:{self.args.db_port}/{database}",
            "org.killbill.dao.user": self.args.db_user, "org.killbill.dao.password": self.args.password,
            "org.killbill.catalog.uri": (ROOT / "killbill/profiles/killbill/src/main/resources/SpyCarAdvanced.xml").as_uri(),
            "org.killbill.server.updateCheck.skip": "true", "org.killbill.server.test.mode": "false",
            "org.killbill.server.multitenant": "true", "org.killbill.server.lookupEnvironmentVariables": "false",
            "org.killbill.billing.plugin.install.dir": str(install),
            "org.killbill.billing.plugin.mandatory.plugins": "hello-world",
            "org.killbill.notificationq.main.sleep": "100", "org.killbill.persistent.bus.main.sleep": "100",
            "org.killbill.persistent.bus.external.sleep": "100", "org.killbill.tenant.broadcast.rate": "1s"}
        self.config = self.output / f"{name}.properties"
        self.config.write_text("\n".join(k + "=" + property_value(v) for k, v in values.items()) + "\n")
        self.record("plugin", path=str(jar), sha256=hashlib.sha256(jar.read_bytes()).hexdigest())

    def start_server(self, generation):
        self.server = WarNode(self, generation, self.config)
        return self.server.start(JVM)

    def stop_server(self):
        if self.server is not None:
            self.server.stop()
            self.server = None

    def http_smoke(self):
        database = self.infrastructure.create(ROOT, "http")
        self.configure_server(database)
        api = self.start_server(1)
        state = business_flow(api)
        self.record("business-flow", status="passed", **state)
        self.stop_server()
        recovered = self.start_server(2)
        recovered.headers.update(api.headers)
        check_plugin(recovered)
        verify_business(recovered, state)
        self.record("restart-recovery", status="passed", **state)
        self.stop_server()

    def run(self):
        try:
            self.infrastructure.probe()
            self.prepare_build()
            self.database_tests()
            self.http_smoke()
            from war_cluster import WarCluster
            WarCluster(self, JVM).run()
            if not self.args.keep_databases:
                for database in sorted(self.infrastructure.owned):
                    self.infrastructure.drop(database)
            self.report["status"] = "passed"
        except (Exception, KeyboardInterrupt) as error:
            self.report.update(status="failed", error=str(error))
            raise
        finally:
            try:
                self.stop_server()
            except Exception as error:
                self.report.update(status="failed", cleanup_error=str(error))
            self.save()
            print("Report: " + str(self.output / "report.json"), flush=True)


def property_value(value):
    return str(value).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")


def artifact(directory, pattern):
    matches = [p for p in directory.glob(pattern) if not any(
        marker in p.name for marker in ("-sources", "-javadoc", "-tests", "-classes", "original-"))]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one built artifact in {directory}: {matches}")
    return matches[0].resolve()


def interrupted(signum, frame):
    raise KeyboardInterrupt(f"Interrupted by signal {signum}")


if __name__ == "__main__":
    os.umask(0o077)
    signal.signal(signal.SIGTERM, interrupted)
    validation = Validation(options())
    try:
        validation.run()
    except (Exception, KeyboardInterrupt) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
    sys.exit(0 if validation.report["status"] == "passed" else 1)
