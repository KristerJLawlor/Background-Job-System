# ---------- Build Stage ----------
FROM gradle:8.5-jdk21 AS build
WORKDIR /build

COPY . .

RUN gradle :api:bootJar --no-daemon

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# create non-root user to run the application
RUN useradd -ms /bin/bash appuser

COPY --from=build /build/api/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s \
CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-jar","app.jar"]