# Performance experiments

Run the API and PostgreSQL, then `k6 run load-tests/review.js`. This exercises 5,000 resources with the actual applicable production rules. Keep HTTP/database latency separate from engine timings.

For the synthetic 100-rule × 5,000-resource experiment:

```sh
mvn test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.changeguard.EngineBenchmark -Dexec.classpathScope=test -Dexec.args=5
```

The standalone harness compares 1/4/8/16 threads, cold/warm cache, P50/P95, throughput, and sampled used heap. It writes `load-tests/results/engine.json`. The 100 rules are synthetic encryption predicates, **not 100 shipped policies**. Warm runs count applicable checks, including cache hits. Heap is sampled after runs, not a peak allocation measurement. Five samples per mode are exploratory; increase the argument for stable percentiles. P95 under two seconds is a target, not a guarantee.

Latest acceptance (September 13): `ITERATIONS=100` produced 100 successful 5,000-resource HTTP reviews with 200/200 checks, median 601 ms and P95 1.10 s. Check failures now fail the k6 run. This repeated-template workload includes cache reuse. The separate engine run used ten samples per mode; see [release evidence](../docs/results/release.json) for source hashes, measurements and scope.
