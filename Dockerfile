# syntax=docker/dockerfile:1

# ------------------------------------------------------------------
# Stage 1: build the application fat jar.
# Maven dependencies are resolved before copying sources so that builds
# that only change application code reuse Docker's layer cache.
# ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline || true

COPY src ./src
RUN mvn -q -DskipTests package

# ------------------------------------------------------------------
# Stage 2: minimal runtime image (JRE only, non-root user).
# ------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --no-create-home --uid 1001 appuser

COPY --from=builder /build/target/*.jar /app/app.jar

USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]