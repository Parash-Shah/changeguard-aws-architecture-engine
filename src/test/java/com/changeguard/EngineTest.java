package com.changeguard;
import com.changeguard.evaluation.*;
import com.changeguard.findings.Finding;
import com.changeguard.model.*;
import com.changeguard.parser.*;
import com.changeguard.rules.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EngineTest {
    private final CloudFormationParser parser = new CloudFormationParser();
    @Test void allGoodFixtureChecksPass() throws Exception {
        var engine = TestSupport.engine();
        try {
            var report = engine.evaluate(parser.parse(TestSupport.fixture("good/production.json")), null,List.of(),QualityGate.defaults(),List.of());
            assertThat(report.score()).isEqualTo(100); assertThat(report.status()).isEqualTo(ArchitectureReport.Status.PASS);
            assertThat(report.evaluations()).isEqualTo(42); assertThat(report.findings()).isEmpty();
        } finally { engine.close(); }
    }
    @Test void detectsRegressionsAndResolutions() throws Exception {
        var engine = TestSupport.engine();
        try {
            var before = parser.parse(TestSupport.fixture("good/production.json")).stream().filter(r -> r.resourceId().equals("OrdersDatabase")).toList();
            var after = parser.parse(TestSupport.fixture("bad/public-rds.json"));
            var report = engine.evaluate(after,before,List.of(),QualityGate.defaults(),List.of());
            assertThat(report.status()).isEqualTo(ArchitectureReport.Status.FAIL);
            assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("SEC-RDS-002") && f.classification() == Finding.Classification.NEW);
            var reverse = engine.evaluate(before,after,List.of(),QualityGate.defaults(),List.of());
            assertThat(reverse.resolvedFindings()).hasSize(report.findings().size());
            assertThat(reverse.changes().getFirst().risk()).isEqualTo(ResourceChange.Risk.RISK_REDUCTION);
        } finally { engine.close(); }
    }
    @Test void regressionOnlyGateIgnoresExistingDebtButShowsIt() throws Exception {
        var engine = TestSupport.engine();
        try {
            var bad = parser.parse(TestSupport.fixture("bad/public-rds.json"));
            var report = engine.evaluate(bad,bad,List.of(),new QualityGate(80,Set.of(Severity.CRITICAL),2,true),List.of());
            assertThat(report.status()).isEqualTo(ArchitectureReport.Status.WARN);
            assertThat(report.gateScore()).isEqualTo(100); assertThat(report.score()).isLessThan(80);
        } finally { engine.close(); }
    }
    @Test void datedSuppressionIsAuditedAndExpiredOneRejected() throws Exception {
        var engine = TestSupport.engine();
        try {
            var bad = parser.parse(TestSupport.fixture("bad/public-rds.json"));
            var suppression = new Suppression("SEC-RDS-002","OrdersDatabase","Isolated sandbox", LocalDate.of(2026,10,1));
            var report = engine.evaluate(bad,null,List.of(),QualityGate.defaults(),List.of(suppression));
            assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("SEC-RDS-002") && f.suppressed() && f.suppressionReason().equals("Isolated sandbox"));
            assertThatThrownBy(() -> engine.evaluate(bad,null,List.of(),QualityGate.defaults(),List.of(new Suppression("SEC-RDS-002","OrdersDatabase","old",LocalDate.of(2026,9,10))))).isInstanceOf(IllegalArgumentException.class);
        } finally { engine.close(); }
    }
    @Test void deletionCannotPassEvenWithPermissiveGate() throws Exception {
        var engine = TestSupport.engine();
        try {
            var report = engine.evaluate(List.of(),parser.parse(TestSupport.fixture("good/production.json")),List.of(),new QualityGate(0,Set.of(),999,true),List.of());
            assertThat(report.status()).isEqualTo(ArchitectureReport.Status.FAIL);
            assertThat(report.findings()).anyMatch(f -> f.ruleId().equals("CHANGE-STATEFUL-001"));
        } finally { engine.close(); }
    }
    @Test void unknownAndUnsupportedResourcesNeverPass() throws Exception {
        var engine = TestSupport.engine();
        try {
            var resource = new CloudResource("db","AWS::RDS::DBInstance",Map.of("StorageEncrypted",Map.of("Ref","Encrypt")));
            var report = engine.evaluate(List.of(resource),null,List.of(),QualityGate.defaults(),List.of());
            assertThat(report.unknownChecks()).isEqualTo(1); assertThat(report.status()).isEqualTo(ArchitectureReport.Status.FAIL);
            var unsupported = engine.evaluate(List.of(new CloudResource("x","AWS::Unknown::Thing",Map.of())),null,List.of(),QualityGate.defaults(),List.of());
            assertThat(unsupported.status()).isEqualTo(ArchitectureReport.Status.FAIL);
            assertThat(unsupported.pillarScores().get(Pillar.SECURITY).score()).isNull();
        } finally { engine.close(); }
    }
    @Test void unknownProposalDoesNotResolveBaselineFailure() throws Exception {
        var engine=TestSupport.engine();
        try {
            var before=List.of(new CloudResource("volume","AWS::EC2::Volume",Map.of("Encrypted",false,"VolumeType","gp3")));
            var after=List.of(new CloudResource("volume","AWS::EC2::Volume",Map.of("Encrypted",Map.of("Ref","Encryption"),"VolumeType","gp3")));
            var report=engine.evaluate(after,before,List.of(),QualityGate.defaults(),List.of());
            assertThat(report.resolvedFindings()).isEmpty(); assertThat(report.unknownChecks()).isEqualTo(1);
        } finally { engine.close(); }
    }
    @Test void contradictoryChangeMetadataIsRejected() throws Exception {
        var engine=TestSupport.engine();
        try {
            var before=List.of(new CloudResource("volume","AWS::EC2::Volume",Map.of("Encrypted",true,"VolumeType","gp3")));
            var forged=List.of(new ResourceChange("volume","AWS::Logs::LogGroup",ResourceChange.Action.DELETE,ResourceChange.Risk.NEUTRAL,List.of()));
            assertThatThrownBy(() -> engine.evaluate(List.of(),before,forged,QualityGate.defaults(),List.of())).isInstanceOf(IllegalArgumentException.class);
        } finally { engine.close(); }
    }
    @Test void throwingRuleIsIsolatedAndNeverCached() throws Exception {
        var base = TestSupport.catalog().rules().getFirst();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ArchitectureRule broken = new ArchitectureRule() {
            public RuleDefinition definition() { return base.definition(); }
            public RuleResult evaluate(CloudResource r) { calls.incrementAndGet(); throw new IllegalStateException("sensitive payload"); }
        };
        var engine = new ArchitectureEvaluationService(List.of(broken),new SimpleMeterRegistry(),1,100,Clock.systemUTC());
        try {
            var resource = new CloudResource("x",base.definition().resourceTypes().getFirst(),Map.of());
            for (int i=0;i<2;i++) {
                var report = engine.evaluate(List.of(resource),null,List.of(),QualityGate.defaults(),List.of());
                assertThat(report.ruleErrors()).isEqualTo(1); assertThat(report.findings().getFirst().message()).doesNotContain("sensitive");
            }
            assertThat(calls.get()).isEqualTo(2);
        } finally { engine.close(); }
    }
    @Test void parallelResultsAndConcurrentReviewsAreIsolated() throws Exception {
        var engine = new ArchitectureEvaluationService(TestSupport.catalog().rules(),new SimpleMeterRegistry(),4,1000,Clock.systemUTC());
        var resources = parser.parse(TestSupport.fixture("good/production.json"));
        try (var callers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<java.util.concurrent.Future<ArchitectureReport>>();
            for (int i=0;i<20;i++) { int n=i; futures.add(callers.submit(() -> engine.evaluate(List.of(new CloudResource("review-"+n,"AWS::EC2::Volume",Map.of("Encrypted",true,"VolumeType","gp3"))),null,List.of(),QualityGate.defaults(),List.of()))); }
            Set<UUID> ids = new HashSet<>();
            for (var future : futures) { var report=future.get(); assertThat(report.resources()).isEqualTo(1); assertThat(report.findings()).isEmpty(); ids.add(report.reviewId()); }
            assertThat(ids).hasSize(20);
            assertThat(engine.evaluate(resources,null,List.of(),QualityGate.defaults(),List.of()).score()).isEqualTo(100);
        } finally { engine.close(); }
    }
}
