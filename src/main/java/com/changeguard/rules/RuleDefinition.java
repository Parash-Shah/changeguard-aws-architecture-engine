package com.changeguard.rules;
import com.changeguard.model.*;
import java.util.List;
public record RuleDefinition(String id, String version, String title, Pillar pillar, Severity severity,
        List<String> resourceTypes, String property, Operator operator, Object expected,
        String rationale, String recommendation, String documentation, boolean heuristic) {
    public enum Operator { EQUALS, GTE, LTE, BETWEEN, PRESENT, ALL_TRUE, NO_ADMIN, NO_PUBLIC_INGRESS, IN, CONTAINS, S3_ENCRYPTED }
    public RuleDefinition {
        if (id == null || id.isBlank() || version == null || title == null || pillar == null || severity == null
                || resourceTypes == null || resourceTypes.isEmpty() || property == null || operator == null
                || rationale == null || recommendation == null || documentation == null)
            throw new IllegalArgumentException("Incomplete rule metadata");
        resourceTypes = List.copyOf(resourceTypes);
        expected = freeze(expected);
        if (operator == Operator.BETWEEN && (!(expected instanceof List<?> bounds) || bounds.size() != 2
                || !(bounds.get(0) instanceof Number low) || !(bounds.get(1) instanceof Number high)
                || !Double.isFinite(low.doubleValue()) || !Double.isFinite(high.doubleValue()) || low.doubleValue() > high.doubleValue()))
            throw new IllegalArgumentException("BETWEEN requires finite numeric [minimum, maximum] bounds");
    }
    private static Object freeze(Object value) {
        if (value instanceof java.util.List<?> list) return list.stream().map(RuleDefinition::freeze).toList();
        if (value instanceof java.util.Map<?,?> map) {
            java.util.Map<String,Object> copy = new java.util.TreeMap<>();
            map.forEach((k,v) -> copy.put(k.toString(),freeze(v)));
            return java.util.Collections.unmodifiableMap(copy);
        }
        return value;
    }
}
