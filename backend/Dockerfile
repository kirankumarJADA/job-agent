# syntax=docker/dockerfile:1
# Uses the official Maven image rather than a checked-in wrapper jar, so the
# build doesn't depend on a binary blob living in the repo. If you'd rather
# use ./mvnw, run `mvn -N wrapper:wrapper` once locally and swap this stage.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
# eclipse-temurin's *-jre images don't ship curl by default, but
# infra/docker-compose.yml's backend healthcheck calls curl — without this,
# the healthcheck always reports unhealthy even when the app is fine.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
# Production-safe default for image-based deployments (Render builds this
# Dockerfile directly). Without it a deploy that forgets
# SPRING_PROFILES_ACTIVE silently boots the default profile: registration
# gating off, SameSite=Lax cookies (which cross-origin Vercel browsers will
# not send), and loopback CORS trusted. docker-compose files set their own
# profile and always override this.
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
