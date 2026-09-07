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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
