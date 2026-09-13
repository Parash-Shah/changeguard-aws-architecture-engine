package com.changeguard.rules;

import com.changeguard.model.CloudResource;
import java.util.*;
import static com.changeguard.rules.RuleResult.Outcome.*;

public record DeclarativeRule(RuleDefinition definition) implements ArchitectureRule {
    public RuleResult evaluate(CloudResource resource) {
        Object value = resource.properties();
        for (String part : definition.property().split("\\.")) {
            if (value instanceof Map<?, ?> m && m.keySet().stream().anyMatch(k -> k.equals("Ref") || k.toString().startsWith("Fn::") || k.equals("__unknown")))
                return result(UNKNOWN, "Value cannot be resolved statically");
            value = value instanceof Map<?, ?> map ? map.get(part) : null;
        }
        if (resource.properties().containsKey("__condition") || unknown(value))
            return result(UNKNOWN, "Value or resource condition cannot be resolved statically");
        boolean passed = switch (definition.operator()) {
            case EQUALS -> Objects.equals(value, definition.expected());
            case PRESENT -> value != null && (!(value instanceof Collection<?> c) || !c.isEmpty())
                    && (!(value instanceof Map<?, ?> m) || !m.isEmpty()) && !"".equals(value);
            case GTE -> numeric(value) != null && numeric(value) >= ((Number) definition.expected()).doubleValue();
            case LTE -> numeric(value) != null && numeric(value) <= ((Number) definition.expected()).doubleValue();
            case BETWEEN -> numeric(value) != null
                    && numeric(value) >= ((Number) ((List<?>) definition.expected()).get(0)).doubleValue()
                    && numeric(value) <= ((Number) ((List<?>) definition.expected()).get(1)).doubleValue();
            case IN -> ((List<?>) definition.expected()).contains(value);
            case CONTAINS -> value instanceof Collection<?> c && c.contains(definition.expected());
            case ALL_TRUE -> value instanceof Map<?, ?> m && ((List<?>) definition.expected()).stream().allMatch(k -> Boolean.TRUE.equals(m.get(k)));
            case NO_ADMIN -> value != null && !admin(value);
            case NO_PUBLIC_INGRESS -> value != null && !publicIngress(value);
            case S3_ENCRYPTED -> value instanceof List<?> configurations && !configurations.isEmpty()
                    && configurations.stream().allMatch(c -> c instanceof Map<?,?> configuration
                        && configuration.get("ServerSideEncryptionByDefault") instanceof Map<?,?> defaults
                        && Set.of("AES256", "aws:kms", "aws:kms:dsse").contains(String.valueOf(defaults.get("SSEAlgorithm"))));
        };
        return result(passed ? PASS : FAIL, passed ? "Policy satisfied" : definition.title());
    }
    private RuleResult result(RuleResult.Outcome outcome, String message) {
        return new RuleResult(outcome, definition.severity(), message, definition.recommendation());
    }
    private static Double numeric(Object value) {
        try {
            double number = value instanceof Number n ? n.doubleValue() : value instanceof String s ? Double.parseDouble(s) : Double.NaN;
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException e) { return null; }
    }
    public static boolean unknown(Object value) {
        if (value instanceof Map<?, ?> m) return m.keySet().stream().anyMatch(k -> k.equals("Ref") || k.toString().startsWith("Fn::") || k.equals("__unknown"))
                || m.values().stream().anyMatch(DeclarativeRule::unknown);
        return value instanceof Collection<?> c && c.stream().anyMatch(DeclarativeRule::unknown);
    }
    private static boolean wildcard(Object value) {
        return value instanceof String s && s.contains("*") || value instanceof Collection<?> c && c.stream().anyMatch(DeclarativeRule::wildcard);
    }
    private static boolean admin(Object value) {
        if (value instanceof Map<?, ?> m) {
            if ("Allow".equals(m.get("Effect")) && (wildcard(m.get("Action")) || m.containsKey("NotAction"))
                    && (wildcard(m.get("Resource")) || m.containsKey("NotResource"))) return true;
            return m.values().stream().anyMatch(DeclarativeRule::admin);
        }
        return value instanceof Collection<?> c && c.stream().anyMatch(DeclarativeRule::admin);
    }
    private static boolean publicIngress(Object value) {
        if (value instanceof Map<?, ?> m) {
            if (publicCidr(m.get("CidrIp")) || publicCidr(m.get("CidrIpv6"))) {
                String protocol = String.valueOf(m.get("IpProtocol"));
                if ("-1".equals(protocol)) return true;
                // ICMP type/code values are not TCP or UDP ports.
                if (Set.of("icmp", "icmpv6", "1", "58").contains(protocol)) return false;
                Double from = numeric(m.get("FromPort")), to = numeric(m.get("ToPort"));
                if (from == null || to == null || from < 0 || to > 65535 || from > to
                        || from != Math.rint(from) || to != Math.rint(to)) return true;
                    for (int port : new int[]{22, 3389, 3306, 5432, 1433, 27017, 6379})
                        if (from <= port && to >= port) return true;
            }
            return m.values().stream().anyMatch(DeclarativeRule::publicIngress);
        }
        return value instanceof Collection<?> c && c.stream().anyMatch(DeclarativeRule::publicIngress);
    }
    private static boolean publicCidr(Object value) {
        return "0.0.0.0/0".equals(value) || "::/0".equals(value) || value instanceof Collection<?> c && c.stream().anyMatch(DeclarativeRule::publicCidr);
    }
}
