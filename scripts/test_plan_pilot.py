import json
import os
import pathlib
import tempfile
import unittest
from unittest.mock import patch

import plan_pilot


class PilotPlanTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        self.variables = self.root / "proposed.tfvars.json"
        self.variables.write_text('{"publicly_accessible":false,"multi_az":true}')
        self.output = self.root / "plan.json"

    def run_plan(self):
        return plan_pilot.main(["--directory", str(self.root), "--output", str(self.output)])

    @patch.dict(os.environ, {"AWS_PROFILE": "private", "AWS_ACCESS_KEY_ID": "test-only",
                             "TF_VAR_offline": "false", "TF_CLI_ARGS_plan": "-refresh=true"})
    @patch("plan_pilot.subprocess.run")
    def test_plan_uses_no_refresh_or_inherited_credentials(self, run):
        self.assertEqual(0, self.run_plan())
        command = run.call_args_list[1].args[0]
        self.assertIn("-refresh=false", command)
        self.assertGreater(command.index("-var=offline=true"), next(i for i, arg in enumerate(command) if arg.startswith("-var-file=")))
        env = run.call_args_list[1].kwargs["env"]
        self.assertNotIn("AWS_PROFILE", env)
        self.assertNotIn("AWS_ACCESS_KEY_ID", env)
        self.assertNotIn("TF_VAR_offline", env)
        self.assertNotIn("TF_CLI_ARGS_plan", env)
        self.assertFalse(any("apply" in call.args[0] for call in run.call_args_list))

    @patch("plan_pilot.subprocess.run")
    def test_rejects_unsupported_or_mistyped_pilot_inputs(self, run):
        for settings in [{"offline": False}, {"publicly_accessible": "false", "multi_az": True}, []]:
            self.variables.write_text(json.dumps(settings))
            self.assertEqual(2, self.run_plan())
        run.assert_not_called()

    @patch("plan_pilot.subprocess.run", side_effect=OSError("missing executable"))
    def test_failed_generation_removes_stale_json(self, run):
        self.output.write_text('{"old":"success"}')
        self.assertEqual(2, self.run_plan())
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
