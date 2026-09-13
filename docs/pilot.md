# End-to-end pilot

The pilot covers Terraform-generated plans, API regression gates, AWS review context, and a required GitHub PR check. Local scanning needs no AWS account. Live AWS workload creation, Java workload/lens/answer reads, and two-milestone creation/comparison were verified on September 13 using the browser-login IAM profile. Offline plans and mocked responses are not live AWS evidence.

Local verification on September 12 passed the generated-plan sequence **PASS/100 → FAIL/70 → PASS/100**, plus 217 Java tests and 10 Python tests. The Docker image built successfully. [Recorded evidence](results/pilot.json) includes persisted review IDs and plan hashes. The Windows provider failed to start reliably, so actual plans were generated and exported with Terraform 1.12.2 in Linux Docker. At that point live AWS verification was pending. The later [AWS discovery evidence](results/aws-discovery.json) records successful SDK authentication and ListWorkloads only.

## Generated-plan exercise

Start the API with `docker compose up -d --build --wait`, then use Terraform 1.12.2 and Python 3:

```sh
python3 scripts/plan_pilot.py --output reports/pilot/baseline.json
python3 scripts/plan_pilot.py --variables regression.tfvars.json --output reports/pilot/regression.json
python3 scripts/verify_pilot.py
```

The module declares one RDS PostgreSQL instance against an existing subnet group. Offline mode uses placeholder credentials, disables account validation and metadata lookup, and generates creation plans with refresh disabled. No apply command is used. A live deployment requires a selected sandbox account, appropriate private networking, instance sizing and cleanup arrangements. Saved plan files are ignored by Git and excluded from workflow artifact uploads.

The verifier submits a safe plan, an unsafe proposal against that baseline, and the safe proposal again. It requires PASS/100, FAIL/70 with exactly two new findings (`SEC-RDS-002`, `REL-RDS-001`), and PASS/100. Results and persisted review IDs go under `reports/pilot`. Both plans describe resource creation: this tests configuration regressions against a proposed baseline, not an update of deployed AWS state.

The **Pilot End-to-End Test** workflow runs this exercise on pushes and PRs. It asserts the expected failed gate, so the test job itself passes when all three outcomes match.

## Real PR gate

The pilot gate also runs on pushes to `main`, using the push's exact previous commit as the trusted baseline. PR runs use the event's exact base commit. This gives new main commits a fresh architecture check after bootstrap; it does not rewrite historical PR results or fall back to proposal-supplied policies when a baseline is missing. Missing bootstrap files produce an explicit error and job summary.

The bootstrap branch is published as `milestone/terraform-aws-pilot`. Both [ChangeGuard CI](https://github.com/Parash-Shah/changeguard-aws-architecture-engine/actions/runs/34712989765) and [Pilot End-to-End Test](https://github.com/Parash-Shah/changeguard-aws-architecture-engine/actions/runs/34712989801) passed for commit `08dcf187ee95fe3344b385ac1223546220ba1cc2`. These are push-triggered test runs; required PR-check enforcement and an actual failing-then-passing PR remain unverified.

The GitHub connector previously rejected PR creation with HTTP 403 (`Resource not accessible by integration`); the September 13 draft PR attempt was rejected by automatic approval review for insufficiently explicit publishing authorization. A repository owner can [open the prepared bootstrap PR](https://github.com/Parash-Shah/changeguard-aws-architecture-engine/compare/main...milestone/terraform-aws-pilot?expand=1); its review description is in [pilot-pr.md](pilot-pr.md). Repository administration is also unavailable through this connector, so configuring the required check needs owner access.

1. Review and merge the bootstrap so the base branch has the module and helper scripts.
2. Require `pilot-architecture-gate` on `main` in GitHub branch settings/rulesets. Protect workflow and policy changes through required owner review.
3. Open a PR changing `infra/pilot/proposed.tfvars.json` to public access enabled and Multi-AZ disabled.
4. Confirm **Pilot Architecture Review / pilot-architecture-gate** fails with the two regressions.
5. Restore the safe values in that PR and confirm the check passes. Save both workflow-run links as evidence.

The gate checks out the exact base SHA for the engine, catalog, gate and Terraform module. Only two boolean proposal inputs are accepted; changing the module or provider lock requires a separately reviewed bootstrap update. The initial bootstrap PR cannot pass this gate while its base lacks these files; enable the required check after the bootstrap is merged. Neither workflow deploys infrastructure or receives AWS credentials.

## AWS gateway tests without an account

`WellArchitectedGatewayTest` and `WellArchitectedControllerTest` exercise mocked SDK responses for workload reads/creation, historical answers, milestone pagination, comparisons, validation and throttling. Run them without AWS configuration:

```sh
mvn -Dtest=WellArchitectedGatewayTest,WellArchitectedControllerTest test
```

On this Windows workstation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/local-maven.ps1 '-Dtest=WellArchitectedGatewayTest,WellArchitectedControllerTest' test
```

These tests never run `WellArchitectedLiveProbe`. Placeholder credentials in the offline Terraform module only satisfy provider configuration; they cannot authenticate AWS requests.

## Live AWS probe

Configure the AWS SDK default credential chain outside this repository. Set these environment variables without committing credentials:

```sh
export AWS_PROFILE=your-sandbox-profile
export AWS_REGION=us-east-1
export CHANGEGUARD_AWS_WORKLOAD_ID=your-existing-workload-id
# Optional: CHANGEGUARD_AWS_LENS and CHANGEGUARD_AWS_QUESTION_ID
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.changeguard.WellArchitectedLiveProbe -Dexec.classpathScope=test
```

This reads workload/lens context and all milestone pages. To explicitly create two milestones and compare them:

```sh
export CHANGEGUARD_AWS_RUN_TOKEN=a-unique-stable-pilot-attempt-id
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.changeguard.WellArchitectedLiveProbe -Dexec.classpathScope=test \
  -Dexec.args=--create-milestones
```

Reuse the token when retrying the same sequence; a new token creates another milestone pair. The probe calls the actual gateway and writes `reports/aws-live-probe.json` only after success. JUnit and CI never invoke it. It does not modify AWS answers, so immediate milestones ordinarily have equal risk counts; no AWS risk improvement is claimed. Local scores and AWS answer risks remain separate measures.

See [AWS integration](aws-integration.md) for the one-command Windows probe, prepared write policy, IAM actions and API limits. Full live acceptance requires PR failure/fix run links, an enforced required check and a successful AWS milestone artifact. The AWS artifact is now [verified](results/aws-milestones.json); the GitHub PR/check steps remain pending. These remain distinct from the passing local engine and generated-plan exercise.
