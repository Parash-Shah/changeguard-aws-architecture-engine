package com.changeguard.evaluation;
import com.changeguard.findings.Finding;
import com.changeguard.model.*;
import java.time.Instant;
import java.util.*;
public record ArchitectureReport(UUID reviewId, Instant createdAt, int score, Status status,
        Map<Severity, Long> summary, Map<Pillar, PillarScore> pillarScores,
        List<Finding> findings, List<Finding> resolvedFindings, List<ResourceChange> changes,
        List<String> gateReasons, int resources, int evaluations, List<String> unsupportedResourceTypes,
        long unknownChecks, long ruleErrors, int gateScore) {
    public enum Status { PASS, WARN, FAIL }
    public record PillarScore(Integer score, long evaluated, long unknown) { }
}
