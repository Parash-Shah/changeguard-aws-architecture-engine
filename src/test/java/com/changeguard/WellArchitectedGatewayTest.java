package com.changeguard;
import com.changeguard.aws.WellArchitectedGateway;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.wellarchitected.WellArchitectedClient;
import software.amazon.awssdk.services.wellarchitected.model.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WellArchitectedGatewayTest {
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
