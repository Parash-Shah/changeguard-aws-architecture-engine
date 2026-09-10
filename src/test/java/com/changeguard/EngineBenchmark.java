package com.changeguard;

import com.changeguard.evaluation.*;
import com.changeguard.model.*;
import com.changeguard.rules.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.stream.IntStream;

/** Standalone synthetic microbenchmark. Excludes HTTP, parsing, persistence and AWS calls. */
public final class EngineBenchmark {
    public static void main(String[] args) throws Exception {
        int samples = args.length == 0 ? 5 : Integer.parseInt(args[0]);
        List<ArchitectureRule> rules = IntStream.range(0,100).mapToObj(i -> (ArchitectureRule)new DeclarativeRule(new RuleDefinition(
                "BENCH-"+i,"1","Synthetic encryption predicate",Pillar.SECURITY,Severity.HIGH,List.of("AWS::EC2::Volume"),
                "Encrypted",RuleDefinition.Operator.EQUALS,true,"Synthetic benchmark","Enable encryption","https://example.invalid/benchmark",false))).toList();
        List<CloudResource> resources = IntStream.range(0,5000).mapToObj(i -> new CloudResource("volume-"+i,"AWS::EC2::Volume",Map.of("Encrypted",true))).toList();
        List<Map<String,Object>> results = new ArrayList<>();
        // One unrecorded warmup of compiled evaluation paths, with caching disabled.
        var warmup = new ArchitectureEvaluationService(rules,new SimpleMeterRegistry(),1,0,Clock.systemUTC());
        warmup.evaluate(resources,null,List.of(),QualityGate.defaults(),List.of()); warmup.close();
        for(int threads : new int[]{1,4,8,16}) {
            List<Double> cold=new ArrayList<>(), warm=new ArrayList<>();
            long heap=0;
            for(int i=0;i<samples;i++) {
                var engine=new ArchitectureEvaluationService(rules,new SimpleMeterRegistry(),threads,600000,Clock.systemUTC());
                try {
                    long start=System.nanoTime(); var a=engine.evaluate(resources,null,List.of(),QualityGate.defaults(),List.of()); cold.add((System.nanoTime()-start)/1e6);
                    start=System.nanoTime(); var b=engine.evaluate(resources,null,List.of(),QualityGate.defaults(),List.of()); warm.add((System.nanoTime()-start)/1e6);
                    if(a.evaluations()!=500000 || b.score()!=100) throw new IllegalStateException("Invalid benchmark result");
                    heap=Math.max(heap,ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                } finally { engine.close(); }
                System.gc(); // Outside measured intervals; reduce cross-configuration heap carryover.
            }
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("threads",threads); result.put("resources",5000); result.put("applicableChecks",500000); result.put("samplesPerMode",samples);
            result.put("coldP50Ms",percentile(cold,.5)); result.put("coldP95Ms",percentile(cold,.95));
            result.put("warmP50Ms",percentile(warm,.5)); result.put("warmP95Ms",percentile(warm,.95));
            result.put("coldChecksPerSecond",500000d/(percentile(cold,.5)/1000)); result.put("maxObservedHeapBytes",heap);
            result.put("coldSamplesMs",cold); result.put("warmSamplesMs",warm); results.add(result);
            System.out.println(TestSupport.MAPPER.writeValueAsString(result));
        }
        Files.createDirectories(Path.of("load-tests/results"));
        Files.writeString(Path.of("load-tests/results/engine.json"),TestSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "java",System.getProperty("java.version"),"os",System.getProperty("os.name"),"processors",Runtime.getRuntime().availableProcessors(),
                "scope","Synthetic in-process predicate benchmark; warm counts include cache hits; not API latency", "results",results)));
    }
    private static double percentile(List<Double> values,double p) { var sorted=values.stream().sorted().toList(); return sorted.get(Math.max(0,(int)Math.ceil(p*sorted.size())-1)); }
}
