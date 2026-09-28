# Build the jar, then run it. The cache mount keeps Maven's downloads between builds on your PC.
FROM maven:3-eclipse-temurin-26 AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -ntp -DskipTests package

FROM eclipse-temurin:25-jre-alpine
COPY --from=build /src/target/weather-*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
