# Multi-stage: a Maven build stage, then a JRE-only runtime on a slim base (docs/14-platform.md).
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
# Resolve dependencies first so a source change does not invalidate the dependency layer.
COPY pom.xml .
RUN mvn -q -B -ntp -DskipTests dependency:go-offline || true
COPY . .
RUN mvn -q -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine
ENV TZ=UTC
RUN apk add --no-cache wget tzdata \
    && addgroup -S weather && adduser -S weather -G weather
USER weather
WORKDIR /app
COPY --from=build /src/target/weather-*.jar /app/weather.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/weather.jar"]
