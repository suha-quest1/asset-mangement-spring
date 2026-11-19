# Multi-stage build for all Java services (one jar, several entry points).
# --build-arg SERVICE=worker|apiserver|mockses|mockcreditfacility|demodriver
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /src
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

# Ubuntu-based JRE image: published for amd64 and arm64 (Apple Silicon); includes CA certificates and tzdata.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /src/target/camunda-assetmgmt.jar /app/app.jar
COPY bpmn /app/bpmn
ARG SERVICE
ENV SERVICE=${SERVICE}
# Small, predictable footprint per service; override with -e JAVA_OPTS=...
ENV JAVA_OPTS="-XX:+UseSerialGC -Xmx256m"
# Extra arguments (e.g. `docker compose run demo-driver -gp-decision waive`) are passed through.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$SERVICE\" \"$@\"", "--"]
