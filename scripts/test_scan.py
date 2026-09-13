import contextlib
import io
import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import scan


class ScanTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.template = self.write("template.json", '{"Resources":{}}')

    def write(self, name, content):
        path = self.root / name
        path.write_text(content, encoding="utf-8")
        return path

    def run_scan(self, *args):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return scan.main([str(self.template), "--output", str(self.root / "report.json"), *args])

    @patch.dict(os.environ, {}, clear=True)
    @patch("scan.urllib.request.urlopen")
    def test_gate_and_suppressions_reach_api_with_changeset(self, urlopen):
        gate = self.write("gate.json", '{"minimumScore":90,"maxHighFindings":0}')
        suppressions = self.write("exceptions.json", '[{"rule":"REL-RDS-001","resource":"dev","reason":"sandbox","expires":"2027-01-01"}]')
        changes = self.write("changes.json", '{"Changes":[]}')
        urlopen.side_effect = [io.BytesIO(b'{"reviewId":"id","score":90,"status":"WARN"}'), io.BytesIO(b"review")]
        self.assertEqual(0, self.run_scan("--quality-gate", str(gate), "--suppressions", str(suppressions),
                                        "--change-set", str(changes), "--baseline", str(self.template), "--regressions-only"))
        body = json.loads(urlopen.call_args_list[0].args[0].data)
        self.assertEqual(90, body["qualityGate"]["minimumScore"])
        self.assertTrue(body["qualityGate"]["regressionsOnly"])
        self.assertEqual("sandbox", body["suppressions"][0]["reason"])
        self.assertEqual('{"Changes":[]}', body["changeSet"])

    @patch.dict(os.environ, {}, clear=True)
    @patch("scan.urllib.request.urlopen")
    def test_failed_gate_returns_one_and_writes_report(self, urlopen):
        urlopen.side_effect = [io.BytesIO(b'{"reviewId":"id","score":70,"status":"FAIL"}'), io.BytesIO(b"failed")]
        self.assertEqual(1, self.run_scan())
        self.assertTrue((self.root / "report.json").is_file())
        self.assertEqual("failed", (self.root / "report.md").read_text())

    @patch("scan.urllib.request.urlopen")
    def test_unknown_status_cannot_pass(self, urlopen):
        urlopen.return_value = io.BytesIO(b'{"reviewId":"id","score":100,"status":"UNKNOWN"}')
        self.assertEqual(2, self.run_scan())

    @patch("scan.urllib.request.urlopen")
    def test_bad_configuration_and_missing_files_never_submit(self, urlopen):
        self.assertEqual(2, self.run_scan("--quality-gate", str(self.root / "missing")))
        gate = self.write("bad.json", "[]")
        self.assertEqual(2, self.run_scan("--quality-gate", str(gate)))
        self.assertEqual(2, self.run_scan("--format", "TERRAFORM_PLAN", "--change-set", str(self.template)))
        urlopen.assert_not_called()

    @patch.dict(os.environ, {}, clear=True)
    @patch("scan.urllib.request.urlopen")
    def test_identical_retry_reuses_idempotency_key(self, urlopen):
        urlopen.side_effect = [io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass"),
                               io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass")]
        self.assertEqual(0, self.run_scan("--idempotency-key", "retry-key"))
        self.assertEqual(0, self.run_scan("--idempotency-key", "retry-key"))
        self.assertEqual(urlopen.call_args_list[0].args[0].get_header("Idempotency-key"),
                         urlopen.call_args_list[2].args[0].get_header("Idempotency-key"))

    @patch.dict(os.environ, {}, clear=True)
    @patch("scan.urllib.request.urlopen")
    def test_local_invocations_do_not_replay_old_catalog_results(self, urlopen):
        urlopen.side_effect = [io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass"),
                               io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass")]
        self.assertEqual(0, self.run_scan("--regressions-only"))
        self.assertEqual(0, self.run_scan("--regressions-only"))
        self.assertNotEqual(urlopen.call_args_list[0].args[0].get_header("Idempotency-key"),
                            urlopen.call_args_list[2].args[0].get_header("Idempotency-key"))
        gate = json.loads(urlopen.call_args_list[0].args[0].data)["qualityGate"]
        self.assertEqual(80, gate["minimumScore"])
        self.assertEqual(2, gate["maxHighFindings"])

    @patch.dict(os.environ, {"GITHUB_RUN_ID": "42", "GITHUB_RUN_ATTEMPT": "1"}, clear=True)
    @patch("scan.urllib.request.urlopen")
    def test_same_workflow_attempt_reuses_key(self, urlopen):
        urlopen.side_effect = [io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass"),
                               io.BytesIO(b'{"reviewId":"id","score":100,"status":"PASS"}'), io.BytesIO(b"pass")]
        self.assertEqual(0, self.run_scan())
        self.assertEqual(0, self.run_scan())
        self.assertEqual(urlopen.call_args_list[0].args[0].get_header("Idempotency-key"),
                         urlopen.call_args_list[2].args[0].get_header("Idempotency-key"))


if __name__ == "__main__":
    unittest.main()
