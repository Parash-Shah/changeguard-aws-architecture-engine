package com.changeguard;

import com.changeguard.parser.*;
import com.changeguard.evaluation.*;
import com.changeguard.rules.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class TerraformConfigurationTest {
    private final TerraformPlanParser parser = new TerraformPlanParser();
    private ObjectNode plan() {
        var root = TestSupport.MAPPER.createObjectNode().put("format_version", "1.2");
        root.putArray("resource_changes");
        return root;
    }
    private ObjectNode add(ObjectNode root, String address, String type, Map<String,Object> before, Map<String,Object> after) {
        ObjectNode item = ((ArrayNode)root.path("resource_changes")).addObject().put("address", address).put("type", type);
        ObjectNode change = item.putObject("change");
        change.putArray("actions").add(before == null ? "create" : after == null ? "delete" : "update");
        change.set("before", TestSupport.MAPPER.valueToTree(before));
        change.set("after", TestSupport.MAPPER.valueToTree(after));
        return change;
    }
    private Map<String,Object> publicBlock(boolean enabled) {
        return Map.of("bucket", "orders", "block_public_acls", enabled, "ignore_public_acls", enabled,
                "block_public_policy", enabled, "restrict_public_buckets", enabled);
    }
    @Test void splitBucketConfigurationFeedsCanonicalRulesAndRegressionDiff() throws Exception {
        var root = plan();
        var bucket = Map.<String,Object>of("bucket", "orders", "tags", Map.of("Environment", "prod", "Owner", "team"));
        add(root, "module.app.aws_s3_bucket.data", "aws_s3_bucket", bucket, bucket);
        add(root, "module.app.aws_s3_bucket_public_access_block.data", "aws_s3_bucket_public_access_block", publicBlock(true), publicBlock(false));
        var versioning = Map.<String,Object>of("bucket", "orders", "versioning_configuration", List.of(Map.of("status", "Enabled")));
        add(root, "aws_s3_bucket_versioning.data", "aws_s3_bucket_versioning", versioning, versioning);
        var lifecycle = Map.<String,Object>of("bucket", "orders", "rule", List.of(Map.of("id", "expire", "status", "Enabled")));
        add(root, "aws_s3_bucket_lifecycle_configuration.data", "aws_s3_bucket_lifecycle_configuration", lifecycle, lifecycle);
        var before = parser.before(root.toString()); var after = parser.parse(root.toString());
        assertThat(after).hasSize(1);
        var explicit = new ChangeParser(TestSupport.MAPPER).terraform(root, before, after);
        try (var engine = TestSupport.engine()) {
            var report = engine.evaluate(after, before, explicit, QualityGate.defaults(), List.of());
            assertThat(report.status()).isEqualTo(ArchitectureReport.Status.FAIL);
            assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("SEC-S3-001") && f.classification().name().equals("NEW"));
            assertThat(report.changes()).anyMatch(c -> c.changedProperties().contains("PublicAccessBlockConfiguration"));
            assertThat(report.findings()).noneMatch(f -> f.outcome() == RuleResult.Outcome.UNKNOWN);
        }
    }
    @Test void removingPublicAccessConfigurationIsNotLost() {
        var root = plan(); var bucket = Map.<String,Object>of("bucket", "orders");
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", bucket, bucket);
        add(root, "aws_s3_bucket_public_access_block.data", "aws_s3_bucket_public_access_block", publicBlock(true), null);
        assertThat(parser.before(root.toString()).getFirst().properties()).containsKey("PublicAccessBlockConfiguration");
        assertThat(parser.parse(root.toString()).getFirst().properties()).doesNotContainKey("PublicAccessBlockConfiguration");
    }
    @Test void unknownTargetIsRetainedAsUnsupportedRatherThanGuessed() {
        var root = plan();
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", null, Map.of("bucket", "orders"));
        var change = add(root, "aws_s3_bucket_public_access_block.data", "aws_s3_bucket_public_access_block", null, publicBlock(true));
        change.putObject("after_unknown").put("bucket", true);
        assertThat(parser.parse(root.toString())).hasSize(2).anyMatch(r -> r.resourceType().startsWith("Terraform::"));
    }
    @Test void duplicateConfigurationOwnersProduceUnknown() {
        var root = plan();
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", null, Map.of("bucket", "orders"));
        add(root, "aws_s3_bucket_public_access_block.a", "aws_s3_bucket_public_access_block", null, publicBlock(true));
        add(root, "aws_s3_bucket_public_access_block.b", "aws_s3_bucket_public_access_block", null, publicBlock(false));
        assertThat(DeclarativeRule.unknown(parser.parse(root.toString()).getFirst().properties().get("PublicAccessBlockConfiguration"))).isTrue();
    }
    @Test void encryptionSingletonBlocksAreNormalized() {
        var root = plan();
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", null, Map.of("bucket", "orders"));
        add(root, "aws_s3_bucket_server_side_encryption_configuration.data", "aws_s3_bucket_server_side_encryption_configuration", null,
                Map.of("bucket", "orders", "rule", List.of(Map.of("apply_server_side_encryption_by_default", List.of(Map.of("sse_algorithm", "aws:kms"))))));
        assertThat(parser.parse(root.toString()).getFirst().properties()).containsEntry("BucketEncryption",
                Map.of("ServerSideEncryptionConfiguration", List.of(Map.of("ServerSideEncryptionByDefault", Map.of("SSEAlgorithm", "aws:kms")))));
    }
    @Test void unknownCircuitBreakerCannotUseKnownPlaceholder() throws Exception {
        var root = plan();
        var change = add(root, "aws_ecs_service.api", "aws_ecs_service", null,
                Map.of("deployment_circuit_breaker", List.of(Map.of("enable", true, "rollback", true))));
        change.putObject("after_unknown").putArray("deployment_circuit_breaker").addObject().put("rollback", true);
        var resource = parser.parse(root.toString()).getFirst();
        assertThat(DeclarativeRule.unknown(resource.properties().get("DeploymentConfiguration"))).isTrue();
        var rule = TestSupport.catalog().rules().stream().filter(r -> r.definition().property().startsWith("DeploymentConfiguration")).findFirst().orElseThrow();
        assertThat(rule.evaluate(resource).outcome()).isEqualTo(RuleResult.Outcome.UNKNOWN);
    }
    @Test void incompleteAndErroredPlansAreRejected() {
        for (String flag : List.of("complete", "errored")) {
            var root = plan().put(flag, flag.equals("errored"));
            assertThatThrownBy(() -> parser.parse(root.toString())).isInstanceOf(IllegalArgumentException.class);
        }
        var root = plan(); root.putArray("deferred_changes").addObject();
        assertThatThrownBy(() -> parser.parse(root.toString())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void duplicateAddressesAndMissingTypesAreRejected() {
        var root = plan();
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", null, Map.of());
        add(root, "aws_s3_bucket.data", "aws_s3_bucket", null, Map.of());
        assertThatThrownBy(() -> parser.parse(root.toString())).isInstanceOf(IllegalArgumentException.class);
        ((ArrayNode)root.path("resource_changes")).remove(1);
        ((ObjectNode)root.path("resource_changes").get(0)).remove("type");
        assertThatThrownBy(() -> parser.parse(root.toString())).isInstanceOf(IllegalArgumentException.class);
    }
}
