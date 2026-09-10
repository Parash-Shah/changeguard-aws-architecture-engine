package com.changeguard;
import com.changeguard.api.ReviewRequest;
import com.changeguard.evaluation.ArchitectureReport;
import com.changeguard.persistence.ReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @Testcontainers
class ReviewApiIT {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->postgres.getJdbcUrl()+(postgres.getJdbcUrl().contains("?")?"&":"?")+"socketTimeout=2&connectTimeout=2"); r.add("spring.datasource.username",postgres::getUsername); r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.datasource.hikari.connection-timeout",()->2000);
        r.add("changeguard.api-key",()->"integration-test-key");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ReviewService service;
    @Autowired JdbcTemplate jdbc;
    ReviewRequest good() throws Exception { return new ReviewRequest(TestSupport.fixture("good/production.json"),ReviewRequest.Format.CLOUDFORMATION,null,null,null,null,null); }
    @Test void persistsFetchesAndCreatesMilestones() throws Exception {
        String content=mvc.perform(post("/v1/reviews").header("X-API-Key","integration-test-key").contentType("application/json").content(mapper.writeValueAsString(good())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(100)).andReturn().getResponse().getContentAsString();
        String id=mapper.readTree(content).path("reviewId").asText();
        mvc.perform(get("/v1/reviews/"+id).header("X-API-Key","integration-test-key")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PASS"));
        mvc.perform(post("/v1/reviews/"+id+"/milestones").header("X-API-Key","integration-test-key").contentType("application/json").content("{\"name\":\"checkout-v3\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.score").value(100));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE review_id=?",Integer.class,UUID.fromString(id))).isEqualTo(18);
    }
    @Test void duplicateConcurrentRequestsAreIdempotent() throws Exception {
        var request=good(); String key=UUID.randomUUID().toString();
        try(var pool=Executors.newFixedThreadPool(4)) {
            List<Future<ArchitectureReport>> futures=new ArrayList<>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->service.create(request,key)));
            Set<UUID> ids=new HashSet<>(); for(var future:futures) ids.add(future.get(30,TimeUnit.SECONDS).reviewId());
            assertThat(ids).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM review WHERE request_key=?",Integer.class,key)).isEqualTo(1);
        }
    }
    @Test void idempotencyConflictReturns409() throws Exception {
        String key=UUID.randomUUID().toString(); service.create(good(),key);
        var bad=new ReviewRequest(TestSupport.fixture("bad/public-rds.json"),ReviewRequest.Format.CLOUDFORMATION,null,null,null,null,null);
        mvc.perform(post("/v1/reviews").header("X-API-Key","integration-test-key").header("Idempotency-Key",key).contentType("application/json").content(mapper.writeValueAsString(bad))).andExpect(status().isConflict());
    }
    @Test void validatesAndAuthenticatesRequests() throws Exception {
        mvc.perform(get("/v1/rules")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/reviews").header("X-API-Key","integration-test-key").contentType("application/json").content("{}" )).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/reviews/"+UUID.randomUUID()).header("X-API-Key","integration-test-key")).andExpect(status().isNotFound());
    }
    @Test void persistedBaselineDetectsOnlyNewRisksAndIsNotMutated() throws Exception {
        var baseline=service.create(good(),null);
        var unsafe=mapper.readTree(TestSupport.fixture("good/production.json"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)unsafe.path("Resources").path("OrdersDatabase").path("Properties")).put("PubliclyAccessible",true);
        var report=service.create(new ReviewRequest(unsafe.toString(),ReviewRequest.Format.CLOUDFORMATION,null,baseline.reviewId(),null,null,null),null);
        assertThat(report.findings()).hasSize(1); assertThat(report.status()).isEqualTo(ArchitectureReport.Status.FAIL);
        assertThat(service.get(baseline.reviewId()).findings()).isEmpty();
        assertThat(service.baseline("main",baseline.reviewId())).containsEntry("reviewId",baseline.reviewId());
    }
    @Test void databaseOutageReturns503AndRetryCanSucceed() throws Exception {
        String key=UUID.randomUUID().toString();
        String request=mapper.writeValueAsString(good());
        // Pause only this test's disposable PostgreSQL container; JDBC has a two-second socket timeout.
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            mvc.perform(post("/v1/reviews").header("X-API-Key","integration-test-key").header("Idempotency-Key",key)
                    .contentType("application/json").content(request)).andExpect(status().isServiceUnavailable());
        } finally { postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec(); }
        mvc.perform(post("/v1/reviews").header("X-API-Key","integration-test-key").header("Idempotency-Key",key)
                .contentType("application/json").content(request)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review WHERE request_key=?",Integer.class,key)).isEqualTo(1);
    }
}
