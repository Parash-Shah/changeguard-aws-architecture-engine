package com.changeguard.aws;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import software.amazon.awssdk.services.wellarchitected.model.*;
import java.time.Duration;
import java.util.List;

/** Optional adapter. AWS SDK uses the default credential chain and bounded retry/timeouts. */
public class WellArchitectedGateway {
    private final WellArchitectedClient client;
    public WellArchitectedGateway(WellArchitectedClient client) { this.client = client; }
    public String createWorkload(String name, String description, String owner, List<String> regions, String token) {
        return client.createWorkload(CreateWorkloadRequest.builder().workloadName(name).description(description)
                .reviewOwner(owner).environment(WorkloadEnvironment.PRODUCTION).awsRegions(regions)
                .lenses("wellarchitected").clientRequestToken(token).build()).workloadId();
    }
    public LensReview lensReview(String workload, String lens, Integer milestone) {
        return client.getLensReview(GetLensReviewRequest.builder().workloadId(workload).lensAlias(lens).milestoneNumber(milestone).build()).lensReview();
    }
    public Answer answer(String workload, String lens, String question) {
        return client.getAnswer(GetAnswerRequest.builder().workloadId(workload).lensAlias(lens).questionId(question).build()).answer();
    }
    public int milestone(String workload, String name, String token) {
        return client.createMilestone(CreateMilestoneRequest.builder().workloadId(workload).milestoneName(name).clientRequestToken(token).build()).milestoneNumber();
    }
    public String report(String workload, String lens, Integer milestone) {
        return client.getLensReviewReport(GetLensReviewReportRequest.builder().workloadId(workload).lensAlias(lens).milestoneNumber(milestone).build()).lensReviewReport().base64String();
    }
    @Configuration
    @ConditionalOnProperty(name="changeguard.aws.enabled", havingValue="true")
    public static class Config {
        @Bean(destroyMethod="close") WellArchitectedClient wellArchitectedClient(@Value("${changeguard.aws.region}") String region) {
            return WellArchitectedClient.builder().region(Region.of(region)).overrideConfiguration(c -> c
                    .apiCallTimeout(Duration.ofSeconds(20)).apiCallAttemptTimeout(Duration.ofSeconds(5))).build();
        }
        @Bean WellArchitectedGateway wellArchitectedGateway(WellArchitectedClient client) { return new WellArchitectedGateway(client); }
    }
}
