package com.changeguard.rules;
import com.changeguard.model.Severity;
public record RuleResult(Outcome outcome, Severity severity, String message, String recommendation) {
    public enum Outcome { PASS, FAIL, UNKNOWN, RULE_ERROR }
    public boolean passed() { return outcome == Outcome.PASS; }
}
