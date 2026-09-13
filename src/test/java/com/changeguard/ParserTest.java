package com.changeguard;
import com.changeguard.parser.*;
import com.changeguard.rules.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ParserTest {
    @Test void normalizesTerraformPerformanceInsights() throws Exception {
        String plan = """
            {"format_version":"1.2","resource_changes":[{"address":"aws_db_instance.orders","type":"aws_db_instance",
             "change":{"actions":["create"],"before":null,"after":{"performance_insights_enabled":true}}}]}
            """;
        var resource = new TerraformPlanParser().parse(plan).getFirst();
        var rule = TestSupport.catalog().rules().stream().filter(r -> r.id().equals("PERF-RDS-001")).findFirst().orElseThrow();
        assertThat(rule.evaluate(resource).passed()).isTrue();
        var root = TestSupport.MAPPER.readTree(plan);
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("resource_changes").get(0).path("change"))
                .putObject("after_unknown").put("performance_insights_enabled", true);
        assertThat(rule.evaluate(new TerraformPlanParser().parse(root.toString()).getFirst()).outcome()).isEqualTo(RuleResult.Outcome.UNKNOWN);
    }
    private final CloudFormationParser cfn = new CloudFormationParser();
    private final TerraformPlanParser tf = new TerraformPlanParser();
    @Test void parsesAllFixtureTypesAndFreezesNestedValues() throws Exception {
        var resources = cfn.parse(TestSupport.fixture("good/production.json"));
        assertThat(resources).hasSize(18);
        assertThatThrownBy(() -> resources.getFirst().properties().put("MultiAZ",false)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsMalformedTemplates() {
        for (String json : List.of("", "null", "[]", "{}", "{\"Resources\":[]}", "{\"Resources\":{\"Db\":{}}}", "{\"Resources\":{},\"Resources\":{}}"))
            assertThatThrownBy(() -> cfn.parse(json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void normalizesTerraformAcronymsAndBeforeAfter() throws Exception {
        var json = TestSupport.fixture("bad/terraform-public-rds.json");
        assertThat(tf.parse(json).getFirst().properties()).containsEntry("MultiAZ",false).containsEntry("PubliclyAccessible",true);
        assertThat(tf.before(json).getFirst().properties()).containsEntry("MultiAZ",true);
    }
    @Test void preservesTerraformUnknowns() throws Exception {
        var root = TestSupport.MAPPER.readTree(TestSupport.fixture("bad/terraform-public-rds.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)root.path("resource_changes").get(0).path("change").path("after_unknown")).put("storage_encrypted",true);
        assertThat(DeclarativeRule.unknown(tf.parse(root.toString()).getFirst().properties().get("StorageEncrypted"))).isTrue();
    }
    @Test void rejectsStateFilesAndUnsupportedPlanVersions() {
        assertThatThrownBy(() -> tf.parse("{\"format_version\":\"2.0\",\"resource_changes\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tf.parse("{\"format_version\":\"1.0\",\"values\":{}}")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void terraformPublicIpv6IngressIsNotLostDuringNormalization() throws Exception {
        String json="{\"format_version\":\"1.2\",\"resource_changes\":[{\"address\":\"aws_security_group.x\",\"type\":\"aws_security_group\",\"change\":{\"actions\":[\"create\"],\"before\":null,\"after\":{\"ingress\":[{\"protocol\":\"tcp\",\"from_port\":22,\"to_port\":22,\"ipv6_cidr_blocks\":[\"::/0\"]}]}}}]}";
        var rule=TestSupport.catalog().rules().stream().filter(r -> r.id().equals("SEC-EC2-001")).findFirst().orElseThrow();
        assertThat(rule.evaluate(tf.parse(json).getFirst()).passed()).isFalse();
    }
    @Test void trailingJsonIsRejected() {
        assertThatThrownBy(() -> cfn.parse("{\"Resources\":{}} {}" )).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readsNestedTerraformModules() {
        var json = "{\"format_version\":\"1.2\",\"resource_changes\":[],\"planned_values\":{\"root_module\":{\"child_modules\":[{\"resources\":[{\"address\":\"module.a.aws_ebs_volume.v\",\"type\":\"aws_ebs_volume\",\"values\":{\"encrypted\":true}}]}]}}}";
        assertThat(tf.parse(json)).hasSize(1);
    }
    @Test void parsesReplacementAndRejectsPaginatedChangeSet() throws Exception {
        var parser = new ChangeParser(TestSupport.MAPPER);
        assertThat(parser.cloudFormation(TestSupport.fixture("bad/replace-database.changeset.json")).getFirst().action().name()).isEqualTo("REPLACEMENT");
        assertThatThrownBy(() -> parser.cloudFormation("{\"Changes\":[],\"NextToken\":\"more\"}")).isInstanceOf(IllegalArgumentException.class);
    }
}
