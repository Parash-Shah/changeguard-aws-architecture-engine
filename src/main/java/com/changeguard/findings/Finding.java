package com.changeguard.findings;
import com.changeguard.model.*;
import com.changeguard.rules.RuleResult;
public record Finding(String ruleId, String ruleVersion, String resourceId, Pillar pillar, Severity severity,
        RuleResult.Outcome outcome, String message, String recommendation, Classification classification,
        boolean suppressed, String suppressionReason) {
    public enum Classification { NEW, EXISTING, RESOLVED }
    public String key() { return resourceId + "\u0000" + ruleId; }
    public Finding classify(Classification value) {
        return new Finding(ruleId, ruleVersion, resourceId, pillar, severity, outcome, message, recommendation, value, suppressed, suppressionReason);
    }
    public Finding suppress(String reason) {
        return new Finding(ruleId, ruleVersion, resourceId, pillar, severity, outcome, message, recommendation, classification, true, reason);
    }
}
