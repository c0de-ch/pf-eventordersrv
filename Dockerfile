# syntax=docker/dockerfile:1
FROM eclipse-temurin:25.0.4.1_1-jdk-alpine AS build
WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package \
 && java -Djarmode=tools -jar target/order-service-*.jar extract --layers --destination target/extracted --application-filename app.jar

FROM eclipse-temurin:25.0.4.1_1-jre-alpine
LABEL org.opencontainers.image.source="https://github.com/c0de-ch/pf-eventordersrv"
RUN addgroup -S app && adduser -S -G app -u 10001 app
WORKDIR /app
# Layers ordered from least to most volatile, so a code change rebuilds only the last one
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./
USER 10001
EXPOSE 8080
# Layout from "extract --layers": app.jar next to lib/, started like any jar
ENTRYPOINT ["java", "--sun-misc-unsafe-memory-access=allow", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
