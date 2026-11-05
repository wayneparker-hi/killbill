"""Failure guards for acceptance: false green results and cleanup ownership."""

import copy
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

from infrastructure import Infrastructure
from http_smoke import check_plugin, verify_business
from validate import Validation, check_queue_shutdown
from war_node import WarNode


class ValidationGuards(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.output = Path(self.directory.name)
        self.validation = Validation.__new__(Validation)
        self.validation.output = self.output
        self.validation.environment = dict(os.environ)
        self.validation.record = Mock()

    def test_foreign_database_is_never_sent_to_postgres(self):
        infrastructure = Infrastructure(SimpleNamespace(), self.output, Mock())
        infrastructure.postgres = Mock()
        with self.assertRaises(ValueError):
            infrastructure.drop("unowned_disposable_probe")
        infrastructure.postgres.assert_not_called()

    def test_ddl_failure_keeps_database_for_diagnosis(self):
        path = self.output / "killbill/util/src/main/resources/org/killbill/billing/util"
        path.mkdir(parents=True)
        (path / "ddl-postgresql.sql").write_text("SELECT deliberately_invalid_sql")
        infrastructure = Infrastructure(SimpleNamespace(), self.output, Mock())
        infrastructure.postgres = Mock(side_effect=["", subprocess.CalledProcessError(3, ["psql"], stderr="SQL failed")])
        with self.assertRaisesRegex(RuntimeError, "DDL failed"):
            infrastructure.create(self.output, "broken")
        self.assertEqual(len(infrastructure.owned), 1)
        self.assertEqual((self.output / "broken-ddl-error.log").read_text(), "SQL failed")

    def test_child_failure_is_not_a_successful_stage(self):
        with self.assertRaisesRegex(RuntimeError, "exited 7"):
            self.validation.command("failed", [sys.executable, "-c", "raise SystemExit(7)"])
        self.assertEqual(self.validation.record.call_args.kwargs["status"], "failed")
        self.assertEqual(self.validation.record.call_args.kwargs["exit_code"], 7)

    def test_deadline_stops_the_command_process(self):
        marker = self.output / "pid"
        command = [sys.executable, "-c", "import os,time,pathlib; "
                   f"pathlib.Path({str(marker)!r}).write_text(str(os.getpid())); time.sleep(30)"]
        with self.assertRaises(subprocess.TimeoutExpired):
            self.validation.command("hung", command, timeout=1)
        pid = int(marker.read_text())
        try:
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)
        finally:
            try:
                os.kill(pid, __import__("signal").SIGKILL)
            except ProcessLookupError:
                pass

    def test_no_tests_skips_failures_and_wrong_backend_are_rejected(self):
        log = self.output / "suite.log"
        for summary in ("0, Failures: 0, Errors: 0, Skipped: 0", "2, Failures: 0, Errors: 0, Skipped: 1",
                        "2, Failures: 1, Errors: 0, Skipped: 0", "2, Failures: 0, Errors: 1, Skipped: 0"):
            log.write_text("Using PostgreSQL local database\n[INFO] Tests run: " + summary + "\n")
            with self.assertRaises(RuntimeError, msg=summary):
                self.validation.check_test_reports("suite")
        log.write_text("Using h2 as the embedded database\n[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n")
        with self.assertRaisesRegex(RuntimeError, "did not confirm PostgreSQL"):
            self.validation.check_test_reports("suite")
        log.write_text("Using PostgreSQL local database\n[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\n")
        self.validation.check_test_reports("suite")

    def test_missing_or_stopped_plugin_rejects_readiness(self):
        api = SimpleNamespace(get=lambda _: [])
        with self.assertRaises(RuntimeError):
            check_plugin(api)
        api.get = lambda _: [{"pluginKey": "hello-world", "state": "STOPPED"}]
        with self.assertRaises(RuntimeError):
            check_plugin(api)

    def test_queue_shutdown_timeout_and_uncompleted_records_reject_acceptance(self):
        log = self.output / "shutdown.log"
        log.write_text("notifications: Completion thread has exited\n")
        check_queue_shutdown(log)
        for warning in ("Timed out while shutting down subscription-service:subscription-events queue",
                        "notifications: Stopped queue with 40 event/notifications non completed"):
            log.write_text(warning)
            node = WarNode(self.validation, "guard", self.output / "unused")
            node.process = Mock(returncode=143)
            node.process.poll.return_value = None
            node.log = log
            self.validation.server = node
            with patch("war_node.stop_process", return_value=False):
                with self.assertRaisesRegex(RuntimeError, "Queue shutdown did not complete cleanly"):
                    self.validation.stop_server()

    def test_business_recovery_requires_active_subscription_and_exact_paid_amount(self):
        state = {"accountId": "account", "subscriptionPath": "/subscription", "invoiceId": "invoice",
                 "paymentId": "payment", "amount": "10.00"}
        values = {"/1.0/kb/accounts/account": {"accountId": "account"},
                  "/subscription": {"state": "ACTIVE"},
                  "/1.0/kb/invoices/invoice": {"balance": 0, "amount": 10, "status": "COMMITTED"},
                  "/1.0/kb/payments/payment": {"transactions": [
                      {"status": "SUCCESS", "transactionType": "PURCHASE", "amount": 10}]}}
        verify_business(SimpleNamespace(get=values.__getitem__), state)
        for path, key, wrong in (("/subscription", "state", "CANCELLED"),
                                  ("/1.0/kb/invoices/invoice", "amount", 11),
                                  ("/1.0/kb/invoices/invoice", "status", "DRAFT")):
            changed = copy.deepcopy(values)
            changed[path][key] = wrong
            with self.assertRaises(RuntimeError):
                verify_business(SimpleNamespace(get=changed.__getitem__), state)
        for transactions in ([], [{"status": "PAYMENT_FAILURE", "transactionType": "PURCHASE", "amount": 10}],
                             [{"status": "SUCCESS", "transactionType": "PURCHASE", "amount": 11}],
                             values["/1.0/kb/payments/payment"]["transactions"] * 2):
            changed = copy.deepcopy(values)
            changed["/1.0/kb/payments/payment"]["transactions"] = transactions
            with self.assertRaises(RuntimeError):
                verify_business(SimpleNamespace(get=changed.__getitem__), state)


if __name__ == "__main__":
    unittest.main()
