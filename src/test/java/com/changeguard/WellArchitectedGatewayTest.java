package com.changeguard;
import com.changeguard.aws.WellArchitectedGateway;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import software.amazon.awssdk.services.wellarchitected.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;

class WellArchitectedGatewayTest {
    @Test void createsWorkloadWithSelectedEnvironmentLensesAndToken() {
        var client = mock(WellArchitectedClient.class);
        when(client.createWorkload(any(CreateWorkloadRequest.class))).thenReturn(CreateWorkloadResponse.builder().workloadId("id").build());
        var gateway = new WellArchitectedGateway(client);
        assertThat(gateway.createWorkload("sandbox", "description", "owner", List.of("us-east-1"), "token",
                WorkloadEnvironment.PREPRODUCTION, List.of("wellarchitected", "custom-lens"))).isEqualTo("id");
        verify(client).createWorkload(argThat((CreateWorkloadRequest r) -> r.clientRequestToken().equals("token")
                && r.environment() == WorkloadEnvironment.PREPRODUCTION && r.lenses().contains("custom-lens")));
    }
    @Test void retrievesExistingWorkload() {
        var client = mock(WellArchitectedClient.class);
        var workload = Workload.builder().workloadId("id").workloadName("checkout").build();
        when(client.getWorkload(any(GetWorkloadRequest.class))).thenReturn(GetWorkloadResponse.builder().workload(workload).build());
        assertThat(new WellArchitectedGateway(client).workload("id")).isEqualTo(workload);
        verify(client).getWorkload(argThat((GetWorkloadRequest r) -> r.workloadId().equals("id")));
    }
    @Test void milestonePagesPreserveContinuationTokens() {
        var client = mock(WellArchitectedClient.class);
        var response = ListMilestonesResponse.builder().nextToken("page-3")
                .milestoneSummaries(MilestoneSummary.builder().milestoneName("v2").milestoneNumber(2).build()).build();
        when(client.listMilestones(any(ListMilestonesRequest.class))).thenReturn(response);
        assertThat(new WellArchitectedGateway(client).milestones("id", "page-2", 10)).isSameAs(response);
        verify(client).listMilestones(argThat((ListMilestonesRequest r) -> r.nextToken().equals("page-2") && r.maxResults() == 10));
    }
    @Test void historicalAnswerUsesRequestedMilestone() {
        var client = mock(WellArchitectedClient.class);
        when(client.getAnswer(any(GetAnswerRequest.class))).thenReturn(GetAnswerResponse.builder().answer(Answer.builder().questionId("security").build()).build());
        assertThat(new WellArchitectedGateway(client).answer("id", "wellarchitected", "security", 4).questionId()).isEqualTo("security");
        verify(client).getAnswer(argThat((GetAnswerRequest r) -> r.milestoneNumber() == 4 && r.questionId().equals("security")));
    }
    @Test void reportUsesRequestedMilestone() {
        var client = mock(WellArchitectedClient.class);
        when(client.getLensReviewReport(any(GetLensReviewReportRequest.class))).thenReturn(GetLensReviewReportResponse.builder()
                .lensReviewReport(LensReviewReport.builder().base64String("cGRm").build()).build());
        assertThat(new WellArchitectedGateway(client).report("id", "wellarchitected", 2)).isEqualTo("cGRm");
        verify(client).getLensReviewReport(argThat((GetLensReviewReportRequest r) -> r.milestoneNumber() == 2));
    }
    @Test void comparesRiskCategoriesAcrossSameLensVersion() {
        var client = mock(WellArchitectedClient.class);
        when(client.getLensReview(any(GetLensReviewRequest.class))).thenAnswer(call -> {
            GetLensReviewRequest request = call.getArgument(0);
            return GetLensReviewResponse.builder().lensReview(LensReview.builder().lensVersion("1")
                    .riskCountsWithStrings(request.milestoneNumber() == 1 ? Map.of("HIGH", 4, "MEDIUM", 2) : Map.of("HIGH", 1, "NONE", 3)).build()).build();
        });
        var comparison = new WellArchitectedGateway(client).compare("id", "wellarchitected", 1, 2);
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.delta()).containsExactlyInAnyOrderEntriesOf(Map.of("HIGH", -3, "MEDIUM", -2, "NONE", 3));
        assertThat(comparison.before()).containsEntry("HIGH", 4);
    }
    @Test void differentLensVersionsDoNotProduceMisleadingDeltas() {
        var client = mock(WellArchitectedClient.class);
        when(client.getLensReview(any(GetLensReviewRequest.class))).thenAnswer(call -> {
            GetLensReviewRequest request = call.getArgument(0);
            return GetLensReviewResponse.builder().lensReview(LensReview.builder().lensVersion(request.milestoneNumber().toString())
                    .riskCountsWithStrings(Map.of("HIGH", 1)).build()).build();
        });
        var comparison = new WellArchitectedGateway(client).compare("id", "wellarchitected", 1, 2);
        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.delta()).isEmpty();
    }
    @Test void invalidMilestonesAndPageSizesNeverCallAws() {
        var client = mock(WellArchitectedClient.class);
        var gateway = new WellArchitectedGateway(client);
        assertThatThrownBy(() -> gateway.compare("id", "lens", 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.lensReview("id", "lens", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.answer("id", "lens", "q", 101)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.milestones("id", null, 51)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
    @Test void failedSecondMilestoneDoesNotReturnPartialComparison() {
        var client = mock(WellArchitectedClient.class);
        when(client.getLensReview(any(GetLensReviewRequest.class)))
                .thenReturn(GetLensReviewResponse.builder().lensReview(LensReview.builder().lensVersion("1").build()).build())
                .thenThrow(ThrottlingException.builder().message("retry later").build());
        assertThatThrownBy(() -> new WellArchitectedGateway(client).compare("id", "lens", 1, 2)).isInstanceOf(ThrottlingException.class);
    }
    @Test void preservesMilestoneIdempotencyToken() {
        var client=mock(WellArchitectedClient.class);
        when(client.createMilestone(any(CreateMilestoneRequest.class))).thenReturn(CreateMilestoneResponse.builder().milestoneNumber(3).build());
        var gateway=new WellArchitectedGateway(client);
        assertThat(gateway.milestone("workload","checkout-v3","token")).isEqualTo(3);
        verify(client).createMilestone(argThat((CreateMilestoneRequest r) -> r.clientRequestToken().equals("token") && r.workloadId().equals("workload")));
    }
    @Test void exposesAwsFailureWithoutFabricatingSuccess() {
        var client=mock(WellArchitectedClient.class);
        when(client.getLensReview(any(GetLensReviewRequest.class))).thenThrow(ThrottlingException.builder().message("throttled").build());
        assertThatThrownBy(()->new WellArchitectedGateway(client).lensReview("workload","wellarchitected",null)).isInstanceOf(ThrottlingException.class);
    }
}
