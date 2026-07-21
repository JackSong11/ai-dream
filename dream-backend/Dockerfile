FROM maven:3.9.11-eclipse-temurin-21 AS builder
WORKDIR /workspace
COPY dream-backend/pom.xml ./pom.xml
COPY dream-backend/dream-common/pom.xml dream-common/pom.xml
COPY dream-backend/dream-dal/pom.xml dream-dal/pom.xml
COPY dream-backend/dream-integration/pom.xml dream-integration/pom.xml
COPY dream-backend/dream-processor/pom.xml dream-processor/pom.xml
COPY dream-backend/dream-service/pom.xml dream-service/pom.xml
COPY dream-backend/dream-app-web/pom.xml dream-app-web/pom.xml
COPY dream-backend/dream-app-starter/pom.xml dream-app-starter/pom.xml
RUN mvn -B -DskipTests dependency:go-offline
COPY dream-backend/ ./
RUN mvn -B -DskipTests -pl dream-app-starter -am package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=builder /workspace/dream-app-starter/target/dream-app-starter-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
