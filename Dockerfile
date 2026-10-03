# Build the jar, then run it. The cache mount keeps Maven's downloads between builds on your PC. Every
# application's Dockerfile is this file with its own jar, plus anything its runtime needs.
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre
COPY --from=build /src/target/weather-*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
