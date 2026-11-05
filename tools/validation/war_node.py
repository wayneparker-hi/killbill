"""Bounded lifecycle for each owned production WAR process."""

import os
from pathlib import Path
import re
import signal
import subprocess
import time

from http_smoke import Api


def stop_process(process):
    if process.poll() is not None:
        return False
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=20)
        return False
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
        return True


def check_queue_shutdown(log):
    warning = re.search(r"Timed out while shutting down|Stopped queue with \d+ event/notifications non completed",
                        log.read_text())
    if warning:
        raise RuntimeError(f"Queue shutdown did not complete cleanly: {warning.group()}; see {log}")


class WarNode:
    def __init__(self, validation, generation, config, name=None):
        self.validation, self.generation, self.config, self.name = validation, generation, config, name
        self.process = None
        self.log = validation.output / f"server-{generation}.log"

    def start(self, jvm):
        v = self.validation
        port_file = v.output / f"port-{self.generation}"
        work = v.output / f"webapp-{self.generation}"
        work.mkdir()
        command = [str(Path(v.args.java_home) / "bin/java"), *jvm, "-Duser.timezone=GMT",
                   "-Dorg.killbill.server.properties=" + self.config.as_uri()]
        if self.name:
            command.append("-Dorg.killbill.queue.creator.name=" + self.name)
        command += ["-cp", v.classpath, "WarServer", str(v.war), str(port_file), str(work)]
        with self.log.open("w") as output:
            self.process = subprocess.Popen(command, cwd=v.output, env=v.environment, stdout=output,
                                            stderr=subprocess.STDOUT, start_new_session=True)
        v.record("server-start", pid=self.process.pid, log=str(self.log), generation=self.generation, command=command)
        deadline = time.monotonic() + 180
        while not port_file.is_file():
            self.require_alive()
            if time.monotonic() >= deadline:
                raise RuntimeError(f"WAR startup timed out; see {self.log}")
            time.sleep(0.25)
        self.api = Api(int(port_file.read_text()))
        self.api.get("/1.0/healthcheck")
        self.require_alive()
        v.record("server-ready", generation=self.generation, pid=self.process.pid, port=int(port_file.read_text()))
        return self.api

    def require_alive(self):
        if self.process is None or self.process.poll() is not None:
            raise RuntimeError(f"WAR exited unexpectedly; see {self.log}")

    def crash(self):
        self.require_alive()
        process = self.process
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)
        self.validation.record("server-crashed", pid=process.pid, exit_code=process.returncode, generation=self.generation)
        if process.returncode != -signal.SIGKILL:
            raise RuntimeError(f"Wrong fault exit: {process.returncode}")
        self.process = None

    def stop(self):
        if self.process is None:
            return
        process = self.process
        unexpected = process.poll() is not None
        forced = stop_process(process)
        self.validation.record("server-stopped", pid=process.pid, exit_code=process.returncode, generation=self.generation)
        self.process = None
        if unexpected or forced or process.returncode not in (0, 143, -signal.SIGTERM):
            raise RuntimeError(f"WAR stop failed: unexpected={unexpected}, forced={forced}, exit={process.returncode}; see {self.log}")
        check_queue_shutdown(self.log)
        self.validation.record("queue-shutdown", status="passed", log=str(self.log))
