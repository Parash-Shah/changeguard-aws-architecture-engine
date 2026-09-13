package com.changeguard;

import com.changeguard.model.CloudResource;
import com.changeguard.rules.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OrganizationalLensTest {
    private List<ArchitectureRule> rules() throws Exception {
        return Arrays.stream(TestSupport.MAPPER.readValue(Files.readString(Path.of("docs/examples/agent-platform-lens.json")), RuleDefinition[].class))
                .map(d -> (ArchitectureRule) new DeclarativeRule(d)).toList();
    }
    @Test void declaredAgentControlsPassAllSixPolicies() throws Exception {
        var resource = new CloudResource("agent", "ChangeGuard::Agent::Workload", Map.of("BudgetLimit", 10,
                "LeastPrivilege", true, "RemediationApprovalRequired", true, "AuditTrailEnabled", true,
                "ModelTimeoutSeconds", 30, "MaximumSteps", 20));
        assertThat(rules()).hasSize(6).allMatch(r -> r.evaluate(resource).passed());
    }
    @ParameterizedTest @ValueSource(ints={-1,0,121,Integer.MAX_VALUE})
    void invalidOrUnboundedAgentLimitsFail(int limit) throws Exception {
        var resource = new CloudResource("agent", "ChangeGuard::Agent::Workload", Map.of("ModelTimeoutSeconds", limit, "MaximumSteps", limit));
        assertThat(rules().stream().filter(r -> r.id().equals("ORG-AGENT-005") || r.id().equals("ORG-AGENT-006")))
                .allMatch(r -> r.evaluate(resource).outcome() == RuleResult.Outcome.FAIL);
    }
    @Test void unresolvedAgentLimitsRemainUnknown() throws Exception {
        var resource = new CloudResource("agent", "ChangeGuard::Agent::Workload", Map.of("MaximumSteps", Map.of("Ref", "Steps")));
        var rule = rules().stream().filter(r -> r.id().equals("ORG-AGENT-006")).findFirst().orElseThrow();
        assertThat(rule.evaluate(resource).outcome()).isEqualTo(RuleResult.Outcome.UNKNOWN);
    }
}
