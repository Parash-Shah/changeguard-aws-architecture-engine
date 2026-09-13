package com.changeguard.parser;
import com.changeguard.evaluation.ResourceChange;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ChangeParser {
    private final ObjectMapper mapper;
    public ChangeParser(ObjectMapper mapper) { this.mapper = mapper; }
    public List<ResourceChange> cloudFormation(String json) {
        if (json == null) return List.of();
        try {
            JsonNode root = mapper.readTree(json);
            if (!root.path("Changes").isArray()) throw new IllegalArgumentException("Change set must contain Changes array");
            if (root.hasNonNull("NextToken")) throw new IllegalArgumentException("Merge all change-set pages before reviewing");
            List<ResourceChange> result = new ArrayList<>();
            for (JsonNode change : root.path("Changes")) {
                JsonNode r = change.path("ResourceChange");
                if (!r.path("LogicalResourceId").isTextual() || !r.path("ResourceType").isTextual()) throw new IllegalArgumentException("Malformed resource change");
                var action = switch (r.path("Action").asText()) {
                    case "Add", "Import" -> ResourceChange.Action.CREATE;
                    case "Remove" -> ResourceChange.Action.DELETE;
                    case "Modify" -> r.path("Replacement").asText().equals("False") ? ResourceChange.Action.MODIFY : ResourceChange.Action.REPLACEMENT;
                    default -> throw new IllegalArgumentException("Unsupported change-set action");
                };
                List<String> properties = new ArrayList<>();
                for (JsonNode detail : r.path("Details")) if (detail.path("Target").path("Name").isTextual()) properties.add(detail.path("Target").path("Name").asText());
                result.add(new ResourceChange(r.path("LogicalResourceId").asText(), r.path("ResourceType").asText(), action, ResourceChange.Risk.NEUTRAL, List.copyOf(properties)));
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("Invalid CloudFormation change-set JSON"); }
    }
    public List<ResourceChange> terraform(JsonNode root, List<com.changeguard.model.CloudResource> before, List<com.changeguard.model.CloudResource> after) {
        Map<String, String> types = new HashMap<>(); before.forEach(r -> types.put(r.resourceId(), r.resourceType())); after.forEach(r -> types.put(r.resourceId(), r.resourceType()));
        List<ResourceChange> changes = new ArrayList<>();
        for (JsonNode item : root.path("resource_changes")) {
            if ("data".equals(item.path("mode").asText())) continue;
            List<String> actions = new ArrayList<>(); item.path("change").path("actions").forEach(a -> actions.add(a.asText()));
            if (actions.equals(List.of("no-op")) || actions.equals(List.of("read"))) continue;
            var action = actions.contains("delete") && actions.contains("create") ? ResourceChange.Action.REPLACEMENT
                    : actions.equals(List.of("delete")) ? ResourceChange.Action.DELETE
                    : actions.equals(List.of("create")) ? ResourceChange.Action.CREATE
                    : actions.equals(List.of("update")) ? ResourceChange.Action.MODIFY : null;
            if (action == null) throw new IllegalArgumentException("Unsupported Terraform change actions");
            // These resources become bucket properties; snapshot diffing captures additions/removals.
            // Unresolved attachments remain visible as unsupported resources in those snapshots.
            if (TerraformPlanParser.isS3Configuration(item.path("type").asText())) continue;
            String id = item.path("address").asText();
            changes.add(new ResourceChange(id, types.getOrDefault(id, "Terraform::" + item.path("type").asText()), action, ResourceChange.Risk.NEUTRAL, List.of()));
        }
        return List.copyOf(changes);
    }
}
