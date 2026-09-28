# Build the jar with Maven, then run it on a small Java image. The cache mount keeps downloaded
# dependencies between builds on your PC.
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine
COPY --from=build /src/target/weather-*.jar /app/weather.jar
EXPOSE 8082
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/weather.jar"]
