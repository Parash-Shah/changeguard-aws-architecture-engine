package com.changeguard.aws;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import software.amazon.awssdk.services.wellarchitected.model.*;
import java.time.Duration;
import java.util.*;

/** Optional adapter. AWS SDK uses the default credential chain and bounded retry/timeouts. */
public class WellArchitectedGateway {
    private final WellArchitectedClient client;
    public WellArchitectedGateway(WellArchitectedClient client) { this.client = client; }
    public String createWorkload(String name, String description, String owner, List<String> regions, String token) {
        return createWorkload(name, description, owner, regions, token, WorkloadEnvironment.PRODUCTION, List.of("wellarchitected"));
    }
    public String createWorkload(String name, String description, String owner, List<String> regions, String token,
                                 WorkloadEnvironment environment, List<String> lenses) {
        if (environment == WorkloadEnvironment.UNKNOWN_TO_SDK_VERSION) throw new IllegalArgumentException("Unsupported workload environment");
        return client.createWorkload(CreateWorkloadRequest.builder().workloadName(name).description(description)
                .reviewOwner(owner).environment(environment).awsRegions(regions)
                .lenses(lenses).clientRequestToken(token).build()).workloadId();
    }
    public Workload workload(String id) {
        return client.getWorkload(GetWorkloadRequest.builder().workloadId(id).build()).workload();
    }
    public ListMilestonesResponse milestones(String workload, String nextToken, int maxResults) {
        if (maxResults < 1 || maxResults > 50) throw new IllegalArgumentException("maxResults must be between 1 and 50");
        return client.listMilestones(ListMilestonesRequest.builder().workloadId(workload)
                .nextToken(nextToken).maxResults(maxResults).build());
    }
    public LensReview lensReview(String workload, String lens, Integer milestone) {
        validateMilestone(milestone);
        return client.getLensReview(GetLensReviewRequest.builder().workloadId(workload).lensAlias(lens).milestoneNumber(milestone).build()).lensReview();
    }
    public Answer answer(String workload, String lens, String question) {
        return answer(workload, lens, question, null);
    }
    public Answer answer(String workload, String lens, String question, Integer milestone) {
        validateMilestone(milestone);
        return client.getAnswer(GetAnswerRequest.builder().workloadId(workload).lensAlias(lens).questionId(question)
                .milestoneNumber(milestone).build()).answer();
    }
    public int milestone(String workload, String name, String token) {
        return client.createMilestone(CreateMilestoneRequest.builder().workloadId(workload).milestoneName(name).clientRequestToken(token).build()).milestoneNumber();
    }
    public String report(String workload, String lens, Integer milestone) {
        validateMilestone(milestone);
        return client.getLensReviewReport(GetLensReviewReportRequest.builder().workloadId(workload).lensAlias(lens).milestoneNumber(milestone).build()).lensReviewReport().base64String();
    }
    public record RiskComparison(String workloadId, String lensAlias, int fromMilestone, int toMilestone,
                                 String fromLensVersion, String toLensVersion, boolean comparable,
                                 Map<String, Integer> before, Map<String, Integer> after, Map<String, Integer> delta) { }
    /** Positive deltas mean more answers in that AWS risk category, not ChangeGuard score points. */
    public RiskComparison compare(String workload, String lens, int from, int to) {
        validateMilestone(from); validateMilestone(to);
        if (from >= to) throw new IllegalArgumentException("from must precede to");
        LensReview before = lensReview(workload, lens, from);
        LensReview after = lensReview(workload, lens, to);
        boolean comparable = before.lensVersion() != null && before.lensVersion().equals(after.lensVersion());
        Map<String, Integer> delta = new TreeMap<>();
        if (comparable) {
            Set<String> categories = new TreeSet<>(before.riskCountsAsStrings().keySet());
            categories.addAll(after.riskCountsAsStrings().keySet());
            for (String category : categories) delta.put(category,
                    after.riskCountsAsStrings().getOrDefault(category, 0) - before.riskCountsAsStrings().getOrDefault(category, 0));
        }
        return new RiskComparison(workload, lens, from, to, before.lensVersion(), after.lensVersion(), comparable,
                Map.copyOf(before.riskCountsAsStrings()), Map.copyOf(after.riskCountsAsStrings()), Map.copyOf(delta));
    }
    private static void validateMilestone(Integer milestone) {
        if (milestone != null && (milestone < 1 || milestone > 100))
            throw new IllegalArgumentException("Milestone must be between 1 and 100");
    }
    @Configuration
    @ConditionalOnProperty(name="changeguard.aws.enabled", havingValue="true")
    public static class Config {
        @Bean(destroyMethod="close") DefaultCredentialsProvider awsCredentialsProvider() {
            return DefaultCredentialsProvider.builder().build();
        }
        @Bean(destroyMethod="close") WellArchitectedClient wellArchitectedClient(@Value("${changeguard.aws.region}") String region,
                                                                               DefaultCredentialsProvider credentials) {
            return WellArchitectedClient.builder().credentialsProvider(credentials).region(Region.of(region)).overrideConfiguration(c -> c
                    .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
        }
        @Bean WellArchitectedGateway wellArchitectedGateway(WellArchitectedClient client) { return new WellArchitectedGateway(client); }
    }
}
