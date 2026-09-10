package com.changeguard.evaluation;
import java.util.List;
public record ResourceChange(String resourceId, String resourceType, Action action, Risk risk, List<String> changedProperties) {
    public enum Action { CREATE, MODIFY, DELETE, REPLACEMENT }
    public enum Risk { SAFE, RISK_INCREASE, RISK_REDUCTION, NEUTRAL }
}
