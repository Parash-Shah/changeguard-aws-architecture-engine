package com.changeguard.persistence;

import com.changeguard.api.ReviewRequest;
import com.changeguard.evaluation.*;
import com.changeguard.model.CloudResource;
import com.changeguard.parser.*;
import com.changeguard.rules.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class ReviewService {
    private final ReviewRepository repository;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CloudFormationParser cfn;
    private final TerraformPlanParser terraform;
    private final ChangeParser changes;
    private final ArchitectureEvaluationService engine;
    private final RuleCatalog catalog;
    public ReviewService(ReviewRepository repository, JdbcTemplate jdbc, ObjectMapper mapper, CloudFormationParser cfn,
            TerraformPlanParser terraform, ChangeParser changes, ArchitectureEvaluationService engine, RuleCatalog catalog) {
        this.repository = repository; this.jdbc = jdbc; this.mapper = mapper; this.cfn = cfn;
        this.terraform = terraform; this.changes = changes; this.engine = engine; this.catalog = catalog;
    }
    @Transactional
    public ArchitectureReport create(ReviewRequest request, String key) {
        String hash = hash(json(request));
        if (key != null) {
            if (key.isBlank() || key.length() > 200) throw new IllegalArgumentException("Idempotency-Key must be 1 to 200 characters");
            // Transaction-scoped PostgreSQL lock serializes duplicate requests across service replicas.
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> { }, key);
            var existing = repository.findByRequestKey(key);
            if (existing.isPresent()) {
                if (!existing.get().requestHash.equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency key reused with a different request");
                return read(existing.get().report, ArchitectureReport.class);
            }
        }
        InfrastructureParser parser = request.format() == ReviewRequest.Format.CLOUDFORMATION ? cfn : terraform;
        List<CloudResource> proposed = parser.parse(request.template());
        List<CloudResource> baseline = request.baselineTemplate() == null ? null : parser.parse(request.baselineTemplate());
        if (request.baselineReviewId() != null) {
            ReviewEntity stored = entity(request.baselineReviewId());
            if (!stored.format.equals(request.format().name())) throw new IllegalArgumentException("Baseline input formats must match");
            try { baseline = mapper.readValue(stored.snapshot, new TypeReference<List<CloudResource>>() {}); }
            catch (Exception e) { throw new IllegalStateException("Stored baseline cannot be read", e); }
        }
        List<ResourceChange> explicit;
        if (request.format() == ReviewRequest.Format.TERRAFORM_PLAN) {
            if (request.changeSet() != null) throw new IllegalArgumentException("changeSet is only valid with CloudFormation");
            List<CloudResource> prior = terraform.before(request.template());
            if (baseline == null) baseline = prior;
            explicit = changes.terraform(terraform.tree(request.template()), prior, proposed);
        } else {
            if (request.changeSet() != null && baseline == null) throw new IllegalArgumentException("CloudFormation change-set reviews require a baseline snapshot");
            explicit = changes.cloudFormation(request.changeSet());
        }
        ArchitectureReport report = engine.evaluate(proposed, baseline, explicit, request.qualityGate(), request.suppressions());
        ReviewEntity entity = new ReviewEntity();
        entity.id = report.reviewId(); entity.createdAt = report.createdAt(); entity.requestKey = key;
        entity.requestHash = hash; entity.format = request.format().name(); entity.report = json(report);
        entity.snapshot = json(proposed); entity.suppressions = json(request.suppressions());
        repository.saveAndFlush(entity);
        for (ArchitectureRule rule : catalog.rules()) {
            jdbc.update("INSERT INTO rule_definition(id,version,definition) VALUES (?,?,?) ON CONFLICT DO NOTHING", rule.id(), rule.definition().version(), json(rule.definition()));
            String stored = jdbc.queryForObject("SELECT definition FROM rule_definition WHERE id=? AND version=?", String.class, rule.id(), rule.definition().version());
            if (!json(rule.definition()).equals(stored)) throw new IllegalStateException("Rule contents changed without a version increment: " + rule.id());
        }
        jdbc.update("INSERT INTO rule_definition(id,version,definition) VALUES ('CHANGE-STATEFUL-001','1','Destructive stateful change') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO rule_definition(id,version,definition) VALUES ('CHANGE-ALARM-001','1','CloudWatch alarm removed') ON CONFLICT DO NOTHING");
        jdbc.batchUpdate("INSERT INTO resource(review_id,resource_id,resource_type,properties) VALUES (?,?,?,?)", proposed, 500, (statement, resource) -> {
            statement.setObject(1,entity.id); statement.setString(2,resource.resourceId()); statement.setString(3,resource.resourceType()); statement.setString(4,json(resource.properties()));
        });
        var allFindings = new ArrayList<>(report.findings()); allFindings.addAll(report.resolvedFindings());
        jdbc.batchUpdate("INSERT INTO finding(review_id,rule_id,rule_version,resource_id,severity,outcome,classification,suppressed,detail) VALUES (?,?,?,?,?,?,?,?,?)", allFindings, 500, (statement, finding) -> {
            statement.setObject(1,entity.id); statement.setString(2,finding.ruleId()); statement.setString(3,finding.ruleVersion()); statement.setString(4,finding.resourceId());
            statement.setString(5,finding.severity().name()); statement.setString(6,finding.outcome().name()); statement.setString(7,finding.classification().name());
            statement.setBoolean(8,finding.suppressed()); statement.setString(9,json(finding));
        });
        return report;
    }
    @Transactional(readOnly=true) public ArchitectureReport get(UUID id) { return read(entity(id).report, ArchitectureReport.class); }
    @Transactional public Map<String, Object> milestone(UUID reviewId, String name) {
        ArchitectureReport report = get(reviewId);
        jdbc.update("INSERT INTO review_milestone(id,review_id,name,score,created_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP) ON CONFLICT(review_id,name) DO NOTHING", UUID.randomUUID(), reviewId, name, report.score());
        return jdbc.queryForMap("SELECT id,review_id,name,score,created_at FROM review_milestone WHERE review_id=? AND name=?", reviewId, name);
    }
    @Transactional(readOnly=true) public List<Map<String,Object>> milestones(UUID reviewId) {
        entity(reviewId); return jdbc.queryForList("SELECT id,review_id,name,score,created_at FROM review_milestone WHERE review_id=? ORDER BY created_at", reviewId);
    }
    @Transactional public Map<String,Object> baseline(String name, UUID reviewId) {
        entity(reviewId);
        jdbc.update("INSERT INTO architecture_baseline(name,review_id) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET review_id=EXCLUDED.review_id,updated_at=CURRENT_TIMESTAMP", name, reviewId);
        return Map.of("name", name, "reviewId", reviewId);
    }
    @Transactional(readOnly=true) public Map<String,Object> baseline(String name) {
        var rows = jdbc.queryForList("SELECT name,review_id,updated_at FROM architecture_baseline WHERE name=?", name);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Baseline not found");
        return rows.getFirst();
    }
    private ReviewEntity entity(UUID id) { return repository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Review not found")); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("Serialization failed", e); } }
    private <T> T read(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("Stored report cannot be read", e); } }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
