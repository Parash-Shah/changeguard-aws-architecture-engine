# AWS deployment example

Creates an internal HTTPS ALB, two private Fargate tasks, an ECS cluster, log retention, execution role, and scoped Secrets Manager access. Supply an existing VPC, private subnets in two AZs, ACM certificate, container image, and private PostgreSQL database. Configure the database security group to allow 5432 from the output API security group. Private subnets need NAT or the appropriate ECR/Logs/Secrets Manager endpoints. Customer-managed secret KMS keys need an additional scoped decrypt grant.

```sh
terraform init
terraform fmt -check
terraform validate
terraform plan -out=tfplan
terraform show -json tfplan > tfplan.json
```

No AWS resources are created by validation. Review cost, networking, database backup/encryption/deletion protection, secret rotation, and image provenance before applying. This is a deployment example, not a complete production landing zone. AWS Well-Architected integration stays disabled; add a scoped task role when enabling it. The API's single-key authorization is not multi-tenant isolation.
