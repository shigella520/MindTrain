FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps/trainer-core/pom.xml apps/trainer-core/pom.xml
COPY apps/trainer-mcp/pom.xml apps/trainer-mcp/pom.xml
COPY apps/trainer-core/src apps/trainer-core/src
RUN mvn -pl apps/trainer-core -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/apps/trainer-core/target/trainer-core-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
