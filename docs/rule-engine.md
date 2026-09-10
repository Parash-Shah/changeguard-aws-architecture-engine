# Rule engine

Policy files are JSON arrays under `rules/<pillar>/`. Each rule declares ID, version, title, pillar, severity, resource types, property path, operator, expected value, rationale, recommendation, documentation, and whether it is a heuristic. JSON is used to keep the initial policy language small and strictly typed.

```json
{
  "id": "REL-RDS-001", "version": "1",
  "title": "Production RDS should use Multi-AZ", "pillar": "RELIABILITY",
  "severity": "HIGH", "resourceTypes": ["AWS::RDS::DBInstance"],
  "property": "MultiAZ", "operator": "EQUALS", "expected": true,
  "rationale": "Require explicit infrastructure evidence of this architecture control.",
  "recommendation": "Enable MultiAZ for production workloads.",
  "documentation": "https://docs.aws.amazon.com/AWSCloudFormation/latest/TemplateReference/aws-resource-rds-dbinstance.html",
  "heuristic": false
}
```

Operators: `EQUALS`, `GTE`, `LTE`, `PRESENT`, `ALL_TRUE`, `IN`, `CONTAINS`, `NO_ADMIN`, `NO_PUBLIC_INGRESS`. Numeric comparisons accept numeric literals and numeric strings. No SpEL, scripts, shell execution, or user-provided Java classes are interpreted. IAM checks examine Allow statements, scalar/list action/resource values and NotAction/NotResource. Ingress checks detect world-open administrative/database ports, IPv4/IPv6, port ranges and all protocols.

Missing required properties fail explicit-declaration policies. CloudFormation `Ref`/`Fn::*`, resource conditions and Terraform unknown markers produce `UNKNOWN` instead of asserting a safe or unsafe value. This distinction does not attempt to reconstruct AWS account defaults. For example, an absent S3 block-public-access declaration does not prove that account-level access controls are absent. SQS's managed-SSE policy deliberately requests that specific configuration; a KMS-encrypted queue may need a policy exception. These limitations matter when interpreting results.

Outcomes are `PASS`, `FAIL`, `UNKNOWN`, `RULE_ERROR`. One Java rule exception becomes `RULE_ERROR`; other resources and rules continue. Unknown or error results cannot be suppressed and cause the gate to fail as incomplete analysis. Unsupported resource types are listed and also fail the gate. An unassessed pillar has a null score, displayed as “Not assessed”.

## Scoring and gates

Penalties: INFO 0, LOW 2, MEDIUM 5, HIGH 10, CRITICAL 20. Score is `max(0, 100 - sum(active failure penalties))`; each pillar uses the same formula for its own findings. This intentionally simple policy score is neither an AWS score nor a statistically calibrated risk probability. Large architectures can hit zero quickly.

Default gate: minimum 80, no CRITICAL findings, at most two HIGH findings. Passing with remaining findings is WARN; no active findings is PASS; violated thresholds/incomplete analysis are FAIL. `regressionsOnly` applies score/thresholds to NEW failures while retaining the complete architecture score and existing debt in the report. Removing/replacing a stateful resource always requires manual review and fails even a permissive gate. Removing an alarm adds a MEDIUM change finding. These change checks are additional to the 42 catalog policies.

Findings are matched by stable `(resourceId, ruleId)`. A failed baseline finding that is still failing is EXISTING. A new failure is NEW. An old failure absent from proposed non-passing checks is RESOLVED. Unknowns do not silently resolve failures. Resource identity changes appear as create/delete. Changes are SAFE, NEUTRAL, RISK_INCREASE or RISK_REDUCTION relative to covered controls; they do not prove application-level safety. Static template diffs cannot determine every CloudFormation replacement; supply a complete change set for that.

## Exceptions and organization policies

Suppressions require exact rule/resource IDs, a nonblank reason and an expiration after today's UTC date. They are stored with the review, never applied to the cache, and remain visible in findings. Expired/duplicate/unknown exceptions are rejected. Production policy and suppression approval must be controlled by a trusted CI configuration, since this single-tenant API accepts caller-provided gates and exceptions.

Override `RULES_LOCATION` with a trusted filesystem glob, for example `file:./company-rules/**/*.json`, and restart to load new rules without recompiling. This replaces the catalog; copy default rules if you want to extend them. Duplicate IDs and incomplete metadata fail startup. Increase `version` when changing a rule. `docs/examples/agent-platform-lens.json` illustrates six organization policies for an explicitly supplied `ChangeGuard::Agent::Workload` resource. This local policy pack is not an AWS-published custom lens.
