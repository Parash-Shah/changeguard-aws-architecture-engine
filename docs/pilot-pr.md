# Complete architecture engine and bootstrap Terraform PR pilot

The original PR workflow scanned a fixture. This change adds real Terraform-generated RDS plans and a separate PR gate using the exact base commit's engine, catalog, policy and Terraform module. Enabling public access and disabling Multi-AZ must fail; restoring safe settings must pass.

Includes S3 split-resource normalization, an explicit encryption policy, CLI configuration/idempotency, AWS workload and milestone history/comparisons, and an optional live probe. The gateway is mock-tested and live-verified with browser login, workload creation, workload/lens/answer reads and two-milestone creation/comparison; local scanning needs no AWS account.

Validation: 233 Java tests including six PostgreSQL integration tests, 10 Python tests, Docker image build, Terraform validation, and Terraform 1.12.2/AWS provider 6.64.0 generated plans. The final audit fixed quoted ingress ports, applicability exception isolation and nonpositive agent limits, and added rule-level violation metrics. The API verified PASS/100 -> FAIL/70 -> PASS/100 with exactly two new regressions. Both push-triggered GitHub workflows passed for `08dcf187ee95fe3344b385ac1223546220ba1cc2`; links and evidence are in [pilot.md](pilot.md) and [results/pilot.json](results/pilot.json).

Bootstrap sequencing: `pilot-architecture-gate` cannot pass on this initial PR because main lacks the trusted pilot module/scripts. Review and merge the bootstrap before enabling the required check. Then use a separate PR to record the failing regression and passing fix. The Pilot End-to-End Test validates all three expected outcomes within this branch.

Required-check enforcement and the real PR fail/fix demonstration remain pending. No compute or database infrastructure was applied. One Well-Architected review workload and two milestones were created; unchanged unanswered risk counts confirm round-trip behavior only. The release audit and fresh local measurements are in docs/completion.md and docs/results/release.json.
