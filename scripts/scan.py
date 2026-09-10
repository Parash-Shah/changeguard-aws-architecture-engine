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

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("template", type=pathlib.Path)
    parser.add_argument("--format", choices=["CLOUDFORMATION", "TERRAFORM_PLAN"], default="CLOUDFORMATION")
    parser.add_argument("--baseline", type=pathlib.Path)
    parser.add_argument("--baseline-review-id")
    parser.add_argument("--regressions-only", action="store_true")
    parser.add_argument("--url", default=os.environ.get("CHANGEGUARD_URL", "http://localhost:8080"))
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("reports/review.json"))
    args = parser.parse_args()
    request = {"template": args.template.read_text(encoding="utf-8-sig"), "format": args.format}
    if args.baseline: request["baselineTemplate"] = args.baseline.read_text(encoding="utf-8-sig")
    if args.baseline_review_id: request["baselineReviewId"] = args.baseline_review_id
    if args.regressions_only: request["qualityGate"] = {"minimumScore": 80, "failOn": ["CRITICAL"], "maxHighFindings": 2, "regressionsOnly": True}
    body = json.dumps(request, sort_keys=True).encode()
    # Distinguish workflow attempts, while retries within an attempt reuse the same key.
    run = os.environ.get("GITHUB_RUN_ID", "local") + ":" + os.environ.get("GITHUB_RUN_ATTEMPT", "1")
    key = hashlib.sha256(run.encode() + body).hexdigest()
    headers = {"Content-Type": "application/json", "Idempotency-Key": key}
    if os.environ.get("CHANGEGUARD_API_KEY"): headers["X-API-Key"] = os.environ["CHANGEGUARD_API_KEY"]
    try:
        with urllib.request.urlopen(urllib.request.Request(args.url.rstrip("/") + "/v1/reviews", data=body, headers=headers), timeout=120) as response:
            report = json.load(response)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
        with urllib.request.urlopen(urllib.request.Request(args.url.rstrip("/") + "/v1/reviews/" + report["reviewId"] + "/markdown", headers=headers), timeout=30) as response:
            markdown = response.read().decode()
        args.output.with_suffix(".md").write_text(markdown, encoding="utf-8")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary: summary.write(markdown)
        print(f"ChangeGuard: {report['status']} — score {report['score']}/100; report {args.output}")
        return 1 if report["status"] == "FAIL" else 0
    except (urllib.error.URLError, ValueError, KeyError) as error:
        print(f"ChangeGuard scan could not complete: {type(error).__name__}", file=sys.stderr)
        return 2

if __name__ == "__main__":
    sys.exit(main())
