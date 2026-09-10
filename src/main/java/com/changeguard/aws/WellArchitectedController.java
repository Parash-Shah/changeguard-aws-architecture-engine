package com.changeguard.aws;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/v1/aws/workloads")
@ConditionalOnProperty(name="changeguard.aws.enabled", havingValue="true")
public class WellArchitectedController {
    private final WellArchitectedGateway gateway;
    public WellArchitectedController(WellArchitectedGateway gateway) { this.gateway = gateway; }
    public record Workload(@NotBlank String name, @NotBlank String description, @NotBlank String owner,
                           @NotEmpty List<@NotBlank String> regions, @NotBlank String clientToken) { }
    public record Milestone(@NotBlank String name, @NotBlank String clientToken) { }
    @PostMapping public Map<String,String> create(@Valid @RequestBody Workload request) {
        return Map.of("workloadId", gateway.createWorkload(request.name(), request.description(), request.owner(), request.regions(), request.clientToken()));
    }
    @GetMapping("/{id}/lenses/{lens}") public Map<String,Object> lens(@PathVariable String id, @PathVariable String lens,
            @RequestParam(required=false) Integer milestone) {
        var review = gateway.lensReview(id, lens, milestone);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("lensName", review.lensName()); result.put("lensVersion", review.lensVersion()); result.put("riskCounts", review.riskCountsAsStrings());
        return result;
    }
    @GetMapping("/{id}/lenses/{lens}/answers/{question}") public Map<String,Object> answer(@PathVariable String id, @PathVariable String lens, @PathVariable String question) {
        var answer = gateway.answer(id, lens, question);
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
