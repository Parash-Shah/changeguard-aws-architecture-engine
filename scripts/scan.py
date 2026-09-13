#!/usr/bin/env python3
"""Submit trusted generated IaC JSON; write a report and exit nonzero for a failed gate."""
import argparse
import hashlib
import json
import os
import pathlib
import sys
import urllib.error
import urllib.request
import uuid

def build_request(args):
    request = {"template": args.template.read_text(encoding="utf-8-sig"), "format": args.format}
    if args.baseline:
        request["baselineTemplate"] = args.baseline.read_text(encoding="utf-8-sig")
    if args.baseline_review_id:
        request["baselineReviewId"] = args.baseline_review_id
    if args.change_set:
        if args.format != "CLOUDFORMATION":
            raise ValueError("Change sets require CLOUDFORMATION format")
        request["changeSet"] = args.change_set.read_text(encoding="utf-8-sig")
    if args.quality_gate:
        request["qualityGate"] = json.loads(args.quality_gate.read_text(encoding="utf-8-sig"))
        if not isinstance(request["qualityGate"], dict):
            raise ValueError("Quality gate must be a JSON object")
        request["qualityGate"] = {"minimumScore": 80, "failOn": ["CRITICAL"], "maxHighFindings": 2,
                                  **request["qualityGate"]}
    if args.regressions_only:
        request.setdefault("qualityGate", {"minimumScore": 80, "failOn": ["CRITICAL"], "maxHighFindings": 2}).update(regressionsOnly=True)
    if args.suppressions:
        request["suppressions"] = json.loads(args.suppressions.read_text(encoding="utf-8-sig"))
        if not isinstance(request["suppressions"], list):
            raise ValueError("Suppressions must be a JSON array")
    return request


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("template", type=pathlib.Path)
    parser.add_argument("--format", choices=["CLOUDFORMATION", "TERRAFORM_PLAN"], default="CLOUDFORMATION")
    baseline = parser.add_mutually_exclusive_group()
    baseline.add_argument("--baseline", type=pathlib.Path)
    baseline.add_argument("--baseline-review-id")
    parser.add_argument("--change-set", type=pathlib.Path)
    parser.add_argument("--quality-gate", type=pathlib.Path, help="Trusted quality-gate JSON object")
    parser.add_argument("--suppressions", type=pathlib.Path, help="Approved suppression JSON array")
    parser.add_argument("--regressions-only", action="store_true")
    parser.add_argument("--idempotency-key", help="Reuse this key only when retrying the same review request")
    parser.add_argument("--url", default=os.environ.get("CHANGEGUARD_URL", "http://localhost:8080"))
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("reports/review.json"))
    args = parser.parse_args(argv)
    try:
        request = build_request(args)
    except (OSError, ValueError) as error:
        print(f"ChangeGuard input could not be read: {type(error).__name__}", file=sys.stderr)
        return 2
    body = json.dumps(request, sort_keys=True).encode()
    # Distinguish workflow attempts, while retries within an attempt reuse the same key.
    # Local invocations need fresh reviews when the server's catalog or dated exceptions change.
    run = os.environ.get("GITHUB_RUN_ID", uuid.uuid4().hex) + ":" + os.environ.get("GITHUB_RUN_ATTEMPT", "1")
    key = args.idempotency_key or hashlib.sha256(run.encode() + body).hexdigest()
    headers = {"Content-Type": "application/json", "Idempotency-Key": key}
    if os.environ.get("CHANGEGUARD_API_KEY"): headers["X-API-Key"] = os.environ["CHANGEGUARD_API_KEY"]
    try:
        with urllib.request.urlopen(urllib.request.Request(args.url.rstrip("/") + "/v1/reviews", data=body, headers=headers), timeout=120) as response:
            report = json.load(response)
        if report["status"] not in ("PASS", "WARN", "FAIL"):
            raise ValueError("Unrecognized gate status")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        with urllib.request.urlopen(urllib.request.Request(args.url.rstrip("/") + "/v1/reviews/" + report["reviewId"] + "/markdown", headers=headers), timeout=30) as response:
            markdown = response.read().decode()
        args.output.with_suffix(".md").write_text(markdown, encoding="utf-8")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary: summary.write(markdown)
        print(f"ChangeGuard: {report['status']} — score {report['score']}/100; report {args.output}")
        return 1 if report["status"] == "FAIL" else 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"ChangeGuard scan could not complete: {type(error).__name__}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    sys.exit(main())
