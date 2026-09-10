package com.changeguard.evaluation;

import com.changeguard.findings.Finding;
import com.changeguard.model.*;
import com.changeguard.rules.*;
import com.github.benmanes.caffeine.cache.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import static com.changeguard.rules.RuleResult.Outcome.*;
import static com.changeguard.findings.Finding.Classification.*;

@Service
public class ArchitectureEvaluationService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ArchitectureEvaluationService.class);
    private final List<ArchitectureRule> rules;
    private final ExecutorService executor;
    private final Cache<CacheKey, RuleResult> cache;
    private final MeterRegistry metrics;
    private final Clock clock;
    private record CacheKey(RuleDefinition rule, CloudResource resource) { }
    private record Check(ArchitectureRule rule, CloudResource resource, RuleResult result) {
        Finding finding() { return new Finding(rule.id(), rule.definition().version(), resource.resourceId(), rule.pillar(),
                result.severity(), result.outcome(), result.message(), result.recommendation(), NEW, false, null); }
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ArchitectureEvaluationService(RuleCatalog catalog, MeterRegistry metrics,
            @Value("${changeguard.parallelism:1}") int threads, @Value("${changeguard.cache-size:50000}") long cacheSize) {
        this(catalog.rules(), metrics, threads, cacheSize, Clock.systemUTC());
    }
    public ArchitectureEvaluationService(List<ArchitectureRule> rules, MeterRegistry metrics, int threads, long cacheSize, Clock clock) {
        if (threads < 1 || threads > 16) throw new IllegalArgumentException("Evaluation threads must be between 1 and 16");
        this.rules = List.copyOf(rules); this.metrics = metrics; this.clock = clock;
        executor = threads == 1 ? null : new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
        cache = Caffeine.newBuilder().maximumSize(cacheSize).expireAfterAccess(Duration.ofMinutes(10)).build();
    }
    @PreDestroy public void close() { if (executor != null) executor.close(); cache.invalidateAll(); }
    public ArchitectureReport evaluate(List<CloudResource> proposed, List<CloudResource> baseline,
            List<ResourceChange> explicitChanges, QualityGate gate, List<Suppression> suppressions) {
        var timer = io.micrometer.core.instrument.Timer.start(metrics);
        try {
            validateResources(proposed); if (baseline != null) validateResources(baseline);
            validateSuppressions(suppressions, proposed);
            List<Check> checks = checks(proposed);
            Map<String, Finding> old = baseline == null ? Map.of() : checks(baseline).stream()
                    .filter(c -> c.result().outcome() == FAIL).map(Check::finding).collect(Collectors.toMap(Finding::key, f -> f));
            List<Finding> findings = new ArrayList<>(checks.stream().filter(c -> !c.result().passed()).map(Check::finding)
                    .map(f -> old.containsKey(f.key()) && f.outcome() == FAIL ? f.classify(EXISTING) : f).toList());
            Set<String> active = findings.stream().map(Finding::key).collect(Collectors.toSet());
            List<Finding> resolved = old.values().stream().filter(f -> !active.contains(f.key())).map(f -> f.classify(RESOLVED))
                    .sorted(Comparator.comparing(Finding::key)).toList();
            List<ResourceChange> changes = changes(proposed, baseline, explicitChanges, findings, resolved);
            for (ResourceChange change : changes) if (stateful(change.resourceType())
                    && (change.action() == ResourceChange.Action.DELETE || change.action() == ResourceChange.Action.REPLACEMENT)) {
                findings.add(new Finding("CHANGE-STATEFUL-001", "1", change.resourceId(), Pillar.RELIABILITY, Severity.CRITICAL, FAIL,
                        "Stateful resource " + change.action().name().toLowerCase(Locale.ROOT) + " requires manual review",
                        "Verify backups, migration, retention and rollback before deployment", NEW, false, null));
            }
            for (ResourceChange change : changes) if (change.resourceType().equals("AWS::CloudWatch::Alarm") && change.action() == ResourceChange.Action.DELETE)
                findings.add(new Finding("CHANGE-ALARM-001", "1", change.resourceId(), Pillar.OPERATIONAL_EXCELLENCE, Severity.MEDIUM, FAIL,
                        "CloudWatch alarm removed", "Verify that replacement monitoring covers the workload", NEW, false, null));
            findings = findings.stream().map(f -> applySuppression(f, suppressions)).sorted(Comparator.comparing(Finding::key)).toList();
            List<Finding> failures = findings.stream().filter(f -> f.outcome() == FAIL && !f.suppressed()).toList();
            List<Finding> gated = failures.stream().filter(f -> !gate.regressionsOnly() || f.classification() == NEW).toList();
            int score = score(failures), gateScore = score(gated);
            long unknown = findings.stream().filter(f -> f.outcome() == UNKNOWN).count();
            long errors = findings.stream().filter(f -> f.outcome() == RULE_ERROR).count();
            List<String> unsupported = proposed.stream().filter(r -> rules.stream().noneMatch(rule -> rule.supports(r)))
                    .map(CloudResource::resourceType).distinct().sorted().toList();
            List<String> reasons = new ArrayList<>();
            if (gateScore < gate.minimumScore()) reasons.add("Gate score " + gateScore + " is below " + gate.minimumScore());
            if (gated.stream().anyMatch(f -> gate.failOn().contains(f.severity()))) reasons.add("Disallowed finding severity");
            if (gated.stream().filter(f -> f.severity() == Severity.HIGH).count() > gate.maxHighFindings()) reasons.add("Too many HIGH findings");
            if (findings.stream().anyMatch(f -> f.ruleId().equals("CHANGE-STATEFUL-001"))) reasons.add("Destructive stateful change requires manual review");
            if (errors > 0) reasons.add("Rule evaluation errors prevent a complete review");
            // Unknowns and unsupported resource types cannot provide evidence to authorize deployment.
            if (unknown > 0 || !unsupported.isEmpty()) reasons.add("Incomplete analysis: unknown checks or unsupported resource types");
            var status = !reasons.isEmpty() ? ArchitectureReport.Status.FAIL : failures.isEmpty() ? ArchitectureReport.Status.PASS : ArchitectureReport.Status.WARN;
            Map<Severity, Long> summary = new EnumMap<>(Severity.class);
            for (Severity severity : Severity.values()) summary.put(severity, failures.stream().filter(f -> f.severity() == severity).count());
            Map<Pillar, ArchitectureReport.PillarScore> pillars = new EnumMap<>(Pillar.class);
            for (Pillar pillar : Pillar.values()) {
                long evaluated = checks.stream().filter(c -> c.rule().pillar() == pillar).count();
                long incomplete = checks.stream().filter(c -> c.rule().pillar() == pillar && (c.result().outcome() == UNKNOWN || c.result().outcome() == RULE_ERROR)).count();
                pillars.put(pillar, new ArchitectureReport.PillarScore(evaluated == 0 ? null : score(failures.stream().filter(f -> f.pillar() == pillar).toList()), evaluated, incomplete));
            }
            metrics.counter("changeguard_reviews", "status", status.name()).increment();
            for (Finding f : findings) {
                metrics.counter("changeguard_findings", "pillar", f.pillar().name(), "severity", f.severity().name()).increment();
                if (f.severity() == Severity.CRITICAL && f.outcome() == FAIL) metrics.counter("changeguard_critical_findings").increment();
                if (f.suppressed()) metrics.counter("suppressed_findings").increment();
                if (f.classification() == NEW && f.outcome() == FAIL && baseline != null) metrics.counter("regressions_detected").increment();
            }
            if (status == ArchitectureReport.Status.FAIL) metrics.counter("policy_gate_failures").increment();
            metrics.summary("architecture_score").record(score);
            return new ArchitectureReport(UUID.randomUUID(), clock.instant(), score, status, summary, pillars, findings, resolved, changes,
                    List.copyOf(reasons), proposed.size(), checks.size(), unsupported, unknown, errors, gateScore);
        } finally { timer.stop(metrics.timer("review_latency")); }
    }
    private List<Check> checks(List<CloudResource> resources) {
        if (executor == null) return resources.stream().flatMap(r -> checkResource(r).stream()).toList();
        List<CompletableFuture<List<Check>>> futures = resources.stream()
                .map(r -> CompletableFuture.supplyAsync(() -> checkResource(r), executor)).toList();
        return futures.stream().flatMap(f -> f.join().stream()).toList();
    }
    private List<Check> checkResource(CloudResource resource) {
        List<Check> results = new ArrayList<>();
        for (ArchitectureRule rule : rules) if (rule.supports(resource)) {
            RuleResult result;
            try {
                result = cache.get(new CacheKey(rule.definition(), resource), key -> {
                    var sample = io.micrometer.core.instrument.Timer.start(metrics);
                    try { return Objects.requireNonNull(rule.evaluate(resource)); }
                    finally { sample.stop(metrics.timer("rule_evaluation_latency", "rule", rule.id())); }
                });
            } catch (RuntimeException e) {
                LOG.warn("Rule {} failed with {}", rule.id(), e.getClass().getSimpleName());
                result = new RuleResult(RULE_ERROR, rule.definition().severity(), "Rule evaluation failed", "Inspect service logs and policy implementation");
            }
            results.add(new Check(rule, resource, result));
        }
        return results;
    }
    private static int score(List<Finding> findings) { return (int) Math.max(0L, 100L - findings.stream().mapToLong(f -> f.severity().penalty()).sum()); }
    private void validateSuppressions(List<Suppression> suppressions, List<CloudResource> resources) {
        Set<String> keys = new HashSet<>();
        for (Suppression s : suppressions) {
            if (s.rule() == null || s.resource() == null || s.reason() == null || s.reason().isBlank() || s.expires() == null)
                throw new IllegalArgumentException("Suppressions require rule, resource, reason and expiration");
            if (!s.expires().isAfter(LocalDate.now(clock))) throw new IllegalArgumentException("Suppression must expire after today (UTC)");
            if (!keys.add(s.rule() + "\u0000" + s.resource())) throw new IllegalArgumentException("Duplicate suppression");
            if (rules.stream().noneMatch(r -> r.id().equals(s.rule()) && resources.stream().anyMatch(resource -> resource.resourceId().equals(s.resource()) && r.supports(resource))))
                throw new IllegalArgumentException("Suppression must reference an applicable rule and resource");
        }
    }
    private Finding applySuppression(Finding f, List<Suppression> suppressions) {
        if (f.outcome() != FAIL || f.ruleId().startsWith("CHANGE-")) return f;
        return suppressions.stream().filter(s -> s.rule().equals(f.ruleId()) && s.resource().equals(f.resourceId()))
                .findFirst().map(s -> f.suppress(s.reason())).orElse(f);
    }
    private static void validateResources(List<CloudResource> resources) {
        if (resources.size() > 10000 || resources.stream().map(CloudResource::resourceId).distinct().count() != resources.size())
            throw new IllegalArgumentException("Too many resources or duplicate IDs");
    }
    private static boolean stateful(String type) { return Set.of("AWS::RDS::DBInstance", "AWS::DynamoDB::Table", "AWS::S3::Bucket", "AWS::EC2::Volume", "AWS::ElastiCache::ReplicationGroup").contains(type); }
    private List<ResourceChange> changes(List<CloudResource> proposed, List<CloudResource> baseline, List<ResourceChange> explicit,
            List<Finding> findings, List<Finding> resolved) {
        Map<String, CloudResource> old = baseline == null ? Map.of() : baseline.stream().collect(Collectors.toMap(CloudResource::resourceId, r -> r));
        Map<String, CloudResource> next = proposed.stream().collect(Collectors.toMap(CloudResource::resourceId, r -> r));
        Map<String, ResourceChange> result = new TreeMap<>();
        if (baseline != null) {
            Set<String> ids = new TreeSet<>(old.keySet()); ids.addAll(next.keySet());
            for (String id : ids) {
                CloudResource a = old.get(id), b = next.get(id);
                if (Objects.equals(a, b)) continue;
                var action = a == null ? ResourceChange.Action.CREATE : b == null ? ResourceChange.Action.DELETE
                        : !a.resourceType().equals(b.resourceType()) ? ResourceChange.Action.REPLACEMENT : ResourceChange.Action.MODIFY;
                Set<String> properties = new TreeSet<>(); if (a != null) properties.addAll(a.properties().keySet()); if (b != null) properties.addAll(b.properties().keySet());
                properties.removeIf(k -> a != null && b != null && Objects.equals(a.properties().get(k), b.properties().get(k)));
                result.put(id, new ResourceChange(id, b == null ? a.resourceType() : b.resourceType(), action, ResourceChange.Risk.NEUTRAL, List.copyOf(properties)));
            }
        }
        for (ResourceChange change : explicit) {
            CloudResource target = change.action() == ResourceChange.Action.DELETE ? old.get(change.resourceId()) : next.get(change.resourceId());
            if (target == null || !target.resourceType().equals(change.resourceType())
                    || change.action() == ResourceChange.Action.DELETE && next.containsKey(change.resourceId()))
                throw new IllegalArgumentException("Change metadata does not match the supplied snapshots: " + change.resourceId());
            ResourceChange inferred = result.get(change.resourceId());
            if (inferred != null && inferred.action() == ResourceChange.Action.REPLACEMENT && change.action() != ResourceChange.Action.REPLACEMENT)
                throw new IllegalArgumentException("Change metadata cannot downgrade an inferred replacement");
            result.put(change.resourceId(), new ResourceChange(change.resourceId(), change.resourceType(), change.action(), change.risk(),
                    change.changedProperties().isEmpty() && inferred != null ? inferred.changedProperties() : change.changedProperties()));
        }
        return result.values().stream().map(c -> {
            boolean increase = findings.stream().anyMatch(f -> f.resourceId().equals(c.resourceId()) && f.classification() == NEW && f.outcome() == FAIL)
                    || c.resourceType().equals("AWS::CloudWatch::Alarm") && c.action() == ResourceChange.Action.DELETE
                    || stateful(c.resourceType()) && (c.action() == ResourceChange.Action.DELETE || c.action() == ResourceChange.Action.REPLACEMENT);
            boolean reduction = resolved.stream().anyMatch(f -> f.resourceId().equals(c.resourceId()));
            var risk = increase ? ResourceChange.Risk.RISK_INCREASE : reduction ? ResourceChange.Risk.RISK_REDUCTION
                    : c.action() == ResourceChange.Action.CREATE && rules.stream().anyMatch(r -> r.definition().resourceTypes().contains(c.resourceType()))
                        && findings.stream().noneMatch(f -> f.resourceId().equals(c.resourceId())) ? ResourceChange.Risk.SAFE : ResourceChange.Risk.NEUTRAL;
            return new ResourceChange(c.resourceId(), c.resourceType(), c.action(), risk, c.changedProperties());
        }).toList();
    }
}
