# syntax=docker/dockerfile:1.7

# ---- build stage ---------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy Maven descriptor first for better layer caching
COPY pom.xml ./
COPY .mvn ./.mvn
COPY mvnw ./mvnw
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline || true

COPY src ./src
RUN ./mvnw -B -q -DskipTests package && \
    cp target/*.jar target/app.jar

# ---- runtime stage -------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/target/app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+UseContainerSupport"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

