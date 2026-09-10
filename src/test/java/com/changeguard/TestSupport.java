package com.changeguard;
import com.changeguard.evaluation.*;
import com.changeguard.rules.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.*;
import java.time.*;
public final class TestSupport {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    public static RuleCatalog catalog() throws Exception { return new RuleCatalog(MAPPER, "classpath*:rules/**/*.json"); }
    public static ArchitectureEvaluationService engine() throws Exception { return new ArchitectureEvaluationService(catalog().rules(), new SimpleMeterRegistry(), 1, 50000, Clock.fixed(Instant.parse("2026-09-10T00:00:00Z"), ZoneOffset.UTC)); }
    public static String fixture(String name) throws Exception { return Files.readString(Path.of("fixtures",name)); }
}
