# The Prior Art team server: Spring Boot on :8080, the API used by the plugin, the capture hooks and the MCP server.
FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /server
COPY server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY server/src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=server /server/target/priorart-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
