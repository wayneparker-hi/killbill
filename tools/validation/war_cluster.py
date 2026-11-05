"""Same-host two-WAR acceptance; all queue writes go through the production plugin API."""

from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import uuid
import zipfile

from http_smoke import await_value, business_flow, check_plugin, verify_business
from war_node import WarNode

PROBE = "/plugins/cluster-probe"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify_deliveries(rows, expected):
    actual = Counter((r["id"], r["node"]) for r in rows)
    require(actual == Counter(expected), f"Wrong per-node deliveries: {actual}; expected {expected}")


class WarCluster:
    def __init__(self, validation, jvm):
        self.v, self.jvm = validation, jvm
        self.nodes = []
        self.install = self.v.output / "cluster-plugins"
        self.evidence = self.install / "cluster-probe/1.0.0/evidence"
        self.name = {key: "war-" + key for key in ("a", "b")}

    def query(self, select):
        require(self.database in self.v.infrastructure.owned, "Refuse query outside this run's database")
        sql = "SELECT COALESCE(json_agg(row_to_json(rows)), '[]'::json) FROM (" + select + ") rows"
        return json.loads(self.v.infrastructure.postgres(self.database, "-Atc", sql))

    def prepare(self):
        self.database = self.v.infrastructure.create(Path(__file__).resolve().parents[2], "war-cluster")
        self.v.infrastructure.postgres(self.database, sql="""
            CREATE TABLE validation_war_effects (id uuid PRIMARY KEY, payload text NOT NULL);
            CREATE TABLE validation_war_deliveries (record_id bigserial PRIMARY KEY, id uuid NOT NULL, node varchar(45) NOT NULL);
        """)
        self.v.configure_server(self.database, self.install, "cluster")
        self.build_probe()
        with self.v.config.open("a") as config:
            config.write("org.killbill.billing.plugin.mandatory.plugins=hello-world,cluster-probe\n"
                         "org.killbill.billing.plugin.events.durable.enabled=true\n"
                         "org.killbill.persistent.bus.external.queue.mode=POLLING\n"
                         "org.killbill.persistent.bus.external.claimed=1\n"
                         "org.killbill.persistent.bus.external.queue.capacity=1\n"
                         "org.killbill.persistent.bus.external.nbThreads=1\n"
                         "org.killbill.persistent.bus.external.claim.time=15s\n"
                         "org.killbill.persistent.bus.external.reapThreshold=20s\n"
                         "org.killbill.persistent.bus.external.reapSchedule=100ms\n")
        self.config = self.v.config

    def build_probe(self):
        root = self.v.output / "cluster-probe-build"
        libraries, classes = root / "libraries", root / "classes"
        libraries.mkdir(parents=True)
        classes.mkdir()
        with zipfile.ZipFile(self.v.war) as war:
            for name in war.namelist():
                if name.startswith("WEB-INF/lib/") and name.endswith(".jar"):
                    (libraries / Path(name).name).write_bytes(war.read(name))
        cp = self.v.classpath + ":" + ":".join(str(p) for p in libraries.glob("*.jar"))
        java = Path(self.v.args.java_home) / "bin/javac"
        self.v.command("compile-cluster-probe", [str(java), "--release", "21", "-cp", cp, "-d", str(classes),
                                               str(Path(__file__).with_name("ClusterProbePlugin.java"))])
        directory = self.install / "cluster-probe/1.0.0"
        directory.mkdir(parents=True)
        descriptor = ("id: cluster-probe\nname: Cluster acceptance probe\nversion: 1.0.0\n"
                      "entrypoint:\n  class: org.killbill.billing.plugin.validation.ClusterProbePlugin\n")
        (directory / "plugin.yaml").write_text(descriptor)
        jar = directory / "plugin.jar"
        with zipfile.ZipFile(jar, "w") as output:
            output.writestr("plugin.yaml", descriptor)
            for path in classes.rglob("*.class"):
                output.write(path, str(path.relative_to(classes)))
        self.v.record("cluster-probe", path=str(jar), sha256=hashlib.sha256(jar.read_bytes()).hexdigest(), acceptance_only=True)

    def launch(self, key, generation):
        node = WarNode(self.v, generation, self.config, self.name[key])
        self.nodes.append(node)  # Register before startup so partial startup is also cleaned.
        node.start(self.jvm)
        key, secret = uuid.uuid4().hex, uuid.uuid4().hex
        node.api.create("/1.0/kb/tenants?useGlobalDefault=true", {"apiKey": key, "apiSecret": secret})
        node.api.headers.update({"X-Killbill-ApiKey": key, "X-Killbill-ApiSecret": secret})
        self.identify(node)
        return node

    def identify(self, node):
        node.require_alive()
        info = node.api.get(PROBE)
        require(info == {"node": node.name, "pid": node.process.pid}, f"Wrong WAR identity: {info}")
        check_plugin(node.api)
        plugins = node.api.get("/1.0/kb/pluginsInfo")
        require(any(p.get("pluginKey") == "cluster-probe" and p.get("state") == "RUNNING" for p in plugins), "Probe is not running")
        self.v.record("cluster-node", **info, generation=node.generation)

    def wait(self, description, action, predicate, timeout=45):
        def observe():
            for node in self.nodes:
                if node.process is not None:
                    node.require_alive()
            return action()
        return await_value(description, observe, predicate, timeout)

    def publish(self, node, phase, count):
        ids = [str(uuid.uuid4()) for _ in range(count)]
        for id in ids:
            result, _ = node.api.request(PROBE + f"?id={id}&phase={phase}", {}, "POST")
            require(result == {"id": id}, "Wrong publish acknowledgement")
        return ids

    def attempts(self, ids, history=False):
        table = "bus_ext_events_history" if history else "bus_ext_events"
        tokens = ",".join("'" + str(uuid.UUID(id)) + "'" for id in ids)
        return self.query("SELECT record_id,user_token AS id,processing_owner AS node,processing_state AS state,error_count AS errors,"
                          "processing_available_date AS available,processing_available_date > (CURRENT_TIMESTAMP AT TIME ZONE 'UTC') AS valid "
                          f"FROM {table} WHERE user_token IN ({tokens}) ORDER BY record_id")

    def deliveries(self, ids):
        return [r for r in self.query("SELECT id::text,node FROM validation_war_deliveries ORDER BY record_id") if r["id"] in ids]

    def blocked_pair(self, ids):
        def entered(rows):
            return [r for r in rows if r["state"] == "IN_PROCESSING" and
                    (self.evidence / f'entered-{r["id"]}-{r["node"]}').is_file()]
        rows = self.wait("both WARs in handlers", lambda: self.attempts(ids), lambda rows: len(entered(rows)) == 2)
        held = entered(rows)
        require({r["node"] for r in held} == set(self.name.values()), f"No distinct WAR owners: {held}")
        require(len({r["id"] for r in held}) == 2 and all(r["valid"] for r in held), f"Invalid simultaneous claims: {held}")
        for row in held:
            self.wait("handler barrier", lambda: self.evidence / f'entered-{row["id"]}-{row["node"]}', Path.is_file)
        self.v.record("cluster-simultaneous", claims=held)
        return held

    def release(self, name):
        (self.evidence / name).touch()

    def verify_effects(self, ids):
        rows = [r for r in self.query("SELECT id::text,payload FROM validation_war_effects") if r["id"] in ids]
        require(sorted(rows, key=lambda r: r["id"]) == [{"id": id, "payload": "acceptance:" + id} for id in sorted(ids)],
                f"Wrong durable effects: {rows}")

    def drain(self, ids):
        self.wait("durable queue to drain", lambda: self.attempts(ids), lambda rows: not rows)
        self.verify_effects(ids)
        history = self.attempts(ids, True)
        require(not any(r["state"] == "FAILED" for r in history), f"Unexpected dead letter: {history}")
        return history

    def normal(self, a, b):
        require(a.process.pid != b.process.pid and a.api.base != b.api.base, "WARs must have distinct processes and ports")
        ids = self.publish(a, "normal", 8)
        self.blocked_pair(ids)
        self.release("release-normal")
        history = self.drain(ids)
        require(Counter((r["id"], r["state"]) for r in history) == Counter((id, "PROCESSED") for id in ids), "Wrong normal history")
        deliveries = self.deliveries(ids)
        require(Counter(r["id"] for r in deliveries) == Counter(ids), f"Normal delivery duplicated/missing: {deliveries}")
        require({r["node"] for r in deliveries} == set(self.name.values()), "Both WARs must consume")
        self.v.record("cluster-normal", status="passed", ids=ids, history=history, deliveries=deliveries)

    def failover(self, a, b):
        ids = self.publish(a, "crash", 6)
        pair = self.blocked_pair(ids)
        old = next(r for r in pair if r["node"] == a.name)
        old_pid, peer_pid = a.process.pid, b.process.pid
        self.verify_effects([old["id"]])
        require(not self.attempts([old["id"]], True), "Victim must be before ACK")
        a.crash()
        for id in ids:
            if id != old["id"]:
                self.release(f"release-{id}-{b.name}")
        replay = self.wait("B to replay the crashed claim", lambda: self.attempts([old["id"]]),
                           lambda rows: len(rows) == 1 and rows[0]["node"] == b.name and rows[0]["record_id"] != old["record_id"])
        current = replay[0]
        expiry = datetime.fromisoformat(old["available"]).replace(tzinfo=timezone.utc)
        require(expiry < datetime.now(timezone.utc), "Original lease must really expire")
        require(current["state"] == "IN_PROCESSING" and current["valid"], f"Invalid replay claim: {current}")
        self.wait("replay handler barrier", lambda: self.evidence / f'entered-{old["id"]}-{b.name}', Path.is_file)
        require(b.process.pid == peer_pid, "Surviving WAR must not restart")
        history = self.attempts([old["id"]], True)
        require(len(history) == 1 and history[0]["state"] == "REAPED" and history[0]["node"] == a.name, f"Wrong reaper history: {history}")
        self.release(f'release-{old["id"]}-{b.name}')
        history = self.drain(ids)
        processed = [r for r in history if r["state"] == "PROCESSED"]
        reaped = [r for r in history if r["state"] == "REAPED"]
        require(Counter((r["id"], r["node"]) for r in processed) == Counter((id, b.name) for id in ids), "Wrong takeover completions")
        require(len(history) == len(processed) + len(reaped) and all(r["node"] == a.name for r in reaped), "Wrong reaped owners/states")
        require(len({r["id"] for r in reaped}) == len(reaped) and old["id"] in {r["id"] for r in reaped}, "Wrong reaped attempts")
        verify_deliveries(self.deliveries(ids), [(id, b.name) for id in ids] + [(old["id"], a.name)])
        self.v.record("cluster-failover", status="passed", old_pid=old_pid, survivor_pid=peer_pid, old=old, new=current, history=history)
        return old_pid

    def business(self, node):
        state = business_flow(node.api, call_completion=False)
        self.v.record("cluster-business", status="passed", node=node.name, **state)
        return state, dict(node.api.headers)

    def verify_on(self, node, cases):
        for state, headers in cases:
            node.api.headers = dict(headers)
            verify_business(node.api, state)
        self.v.record("cluster-cross-read", status="passed", node=node.name, cases=len(cases))

    def run(self):
        try:
            self.prepare()
            a, b = self.launch("a", "cluster-a-1"), self.launch("b", "cluster-b-1")
            cases = [self.business(a), self.business(b)]
            self.verify_on(a, cases)
            self.verify_on(b, cases)
            self.normal(a, b)
            old_pid = self.failover(a, b)
            cases.append(self.business(b))
            self.verify_on(b, cases)
            before = self.query("SELECT id::text,node FROM validation_war_deliveries ORDER BY record_id")
            returned = self.launch("a", "cluster-a-2")
            require(returned.process.pid != old_pid, "Restarted A must be a new JVM")
            cases.append(self.business(returned))
            self.verify_on(returned, cases)
            self.verify_on(b, cases)
            require(self.query("SELECT id::text,node FROM validation_war_deliveries ORDER BY record_id") == before, "Rejoin redelivered completed work")
            require(not self.attempts([r["id"] for r in before]), "Rejoin resurrected completed work")
            self.v.record("cluster-rejoin", status="passed", old_pid=old_pid, new_pid=returned.process.pid, survivor_pid=b.process.pid)
        finally:
            self.cleanup()

    def cleanup(self):
        if self.evidence.is_dir():
            self.release("release-normal")
            for marker in self.evidence.glob("entered-*"):
                self.release(marker.name.replace("entered-", "release-", 1))
        errors = []
        for node in reversed(self.nodes):
            try:
                node.stop()
            except Exception as error:
                errors.append(str(error))
        if errors:
            self.v.record("cluster-cleanup", status="failed", errors=errors)
            raise RuntimeError("Cluster cleanup failed: " + "; ".join(errors))
