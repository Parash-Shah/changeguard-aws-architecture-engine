package com.changeguard.parser;

import com.changeguard.model.CloudResource;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class TerraformPlanParser implements InfrastructureParser {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String, String> TYPES = Map.ofEntries(
        Map.entry("aws_db_instance", "AWS::RDS::DBInstance"), Map.entry("aws_s3_bucket", "AWS::S3::Bucket"),
        Map.entry("aws_iam_policy", "AWS::IAM::Policy"), Map.entry("aws_iam_role", "AWS::IAM::Role"),
        Map.entry("aws_security_group", "AWS::EC2::SecurityGroup"), Map.entry("aws_sqs_queue", "AWS::SQS::Queue"),
        Map.entry("aws_instance", "AWS::EC2::Instance"), Map.entry("aws_ebs_volume", "AWS::EC2::Volume"),
        Map.entry("aws_dynamodb_table", "AWS::DynamoDB::Table"), Map.entry("aws_lambda_function", "AWS::Lambda::Function"),
        Map.entry("aws_cloudwatch_log_group", "AWS::Logs::LogGroup"), Map.entry("aws_cloudtrail", "AWS::CloudTrail::Trail"),
        Map.entry("aws_elasticache_replication_group", "AWS::ElastiCache::ReplicationGroup"),
        Map.entry("aws_autoscaling_group", "AWS::AutoScaling::AutoScalingGroup"), Map.entry("aws_ecs_service", "AWS::ECS::Service"),
        Map.entry("aws_sns_topic", "AWS::SNS::Topic"), Map.entry("aws_ecr_repository", "AWS::ECR::Repository"),
        Map.entry("aws_cloudwatch_metric_alarm", "AWS::CloudWatch::Alarm"));
    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("multi_az", "MultiAZ"), Map.entry("kms_key_id", "KmsMasterKeyId"),
        Map.entry("sqs_managed_sse_enabled", "SqsManagedSseEnabled"), Map.entry("sse_algorithm", "SSEAlgorithm"),
        Map.entry("cidr_blocks", "CidrIp"), Map.entry("ipv6_cidr_blocks", "CidrIpv6"), Map.entry("protocol", "IpProtocol"),
        Map.entry("ingress", "SecurityGroupIngress"), Map.entry("policy", "PolicyDocument"), Map.entry("inline_policy", "Policies"),
        Map.entry("enable_logging", "IsLogging"), Map.entry("is_multi_region_trail", "IsMultiRegionTrail"),
        Map.entry("point_in_time_recovery", "PointInTimeRecoverySpecification"), Map.entry("deployment_circuit_breaker", "DeploymentCircuitBreaker"),
        Map.entry("min_size", "MinSize"), Map.entry("max_size", "MaxSize"), Map.entry("desired_count", "DesiredCount"),
        Map.entry("enabled_cloudwatch_logs_exports", "EnableCloudwatchLogsExports"));
    public List<CloudResource> parse(String template) { return parsePlan(template, false); }
    public List<CloudResource> before(String template) { return parsePlan(template, true); }
    public JsonNode tree(String template) {
        try {
            if (template == null || template.length() > 10_000_000) throw new IllegalArgumentException("Plan exceeds 10 MB");
            JsonNode root = mapper.readTree(template);
            if (root == null || !root.path("format_version").asText().matches("1\\.\\d+") || !root.path("resource_changes").isArray())
                throw new IllegalArgumentException("Expected Terraform plan JSON format 1.x with resource_changes");
            return root;
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid Terraform plan JSON"); }
    }
    private List<CloudResource> parsePlan(String template, boolean before) {
        JsonNode root = tree(template);
        Map<String, CloudResource> resources = new TreeMap<>();
        JsonNode module = before ? root.path("prior_state").path("values").path("root_module") : root.path("planned_values").path("root_module");
        readModule(module, resources);
        for (JsonNode item : root.path("resource_changes")) {
            if ("data".equals(item.path("mode").asText())) continue;
            if (!item.path("change").path("actions").isArray()) throw new IllegalArgumentException("Missing Terraform change actions");
            String id = item.path("address").asText();
            JsonNode values = item.path("change").path(before ? "before" : "after");
            if (values.isMissingNode()) throw new IllegalArgumentException("Missing Terraform change values");
            if (values.isNull()) resources.remove(id);
            else {
                Map<String, Object> properties = normalized(values);
                if (!before) overlayUnknown(properties, item.path("change").path("after_unknown"));
                resources.put(id, new CloudResource(id, TYPES.getOrDefault(item.path("type").asText(), "Terraform::" + item.path("type").asText()), properties));
            }
        }
        if (resources.size() > 10000) throw new IllegalArgumentException("Maximum 10000 resources");
        return List.copyOf(resources.values());
    }
    private void readModule(JsonNode module, Map<String, CloudResource> result) {
        for (JsonNode resource : module.path("resources")) {
            if ("data".equals(resource.path("mode").asText())) continue;
            String id = resource.path("address").asText();
            result.put(id, new CloudResource(id, TYPES.getOrDefault(resource.path("type").asText(), "Terraform::" + resource.path("type").asText()), normalized(resource.path("values"))));
        }
        for (JsonNode child : module.path("child_modules")) readModule(child, result);
    }
    private Map<String, Object> normalized(JsonNode values) {
        if (!values.isObject()) throw new IllegalArgumentException("Terraform resource values must be an object");
        Map<String, Object> result = new TreeMap<>();
        values.fields().forEachRemaining(e -> result.put(name(e.getKey()), normalize(e.getValue())));
        // Terraform provider singleton blocks need canonical CloudFormation property shapes.
        Object recovery = result.get("PointInTimeRecoverySpecification");
        if (recovery instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof Map<?,?> block)
            result.put("PointInTimeRecoverySpecification", Map.of("PointInTimeRecoveryEnabled", block.getOrDefault("Enabled", null) == null ? Map.of("__unknown",true) : block.get("Enabled")));
        Object circuit = result.remove("DeploymentCircuitBreaker");
        if (circuit instanceof List<?> list && list.size() == 1)
            result.put("DeploymentConfiguration", Map.of("DeploymentCircuitBreaker",list.getFirst()));
        return result;
    }
    private Object normalize(JsonNode value) {
        if (value.isObject()) return normalized(value);
        if (value.isArray()) { List<Object> list = new ArrayList<>(); value.forEach(v -> list.add(normalize(v))); return list; }
        if (value.isTextual() && value.asText().stripLeading().startsWith("{")) {
            try { return mapper.readValue(value.asText(), new TypeReference<Map<String, Object>>() {}); } catch (Exception ignored) { /* plain text */ }
        }
        return mapper.convertValue(value, Object.class);
    }
    private void overlayUnknown(Map<String, Object> properties, JsonNode unknown) {
        if (unknown.isBoolean() && unknown.asBoolean()) { properties.put("__unknown",true); return; }
        unknown.fields().forEachRemaining(e -> {
            if (containsTrue(e.getValue())) properties.put(name(e.getKey()), Map.of("__unknown", true));
        });
    }
    private boolean containsTrue(JsonNode value) {
        if (value.isBoolean()) return value.asBoolean();
        for (JsonNode child : value) if (containsTrue(child)) return true;
        return false;
    }
    private static String name(String key) {
        if (NAMES.containsKey(key)) return NAMES.get(key);
        StringBuilder result = new StringBuilder();
        for (String part : key.split("_")) if (!part.isEmpty()) result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        return result.toString();
    }
}
