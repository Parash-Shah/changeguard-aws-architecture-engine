# Milestone audit

Audited September 13, 2026. Local implementation and live acceptance checks are tracked separately. This is a pre-deployment analyzer; completing it does not require deploying the sample RDS or ECS infrastructure.

| Step | Milestone | Implementation and evidence |
|---:|---|---|
| 1 | Repository and stack | Java 21, Spring Boot, Maven wrapper, Docker, CI and documented layout |
| 2 | Normalized resources | Deeply immutable `CloudResource` model; parser/model tests |
| 3 | CloudFormation parser | JSON resource parsing; malformed/duplicate/unknown input tests |
| 4 | Rule interface and pillars | Versioned rule metadata, results and all six pillars |
| 5 | Initial five rules | Encryption, RDS exposure/Multi-AZ, S3 access and IAM wildcard checks |
| 6 | Evaluation engine | Applicable checks, isolated evaluation and applicability errors |
| 7 | Severity and scoring | Five severities, weighted penalties, overall score |
| 8 | REST API | Create/fetch reviews, validation, authentication and error responses |
| 9 | PostgreSQL history | Flyway/JPA; transactional reviews, resources, findings and rule versions |
| 10 | Milestones | Persisted local milestone creation/history; PostgreSQL integration tests |
| 11 | Pillar scores | Six pillar summaries; unassessed pillars explicitly have null scores |
| 12 | Expanded rules | 43 default policies across 18 resource types |
| 13 | Metadata | IDs, versions, rationale, recommendations and documentation references |
| 14 | Declarative policies | JSON catalogs and bounded operators; external catalogs loaded at startup |
| 15 | CloudFormation changes | Complete change-set metadata reconciled with before/after templates |
| 16 | Change severity | SAFE, NEUTRAL, RISK_INCREASE and RISK_REDUCTION |
| 17 | Deployment gates | Score/severity/high-count thresholds; incomplete/destructive changes fail closed |
| 18 | GitHub integration | Workflows and CLI implemented; previous push CI passed. Real PR failure/fix and required-check enforcement remain pending |
| 19 | Terraform plans | Generated JSON plans, nested modules, unknowns, split S3 controls and action metadata |
| 20 | Destructive changes | Stateful deletion/replacement and alarm-removal findings; permissive gates cannot waive stateful destruction |
| 21 | Baselines | Stored snapshot promotion and regression-only gates; immutable baseline integration test |
| 22 | Suppressions | Required reason/expiry, exact resource/rule matching, persistent audit; errors cannot be suppressed |
| 23 | AWS review context | SDK browser login, workload creation, workload/lens/answer reads, milestone pagination and creation/comparison of milestones 1 and 2 verified live; adapter also mock-tested |
| 24 | Organizational lens | Six local Agent Platform policies; positive bounded timeouts/step counts and dedicated tests. This is a local policy pack, not a published AWS custom lens |
| 25 | Observability | Prometheus metrics and nine Grafana panels, including most violated unsuppressed rules |
| 26 | Load testing | API harness with 5,000 resources; separate 100-rule/500,000-check engine harness |
| 27 | Caching | Bounded cache keyed by immutable resource and complete versioned rule definition; exception results not cached |
| 28 | Concurrency | Bounded executor, 1/4/8/16-thread benchmark; concurrent-review isolation tests |
| 29 | Failure handling | Rule/applicability isolation, AWS throttling, malformed input, PostgreSQL outage/retry and duplicate processing |
| 30 | CI and regression suite | Maven unit/scenario/PostgreSQL tests, Python tests, Docker build and Terraform validation/format checks |

## Latest acceptance evidence

The final implementation passed **233 Java tests**, including six PostgreSQL integration tests, with zero failures, errors or skips. All **10 Python tests** passed. The Docker image rebuilt successfully; Terraform validation and recursive format checks passed. The rebuilt API verified generated plans as **PASS/100 -> FAIL/70 -> PASS/100**, detecting exactly the RDS public-access and Multi-AZ regressions. Rule-labelled Prometheus metrics were checked through the running API.

The accuracy corpus remains **150 synthetic variations of 15 independently authored mutations**: 60 secure and 90 unsafe variations, including 30 critical cases. Additional edge-case, organizational policy, parser, concurrency and integration tests are outside that corpus. Synthetic-suite recall is not a production accuracy estimate. Historical evidence is retained under `docs/results`; the [release report](results/release.json) links the latest measurements and their scopes. The current 100-review API run achieved P95 1.10 seconds; the synthetic 500,000-check engine benchmark and its 1/4/8/16-thread results are recorded separately in [release-engine.json](results/release-engine.json).

## External acceptance remaining

- **AWS writes:** `changeguard-dev` can authenticate and read Well-Architected, but CreateWorkload returned AccessDenied. An administrator must attach the prepared [pilot write policy](examples/aws-pilot-write-policy.json) before the [workload request](examples/aws-pilot-workload.json) and two-milestone test can run. No compute or database deployment is involved.
- **GitHub:** the branch exists remotely. Draft PR creation was rejected by automatic approval review this session; a previous attempt also encountered connector HTTP 403. Publishing requires explicit approval, and the connection still needs PR write permission. Required-check enforcement needs repository-owner access. See [pilot instructions](pilot.md) and the prepared [PR description](pilot-pr.md).

Local milestones, generated-plan regression checks and the mock-tested AWS adapter remain usable without GitHub enforcement. Do not describe these pending live checks as passed. Production hosting, multi-tenant authorization, AWS answer synchronization and remote custom-lens publication are separate deployment/product extensions.
