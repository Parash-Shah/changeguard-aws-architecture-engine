package com.changeguard.rules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class RuleCatalog {
    private final List<ArchitectureRule> rules;
    public RuleCatalog(ObjectMapper mapper, @Value("${changeguard.rules-location:classpath*:rules/**/*.json}") String location) throws Exception {
        List<ArchitectureRule> loaded = new ArrayList<>();
        for (var file : new PathMatchingResourcePatternResolver().getResources(location))
            try (var stream = file.getInputStream()) {
                for (RuleDefinition definition : mapper.readValue(stream, RuleDefinition[].class)) loaded.add(new DeclarativeRule(definition));
            }
        if (loaded.isEmpty()) throw new IllegalStateException("No rules loaded from " + location);
        if (loaded.stream().map(ArchitectureRule::id).distinct().count() != loaded.size()) throw new IllegalStateException("Duplicate rule IDs");
        rules = loaded.stream().sorted(Comparator.comparing(ArchitectureRule::id)).toList();
    }
    public List<ArchitectureRule> rules() { return rules; }
}
