FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY apps/trainer-core/pom.xml apps/trainer-core/pom.xml
COPY apps/trainer-mcp/pom.xml apps/trainer-mcp/pom.xml
COPY apps/trainer-mcp/src apps/trainer-mcp/src
RUN mvn -pl apps/trainer-mcp -am -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/apps/trainer-mcp/target/trainer-mcp-*.jar app.jar
EXPOSE 8787
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
