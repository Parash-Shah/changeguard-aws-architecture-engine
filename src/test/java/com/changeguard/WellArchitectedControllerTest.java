package com.changeguard;

import com.changeguard.api.ApiExceptionHandler;
import com.changeguard.aws.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import software.amazon.awssdk.services.wellarchitected.model.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WellArchitectedControllerTest {
    private WellArchitectedClient client;
    private MockMvc mvc;
    @BeforeEach void setup() {
        client = mock(WellArchitectedClient.class);
        mvc = MockMvcBuilders.standaloneSetup(new WellArchitectedController(new WellArchitectedGateway(client)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void oldCreatePayloadKeepsProductionAndDefaultLens() throws Exception {
        when(client.createWorkload(any(CreateWorkloadRequest.class))).thenReturn(CreateWorkloadResponse.builder().workloadId("id").build());
        mvc.perform(post("/v1/aws/workloads").contentType("application/json")
                .content("""
                    {"name":"checkout","description":"review","owner":"team","regions":["us-east-1"],"clientToken":"token"}
                    """)).andExpect(status().isOk()).andExpect(jsonPath("$.workloadId").value("id"));
        verify(client).createWorkload(argThat((CreateWorkloadRequest r) -> r.environment() == WorkloadEnvironment.PRODUCTION
                && r.lenses().equals(List.of("wellarchitected"))));
    }
    @Test void milestoneListExposesNextPageWithoutLosingResults() throws Exception {
        when(client.listMilestones(any(ListMilestonesRequest.class))).thenReturn(ListMilestonesResponse.builder().nextToken("next")
                .milestoneSummaries(MilestoneSummary.builder().milestoneName("release").milestoneNumber(1).build()).build());
        mvc.perform(get("/v1/aws/workloads/id/milestones").param("maxResults", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextToken").value("next"))
                .andExpect(jsonPath("$.milestones[0].milestoneNumber").value(1));
    }
    @Test void invalidComparisonDoesNotCallAws() throws Exception {
        mvc.perform(get("/v1/aws/workloads/id/comparison").param("from", "5").param("to", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/aws/workloads/id/milestones").param("maxResults", "0"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(client);
    }
    @Test void throttlingReturns503WithoutLeakingAwsErrorDetail() throws Exception {
        when(client.getWorkload(any(GetWorkloadRequest.class))).thenThrow(ThrottlingException.builder().message("private provider details").build());
        mvc.perform(get("/v1/aws/workloads/id")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("AWS review context is unavailable; retry later with the same client token"));
    }
    @Test void comparisonAcceptsCustomLensArnAsQueryParameter() throws Exception {
        String arn = "arn:aws:wellarchitected:us-east-1:123456789012:lens/custom";
        when(client.getLensReview(any(GetLensReviewRequest.class))).thenReturn(GetLensReviewResponse.builder()
                .lensReview(LensReview.builder().lensVersion("1").riskCountsWithStrings(Map.of("HIGH", 0)).build()).build());
        mvc.perform(get("/v1/aws/workloads/id/comparison").param("lens", arn).param("from", "1").param("to", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comparable").value(true)).andExpect(jsonPath("$.delta.HIGH").value(0));
        verify(client, times(2)).getLensReview(argThat((GetLensReviewRequest r) -> r.lensAlias().equals(arn)));
    }
    @Test void customLensQueryRoutesPreserveHistoricalContext() throws Exception {
        String arn = "arn:aws:wellarchitected:us-east-1:123456789012:lens/custom";
        when(client.getLensReview(any(GetLensReviewRequest.class))).thenReturn(GetLensReviewResponse.builder()
                .lensReview(LensReview.builder().lensName("Agent Platform").lensVersion("1").build()).build());
        when(client.getAnswer(any(GetAnswerRequest.class))).thenReturn(GetAnswerResponse.builder()
                .answer(Answer.builder().questionId("budget").risk(Risk.HIGH).build()).build());
        when(client.getLensReviewReport(any(GetLensReviewReportRequest.class))).thenReturn(GetLensReviewReportResponse.builder()
                .lensReviewReport(LensReviewReport.builder().base64String("cGRm").build()).build());
        mvc.perform(get("/v1/aws/workloads/id/lens-review").param("lens", arn).param("milestone", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lensName").value("Agent Platform"));
        mvc.perform(get("/v1/aws/workloads/id/answer").param("lens", arn).param("question", "budget").param("milestone", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.risk").value("HIGH"));
        mvc.perform(get("/v1/aws/workloads/id/report").param("lens", arn).param("milestone", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.base64Report").value("cGRm"));
        verify(client).getLensReview(argThat((GetLensReviewRequest r) -> r.lensAlias().equals(arn) && r.milestoneNumber() == 2));
        verify(client).getAnswer(argThat((GetAnswerRequest r) -> r.lensAlias().equals(arn) && r.milestoneNumber() == 2));
        verify(client).getLensReviewReport(argThat((GetLensReviewReportRequest r) -> r.lensAlias().equals(arn) && r.milestoneNumber() == 2));
    }
}
