package com.changeguard;

import com.changeguard.evaluation.*;
import com.changeguard.parser.CloudFormationParser;
import com.changeguard.rules.RuleResult;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;

/** Fifteen independently authored mutation cases, each exercised in ten deployment contexts. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArchitectureScenarioTest {
    record Case(String resource, String property, Object unsafe, String rule, boolean critical) { }
    record Scenario(Case seed, int context, boolean unsafe) { @Override public String toString() { return seed.rule()+"-context-"+context+(unsafe?"-unsafe":"-secure"); } }
    private ArchitectureEvaluationService engine;
    private int unsafeCount, safeCount, truePositive, falsePositive, criticalCount, criticalDetected;
    @BeforeAll void start() throws Exception { engine = TestSupport.engine(); }
    Stream<Scenario> scenarios() {
        List<Case> cases = List.of(
            new Case("OrdersDatabase","PubliclyAccessible",true,"SEC-RDS-002",true),
            new Case("Assets","PublicAccessBlockConfiguration",Map.of("BlockPublicAcls",false,"IgnorePublicAcls",true,"BlockPublicPolicy",true,"RestrictPublicBuckets",true),"SEC-S3-001",true),
            new Case("Reader","PolicyDocument",Map.of("Statement",List.of(Map.of("Effect","Allow","Action","*","Resource","*"))),"SEC-IAM-001",true),
            new Case("WorkerRole","Policies",List.of(Map.of("PolicyName","admin","PolicyDocument",Map.of("Statement",Map.of("Effect","Allow","Action",List.of("*"),"Resource",List.of("*"))))),"SEC-IAM-002",true),
            new Case("PrivateGroup","SecurityGroupIngress",List.of(Map.of("IpProtocol","tcp","FromPort",0,"ToPort",65535,"CidrIpv6","::/0")),"SEC-EC2-001",true),
            new Case("DataVolume","Encrypted",false,"SEC-EBS-001",false),
            new Case("Queue","SqsManagedSseEnabled",false,"SEC-SQS-001",false),
            new Case("Topic","KmsMasterKeyId","","SEC-SNS-001",false),
            new Case("Cache","AutomaticFailoverEnabled",false,"REL-CACHE-001",false),
            new Case("AuditTrail","IsLogging",false,"OPS-TRAIL-001",false),
            new Case("Orders","DeletionProtectionEnabled",false,"REL-DDB-002",false),
            new Case("Workers","MinSize",1,"REL-ASG-001",false),
            new Case("Service","DesiredCount",1,"REL-ECS-001",false),
            new Case("Logs","RetentionInDays",0,"OPS-LOG-001",false),
            new Case("Alarm","AlarmActions",List.of(),"OPS-ALARM-001",false));
        return cases.stream().flatMap(seed -> IntStream.range(0,10).mapToObj(i -> new Scenario(seed,i,i>=4)));
    }
    @ParameterizedTest(name="{0}") @MethodSource("scenarios")
    void unsafeArchitectureRecall(Scenario scenario) throws Exception {
        ObjectNode root = (ObjectNode)TestSupport.MAPPER.readTree(TestSupport.fixture("good/production.json"));
        // Contexts alternate a whole architecture and isolated resource, static scan and regression scan.
        if (scenario.context()%2==0) {
            var chosen=root.path("Resources").get(scenario.seed().resource()).deepCopy();
            root.putObject("Resources").set(scenario.seed().resource(),chosen);
        }
        String baseline = root.toString();
        if (scenario.unsafe()) ((ObjectNode)root.path("Resources").path(scenario.seed().resource()).path("Properties"))
                .set(scenario.seed().property(),TestSupport.MAPPER.valueToTree(scenario.seed().unsafe()));
        var parser = new CloudFormationParser();
        var report = engine.evaluate(parser.parse(root.toString()), scenario.context()%3==0 ? null : parser.parse(baseline), List.of(),QualityGate.defaults(),List.of());
        boolean detected = report.findings().stream().anyMatch(f -> f.ruleId().equals(scenario.seed().rule()) && f.outcome()==RuleResult.Outcome.FAIL);
        if (scenario.unsafe()) {
            unsafeCount++; if (detected) truePositive++;
            if (scenario.seed().critical()) { criticalCount++; if (detected && report.status()==ArchitectureReport.Status.FAIL) criticalDetected++; }
            assertThat(detected).isTrue();
            assertThat(report.status()).isNotEqualTo(ArchitectureReport.Status.PASS);
        } else {
            safeCount++; if (!report.findings().isEmpty()) falsePositive++;
            assertThat(report.findings()).isEmpty(); assertThat(report.score()).isEqualTo(100);
        }
    }
    @AfterAll void results() throws Exception {
        engine.close();
        Files.createDirectories(Path.of("target","evaluation"));
        var result = Map.of("dataset","150 synthetic variations of 15 hand-authored mutations; not a production accuracy estimate",
                "safeScenarios",safeCount,"unsafeScenarios",unsafeCount,"unsafeDetected",truePositive,
                "falsePositives",falsePositive,"criticalScenarios",criticalCount,"criticalBlocked",criticalDetected);
        Files.writeString(Path.of("target","evaluation","scenario-results.json"),TestSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }
}
