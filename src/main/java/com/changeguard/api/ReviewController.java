package com.changeguard.api;
import com.changeguard.evaluation.ArchitectureReport;
import com.changeguard.persistence.ReviewService;
import com.changeguard.rules.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/v1") @Validated
public class ReviewController {
    private final ReviewService reviews;
    private final RuleCatalog catalog;
    public ReviewController(ReviewService reviews, RuleCatalog catalog) { this.reviews = reviews; this.catalog = catalog; }
    @PostMapping("/reviews") public ArchitectureReport create(@Valid @RequestBody ReviewRequest request,
            @RequestHeader(value="Idempotency-Key", required=false) String key) { return reviews.create(request, key); }
    @GetMapping("/reviews/{id}") public ArchitectureReport get(@PathVariable UUID id) { return reviews.get(id); }
    public record MilestoneRequest(@NotBlank @Size(max=200) String name) { }
    public record BaselineRequest(@NotNull UUID reviewId) { }
    @PostMapping("/reviews/{id}/milestones") public Map<String,Object> milestone(@PathVariable UUID id, @Valid @RequestBody MilestoneRequest request) { return reviews.milestone(id, request.name()); }
    @GetMapping("/reviews/{id}/milestones") public List<Map<String,Object>> milestones(@PathVariable UUID id) { return reviews.milestones(id); }
    @PutMapping("/baselines/{name}") public Map<String,Object> baseline(@PathVariable @Size(min=1,max=200) String name, @Valid @RequestBody BaselineRequest request) { return reviews.baseline(name, request.reviewId()); }
    @GetMapping("/baselines/{name}") public Map<String,Object> baseline(@PathVariable String name) { return reviews.baseline(name); }
    @GetMapping("/rules") public List<RuleDefinition> rules() { return catalog.rules().stream().map(ArchitectureRule::definition).toList(); }
}
