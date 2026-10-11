# Jacaranda Scout planner (Spring Boot). Used by render.yaml; also runs anywhere with Docker:
#   docker build -t jacaranda-scout . && docker run -p 8080:8080 jacaranda-scout
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/jacaranda-scout-*.jar app.jar
# small instances: let the heap use most of the container's RAM
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
