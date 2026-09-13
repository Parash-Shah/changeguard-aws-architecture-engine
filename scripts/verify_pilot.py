#!/usr/bin/env python3
"""Verify generated safe/regression plans through the running API."""
import argparse
import json
import os
import pathlib
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--plans", type=pathlib.Path, default=pathlib.Path("reports/pilot"))
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parent.parent
    baseline = args.plans / "baseline.json"
    evidence_path = args.plans / "verification.json"
    evidence_path.unlink(missing_ok=True)
    evidence = []
    for name, proposed, expected_status, expected_score, expected_exit in [
        ("baseline", baseline, "PASS", 100, 0),
        ("regression", args.plans / "regression.json", "FAIL", 70, 1),
        ("fixed", baseline, "PASS", 100, 0),
    ]:
        output = args.plans / f"{name}-review.json"
        output.unlink(missing_ok=True)
        result = subprocess.run([sys.executable, str(root / "scripts/scan.py"), str(proposed), "--format", "TERRAFORM_PLAN",
                                 "--baseline", str(baseline), "--quality-gate", str(root / "infra/pilot/gate.json"),
                                 "--output", str(output)])
        if result.returncode != expected_exit:
            raise RuntimeError(f"{name}: expected CLI exit {expected_exit}, got {result.returncode}")
        report = json.loads(output.read_text())
        if (report["status"], report["score"]) != (expected_status, expected_score):
            raise RuntimeError(f"{name}: unexpected gate status or score")
        if report["unsupportedResourceTypes"] or report["unknownChecks"] or report["ruleErrors"]:
            raise RuntimeError(f"{name}: incomplete analysis")
        if name == "regression":
            actual = {(f["ruleId"], f["classification"]) for f in report["findings"]}
            if actual != {("SEC-RDS-002", "NEW"), ("REL-RDS-001", "NEW")}:
                raise RuntimeError("Unexpected regression findings")
        evidence.append({"phase": name, "status": report["status"], "score": report["score"], "reviewId": report["reviewId"]})
    evidence_path.write_text(json.dumps({"generatedTerraformPlans": True, "liveAws": False,
        "githubRunId": os.getenv("GITHUB_RUN_ID"), "phases": evidence}, indent=2))
    print("Pilot verified: PASS -> FAIL -> PASS; evidence saved alongside plans.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
