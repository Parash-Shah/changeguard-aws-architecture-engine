FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN --mount=type=secret,id=maven_truststore \
    if [ -f /run/secrets/maven_truststore ]; then export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/maven_truststore -Djavax.net.ssl.trustStorePassword=changeit"; fi; \
    mvn -B -q dependency:go-offline
COPY src ./src
COPY rules ./rules
RUN --mount=type=secret,id=maven_truststore \
    if [ -f /run/secrets/maven_truststore ]; then export MAVEN_OPTS="-Djavax.net.ssl.trustStore=/run/secrets/maven_truststore -Djavax.net.ssl.trustStorePassword=changeit"; fi; \
    mvn -B -q -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S changeguard && adduser -S changeguard -G changeguard
WORKDIR /app
COPY --from=build /build/target/changeguard-aws-architecture-engine-0.1.0-SNAPSHOT.jar app.jar
USER changeguard
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
