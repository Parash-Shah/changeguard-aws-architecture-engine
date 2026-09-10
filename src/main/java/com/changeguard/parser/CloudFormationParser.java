package com.changeguard.parser;

import com.changeguard.model.CloudResource;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class CloudFormationParser implements InfrastructureParser {
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public List<CloudResource> parse(String template) {
        try {
            if (template == null || template.length() > 10_000_000) throw new IllegalArgumentException("Template exceeds 10 MB");
            JsonNode root = mapper.readTree(template);
            if (root == null || !root.path("Resources").isObject()) throw new IllegalArgumentException("CloudFormation Resources must be an object");
            if (root.path("Resources").size() > 10000) throw new IllegalArgumentException("Maximum 10000 resources");
            List<CloudResource> result = new ArrayList<>();
            root.path("Resources").fields().forEachRemaining(entry -> {
                JsonNode resource = entry.getValue();
                if (!resource.path("Type").isTextual() || (resource.has("Properties") && !resource.path("Properties").isObject()))
                    throw new IllegalArgumentException("Malformed resource: " + entry.getKey());
                Map<String, Object> properties = resource.has("Properties")
                        ? mapper.convertValue(resource.get("Properties"), new TypeReference<Map<String, Object>>() {}) : new LinkedHashMap<>();
                if (resource.has("Condition")) properties.put("__condition", resource.get("Condition").asText());
                result.add(new CloudResource(entry.getKey(), resource.get("Type").asText(), properties));
            });
            return List.copyOf(result);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid CloudFormation JSON"); }
    }
}
