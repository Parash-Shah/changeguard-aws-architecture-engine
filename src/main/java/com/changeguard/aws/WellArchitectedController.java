package com.changeguard.aws;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import software.amazon.awssdk.services.wellarchitected.model.WorkloadEnvironment;

@RestController @RequestMapping("/v1/aws/workloads")
@ConditionalOnProperty(name="changeguard.aws.enabled", havingValue="true")
public class WellArchitectedController {
    private final WellArchitectedGateway gateway;
    public WellArchitectedController(WellArchitectedGateway gateway) { this.gateway = gateway; }
    public record Workload(@NotBlank String name, @NotBlank String description, @NotBlank String owner,
                           @NotEmpty List<@NotBlank String> regions, @NotBlank String clientToken,
                           WorkloadEnvironment environment, List<@NotBlank String> lenses) {
        public Workload {
            if (environment == null) environment = WorkloadEnvironment.PRODUCTION;
            if (lenses == null || lenses.isEmpty()) lenses = List.of("wellarchitected");
        }
    }
    public record Milestone(@NotBlank String name, @NotBlank String clientToken) { }
    @PostMapping public Map<String,String> create(@Valid @RequestBody Workload request) {
        return Map.of("workloadId", gateway.createWorkload(request.name(), request.description(), request.owner(), request.regions(), request.clientToken(), request.environment(), request.lenses()));
    }
    @GetMapping("/{id}") public Map<String,Object> workload(@PathVariable String id) {
        var workload = gateway.workload(id);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("workloadId", workload.workloadId()); result.put("name", workload.workloadName());
        result.put("environment", workload.environmentAsString()); result.put("lenses", workload.lenses());
        result.put("regions", workload.awsRegions()); result.put("riskCounts", workload.riskCountsAsStrings());
        return result;
    }
    @GetMapping("/{id}/milestones") public Map<String,Object> milestones(@PathVariable String id,
            @RequestParam(required=false) String nextToken, @RequestParam(defaultValue="50") int maxResults) {
        var page = gateway.milestones(id, nextToken, maxResults);
        List<Map<String,Object>> summaries = page.milestoneSummaries().stream().map(m -> {
            Map<String,Object> summary = new LinkedHashMap<>();
            summary.put("name", m.milestoneName()); summary.put("milestoneNumber", m.milestoneNumber());
            summary.put("recordedAt", m.recordedAt()); return summary;
        }).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("milestones", summaries); result.put("nextToken", page.nextToken()); return result;
    }
    @GetMapping("/{id}/comparison") public WellArchitectedGateway.RiskComparison compare(@PathVariable String id,
            @RequestParam(defaultValue="wellarchitected") String lens, @RequestParam int from, @RequestParam int to) {
        return gateway.compare(id, lens, from, to);
    }
    @GetMapping("/{id}/lenses/{lens}") public Map<String,Object> lens(@PathVariable String id, @PathVariable String lens,
            @RequestParam(required=false) Integer milestone) {
        var review = gateway.lensReview(id, lens, milestone);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("lensName", review.lensName()); result.put("lensVersion", review.lensVersion()); result.put("riskCounts", review.riskCountsAsStrings());
        return result;
    }
    // Query-based routes also accept custom lens ARNs, which contain slashes.
    @GetMapping("/{id}/lens-review") public Map<String,Object> lensByQuery(@PathVariable String id,
            @RequestParam(defaultValue="wellarchitected") String lens, @RequestParam(required=false) Integer milestone) {
        return lens(id, lens, milestone);
    }
    @GetMapping("/{id}/answer") public Map<String,Object> answerByQuery(@PathVariable String id,
            @RequestParam(defaultValue="wellarchitected") String lens, @RequestParam String question,
            @RequestParam(required=false) Integer milestone) {
        return answer(id, lens, question, milestone);
    }
    @GetMapping("/{id}/report") public Map<String,String> reportByQuery(@PathVariable String id,
            @RequestParam(defaultValue="wellarchitected") String lens, @RequestParam(required=false) Integer milestone) {
        return report(id, lens, milestone);
    }
    @GetMapping("/{id}/lenses/{lens}/answers/{question}") public Map<String,Object> answer(@PathVariable String id, @PathVariable String lens, @PathVariable String question,
            @RequestParam(required=false) Integer milestone) {
        var answer = gateway.answer(id, lens, question, milestone);
        Map<String,Object> result = new LinkedHashMap<>(); result.put("questionId", answer.questionId()); result.put("questionTitle", answer.questionTitle());
        result.put("risk", answer.riskAsString()); result.put("selectedChoices", answer.selectedChoices()); return result;
    }
    @PostMapping("/{id}/milestones") public Map<String,Integer> milestone(@PathVariable String id, @Valid @RequestBody Milestone request) {
        return Map.of("milestoneNumber", gateway.milestone(id, request.name(), request.clientToken()));
    }
    @GetMapping("/{id}/lenses/{lens}/report") public Map<String,String> report(@PathVariable String id, @PathVariable String lens, @RequestParam(required=false) Integer milestone) {
        return Map.of("base64Report", gateway.report(id, lens, milestone));
    }
}
