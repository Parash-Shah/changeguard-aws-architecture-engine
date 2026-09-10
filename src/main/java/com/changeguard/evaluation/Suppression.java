package com.changeguard.evaluation;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
public record Suppression(@NotBlank String rule, @NotBlank String resource, @NotBlank @Size(max=1000) String reason,
                          @NotNull LocalDate expires) { }
