# ---------- Build Stage ----------
FROM gradle:8.5-jdk21 AS build
WORKDIR /build

COPY . .

RUN gradle :worker:bootJar --no-daemon

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN useradd -ms /bin/bash appuser

COPY --from=build /build/worker/build/libs/*.jar app.jar

USER appuser

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
