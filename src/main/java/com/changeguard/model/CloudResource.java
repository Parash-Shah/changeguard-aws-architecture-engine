package com.changeguard.model;

import java.util.*;

public record CloudResource(String resourceId, String resourceType, Map<String, Object> properties) {
    public CloudResource {
        if (resourceId == null || resourceId.isBlank() || resourceType == null || resourceType.isBlank())
            throw new IllegalArgumentException("Resource ID and type are required");
        properties = freezeMap(properties);
    }
    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        Map<String, Object> copy = new TreeMap<>();
        source.forEach((k, v) -> copy.put(k, freeze(v)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new TreeMap<>();
            map.forEach((k, v) -> copy.put(k.toString(), freeze(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(CloudResource::freeze).toList();
        return value;
    }
}
