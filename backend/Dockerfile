# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S lockstep && adduser -S -G lockstep lockstep
WORKDIR /app
COPY --from=build --chown=lockstep:lockstep /workspace/target/lockstep-tickets-backend-*.jar app.jar
USER lockstep:lockstep
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]

