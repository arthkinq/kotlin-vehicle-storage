FROM gradle:8.5-jdk17 AS builder
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar ./server.jar

EXPOSE 8888

ENTRYPOINT ["java", "-jar", "server.jar"]
