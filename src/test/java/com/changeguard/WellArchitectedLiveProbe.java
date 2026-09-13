package com.changeguard;

import com.changeguard.aws.WellArchitectedGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Explicit live probe; never discovered by JUnit or run by normal mvn verify. */
public final class WellArchitectedLiveProbe {
    public static void main(String[] args) throws Exception {
        Path evidence = Path.of("reports", "aws-live-probe.json");
        Files.createDirectories(evidence.getParent());
        Files.deleteIfExists(evidence); // A failed attempt must not leave a stale success artifact.
        if (args.length > 1 || Arrays.stream(args).anyMatch(arg -> !arg.equals("--create-milestones") && !arg.equals("--list-workloads")))
            throw new IllegalArgumentException("Use no arguments, --list-workloads, or --create-milestones");
        boolean discover = Arrays.asList(args).contains("--list-workloads");
        String workloadId = discover ? null : required("CHANGEGUARD_AWS_WORKLOAD_ID");
        String region = required("AWS_REGION");
        String lens = System.getenv().getOrDefault("CHANGEGUARD_AWS_LENS", "wellarchitected");
        boolean write = Arrays.asList(args).contains("--create-milestones");
        String runToken = write ? required("CHANGEGUARD_AWS_RUN_TOKEN") : null;
        try (var credentials = DefaultCredentialsProvider.builder().build();
             var client = WellArchitectedClient.builder().credentialsProvider(credentials).region(Region.of(region)).overrideConfiguration(c -> c
                    .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build()) {
            if (discover) {
                int workloadCount = 0;
                Set<String> seenTokens = new HashSet<>();
                String next = null;
                do {
                    var page = client.listWorkloads(software.amazon.awssdk.services.wellarchitected.model.ListWorkloadsRequest.builder()
                            .nextToken(next).maxResults(50).build());
                    workloadCount += page.workloadSummaries().size();
                    next = page.nextToken();
                    if (next != null && !seenTokens.add(next)) throw new IllegalStateException("AWS repeated a pagination token");
                } while (next != null);
                new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), Map.of(
                        "liveAws", true, "recordedAt", Instant.now().toString(), "region", region,
                        "operation", "ListWorkloads", "workloadCount", workloadCount, "milestoneRoundTrip", false));
                System.out.println("AWS live discovery completed: " + evidence + " (read-only, " + workloadCount + " workloads)");
                return;
            }
            var gateway = new WellArchitectedGateway(client);
            var workload = gateway.workload(workloadId);
            var review = gateway.lensReview(workloadId, lens, null);
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("liveAws", true); result.put("recordedAt", Instant.now().toString());
            result.put("region", region); result.put("workloadId", workload.workloadId());
            result.put("lens", lens); result.put("lensVersion", review.lensVersion());
            result.put("riskCounts", review.riskCountsAsStrings());
            Set<Integer> milestones = new TreeSet<>();
            Set<String> seenTokens = new HashSet<>();
            String next = null;
            do {
                var page = gateway.milestones(workloadId, next, 50);
                page.milestoneSummaries().forEach(m -> milestones.add(m.milestoneNumber()));
                next = page.nextToken();
                if (next != null && !seenTokens.add(next)) throw new IllegalStateException("AWS repeated a pagination token");
            } while (next != null);
            result.put("existingMilestones", milestones);
            String question = System.getenv("CHANGEGUARD_AWS_QUESTION_ID");
            if (question != null && !question.isBlank()) {
                var answer = gateway.answer(workloadId, lens, question);
                result.put("questionId", answer.questionId()); result.put("answerRisk", answer.riskAsString());
            }
            if (write) {
                String firstToken = UUID.nameUUIDFromBytes((runToken + ":baseline").getBytes(StandardCharsets.UTF_8)).toString();
                String secondToken = UUID.nameUUIDFromBytes((runToken + ":proposed").getBytes(StandardCharsets.UTF_8)).toString();
                int from = gateway.milestone(workloadId, "changeguard-baseline-" + firstToken, firstToken);
                int to = gateway.milestone(workloadId, "changeguard-proposed-" + secondToken, secondToken);
                result.put("comparison", gateway.compare(workloadId, lens, from, to));
                result.put("milestoneRoundTrip", true);
            } else {
                result.put("milestoneRoundTrip", false);
            }
            // No credential material, answer notes, or AWS report document is persisted.
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), result);
            System.out.println("AWS live probe completed: " + evidence + (write ? " (two milestone writes)" : " (read-only)"));
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name + " before running the live probe");
        return value;
    }
}
