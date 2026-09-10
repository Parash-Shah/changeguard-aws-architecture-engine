package com.changeguard.api;
import com.changeguard.evaluation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record ReviewRequest(@NotBlank @Size(max=10000000) String template, @NotNull Format format,
        @Size(max=10000000) String baselineTemplate, UUID baselineReviewId, @Size(max=1000000) String changeSet,
        @Valid QualityGate qualityGate, @Size(max=1000) List<@Valid Suppression> suppressions) {
    public enum Format { CLOUDFORMATION, TERRAFORM_PLAN }
    public ReviewRequest {
        qualityGate = qualityGate == null ? QualityGate.defaults() : qualityGate;
        suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
        if (baselineTemplate != null && baselineReviewId != null) throw new IllegalArgumentException("Choose one baseline source");
    }
}
