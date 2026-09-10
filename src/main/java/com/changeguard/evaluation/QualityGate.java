package com.changeguard.evaluation;
import com.changeguard.model.Severity;
import jakarta.validation.constraints.*;
import java.util.Set;
public record QualityGate(@Min(0) @Max(100) int minimumScore, Set<Severity> failOn,
                          @Min(0) int maxHighFindings, boolean regressionsOnly) {
    public QualityGate { failOn = failOn == null ? Set.of(Severity.CRITICAL) : Set.copyOf(failOn); }
    public static QualityGate defaults() { return new QualityGate(80, Set.of(Severity.CRITICAL), 2, false); }
}
