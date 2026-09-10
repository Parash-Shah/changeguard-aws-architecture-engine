package com.changeguard.rules;
import com.changeguard.model.*;
public interface ArchitectureRule {
    RuleDefinition definition();
    default String id() { return definition().id(); }
    default Pillar pillar() { return definition().pillar(); }
    default boolean supports(CloudResource resource) { return definition().resourceTypes().contains(resource.resourceType()); }
    RuleResult evaluate(CloudResource resource);
}
