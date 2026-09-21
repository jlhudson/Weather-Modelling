# Multi-stage: a Maven build stage, then a JRE-only runtime on a slim base.
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
    && addgroup -S gully && adduser -S gully -G gully
USER gully
WORKDIR /app
COPY --from=build /src/target/weather-*.jar /app/gully.jar
EXPOSE 8082
# -XX:TieredStopAtLevel=1 and -Xshare are the two flags that bring a Boot start under three seconds
# on a small VPS: the service is I/O-bound on a handful of polls, not CPU-bound, and C2 buys nothing.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:TieredStopAtLevel=1", "-Xshare:auto", "-jar", "/app/gully.jar"]
