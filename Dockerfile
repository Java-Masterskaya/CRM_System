FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /application
COPY gradle gradle
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /application
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup
COPY --from=builder /application/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
