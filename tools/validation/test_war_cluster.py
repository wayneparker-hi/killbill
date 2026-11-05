"""Reject false multi-node results and ensure one failed shutdown cannot orphan a peer."""

from pathlib import Path
import signal
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from war_cluster import WarCluster, verify_deliveries
from war_node import WarNode


class ClusterGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.v = SimpleNamespace(output=Path(self.directory.name), record=Mock())

    def test_duplicate_delivery_and_wrong_owner_are_rejected(self):
        expected = [("message", "a"), ("message", "b")]
        rows = [{"id": id, "node": node} for id, node in expected]
        verify_deliveries(rows, expected)
        for invalid in (rows[:1], rows + rows[:1], [rows[0], rows[0]]):
            with self.assertRaisesRegex(RuntimeError, "Wrong per-node deliveries"):
                verify_deliveries(invalid, expected)

    def test_forced_and_already_exited_nodes_fail_normal_shutdown(self):
        for exited, forced in ((True, False), (False, True)):
            node = WarNode(self.v, "guard", self.v.output / "unused")
            node.log.write_text("notifications: Completion thread has exited\n")
            node.process = Mock(returncode=-signal.SIGKILL)
            node.process.poll.return_value = 0 if exited else None
            with patch("war_node.stop_process", return_value=forced):
                with self.assertRaisesRegex(RuntimeError, "WAR stop failed"):
                    node.stop()

    def test_cleanup_tries_all_nodes_after_one_shutdown_fails(self):
        cluster = WarCluster(self.v, [])
        first, last = Mock(), Mock()
        last.stop.side_effect = RuntimeError("failed shutdown")
        cluster.nodes = [first, last]
        with self.assertRaisesRegex(RuntimeError, "Cluster cleanup failed"):
            cluster.cleanup()
        first.stop.assert_called_once()
        last.stop.assert_called_once()

    def test_impostor_identity_does_not_count_as_a_second_war(self):
        cluster = WarCluster(self.v, [])
        node = Mock(name="impostor")
        node.name = "war-b"
        node.process.pid = 123
        node.api.get.return_value = {"node": "war-a", "pid": 123}
        with self.assertRaisesRegex(RuntimeError, "Wrong WAR identity"):
            cluster.identify(node)

    def test_crash_must_be_real_sigkill(self):
        node = WarNode(self.v, "guard", self.v.output / "unused")
        node.process = Mock(returncode=0)
        node.process.poll.return_value = None
        with patch("war_node.os.killpg"):
            with self.assertRaisesRegex(RuntimeError, "Wrong fault exit"):
                node.crash()
