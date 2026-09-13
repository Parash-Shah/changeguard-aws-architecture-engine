# AWS review context

The optional adapter complements local policy evaluation with AWS Well-Architected workload history. Enable it with `AWS_INTEGRATION_ENABLED=true` and `AWS_REGION`; credentials use the SDK default chain. No AWS calls occur when the adapter is disabled. Unit tests use a mocked SDK client, including throttling; local verification does not establish live AWS connectivity.

All routes below begin with `/v1/aws/workloads`. Supply the configured `X-API-Key` header. Workload IDs come from AWS and are regional; local ChangeGuard review UUIDs are separate identifiers.

## Local browser login

The SDK includes the `signin` module required for AWS CLI browser-login profiles. No access keys belong in Java, application configuration, or this repository. See [AWS Java login guidance](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-temporary.html).

Sign in with AWS CLI v2 as your limited-access IAM user, then select the profile in the Java process:

```powershell
& "C:\Program Files\Amazon\AWSCLIV2\aws.exe" login --profile changeguard --region us-east-1
$env:AWS_PROFILE = 'changeguard'
$env:AWS_REGION = 'us-east-1'
$env:AWS_INTEGRATION_ENABLED = 'true'
```

For IntelliJ, set those three environment variables in the application run configuration. For the CLI login, the IAM user needs `SignInLocalDevelopmentAccess`; Well-Architected reads require their own permissions, such as `WellArchitectedConsoleReadOnlyAccess`. Login sessions expire and must then be renewed through `aws login`. Docker does not automatically inherit the Windows profile/cache files.

Before a workload exists, verify SDK authentication with the explicit read-only discovery probe:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/aws-probe.ps1
# Existing workload reads:
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/aws-probe.ps1 -Mode Review -WorkloadId YOUR_WORKLOAD_ID
# Explicitly create and compare two milestones:
# powershell -NoProfile -ExecutionPolicy Bypass -File scripts/aws-probe.ps1 -Mode Milestones -WorkloadId YOUR_WORKLOAD_ID -RunToken YOUR_STABLE_RUN_TOKEN
```

This workstation command uses its local JDK and Windows' certificate trust store with TLS verification enabled. On other machines, use `mvn`, your Java 21 executable and the appropriate trusted CA configuration. The `exec` goal starts a separate Java process so SDK daemon threads cannot linger in Maven's JVM. Successful discovery records only the region, workload count and timestamp in `reports/aws-live-probe.json`; it does not create workloads or milestones. Zero workloads is a successful authentication/read result, not a completed workload review. Normal tests never invoke this probe.

September 13 verification: the Java SDK successfully called `ListWorkloads` using the `changeguard` browser-login profile in `us-east-1`, returning zero workloads. The SDK upgrade passed 217 Java tests, including six PostgreSQL integration tests. See [live discovery evidence](results/aws-discovery.json). Later that day, workload creation, Java workload/lens/answer reads and creation/comparison of two real milestones passed. See [milestone evidence](results/aws-milestones.json) and [read evidence](results/aws-workload-review.json). The workload is still unanswered; equal risk counts do not claim risk reduction.

## Routes

| Method and route | Behavior |
|---|---|
| `POST /` | Create a workload with the caller's stable client token |
| `GET /{id}` | Reference an existing workload and read its environment, lenses and risk counts |
| `GET /{id}/milestones?maxResults=50&nextToken=...` | Read one page; repeat with the returned token until it is null |
| `POST /{id}/milestones` | Create a milestone with `name` and a stable `clientToken` |
| `GET /{id}/lens-review?lens=wellarchitected&milestone=1` | Read lens context at a milestone; omit milestone for current context |
| `GET /{id}/answer?lens=wellarchitected&question=...&milestone=1` | Read an individual historical or current answer |
| `GET /{id}/report?lens=wellarchitected&milestone=1` | Return AWS's base64 report |
| `GET /{id}/comparison?lens=wellarchitected&from=1&to=2` | Compare risk-category counts from two recorded milestones |

The original `/lenses/{lens}`, `/lenses/{lens}/answers/{question}` and `/lenses/{lens}/report` routes remain supported. Prefer the query routes for custom lens ARNs; URL-encode query values normally.

Example workload body:

```json
{
  "name": "checkout",
  "description": "Checkout service architecture review",
  "owner": "platform-team",
  "regions": ["us-east-1"],
  "environment": "PREPRODUCTION",
  "lenses": ["wellarchitected"],
  "clientToken": "a-stable-unique-token-for-this-request"
}
```

Omitting environment and lenses preserves the original defaults: `PRODUCTION` and `wellarchitected`. Existing imported custom lens ARNs can be supplied in `lenses`. Creating a local policy catalog is independent of importing and publishing a lens in AWS.

Comparison returns both source count maps, both lens versions and `delta = after - before` for each category. A negative HIGH delta means fewer HIGH-risk answers; these counts are not ChangeGuard findings or score points. If lens versions differ or are unavailable, `comparable` is false and `delta` is empty. Both source maps remain available for manual interpretation. If either AWS request fails, the endpoint returns an error rather than a partial comparison.

Milestone numbers must be 1–100 and `from` must precede `to`. Page size must be 1–50. Each SDK call has a 20-second total timeout and five-second attempt timeout with SDK retry behavior; a comparison makes two calls. AWS dependency failures return HTTP 503. Reuse the same client token when retrying a write.

The runtime identity needs the corresponding `wellarchitected:GetWorkload`, `ListMilestones`, `GetLensReview`, `GetAnswer`, and `GetLensReviewReport` permissions for reads. Creation additionally needs `CreateWorkload` and `CreateMilestone`. Scope permissions to the intended workloads where supported. The adapter does not synchronize findings into AWS answers, publish custom lenses, or deploy infrastructure.

API contracts: [GetWorkload](https://docs.aws.amazon.com/wellarchitected/latest/APIReference/API_GetWorkload.html), [ListMilestones](https://docs.aws.amazon.com/wellarchitected/latest/APIReference/API_ListMilestones.html), [GetLensReview](https://docs.aws.amazon.com/wellarchitected/latest/APIReference/API_GetLensReview.html), [GetAnswer](https://docs.aws.amazon.com/wellarchitected/latest/APIReference/API_GetAnswer.html).

## Live pilot write permissions

The repository includes a concrete [pilot workload request](examples/aws-pilot-workload.json) and [additional write policy](examples/aws-pilot-write-policy.json) for the current pilot account and region. The policy supplements the existing read-only permissions with `CreateWorkload` in us-east-1 and `CreateMilestone` for workload ARNs in that account. It grants no compute, database, IAM-management or deletion actions. Once a workload exists, replace the milestone ARN wildcard with its exact workload ARN. Adapt account/region and generate your own stable client token when reusing the example elsewhere.

On September 13 the workload creation request was denied because the IAM identity lacked `wellarchitected:CreateWorkload`. After the administrator enabled writes, workload creation and the two-milestone probe succeeded. A transient second-milestone denial was recovered by retrying the same tokens, preserving the first milestone. No root credentials were used by ChangeGuard.

For AWS CLI calls on this workstation, use the certificate bundle for both API calls and login-token refresh:

```powershell
$env:AWS_CA_BUNDLE = (Resolve-Path '.tools/trusted-roots.pem').Path
```

A per-command `--ca-bundle` did not cover the CLI's token-refresh request in the observed environment. TLS verification remains enabled.
