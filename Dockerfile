# ---------- Frontend Build ----------
FROM node:20-alpine AS frontend
WORKDIR /frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

# ---------- Java Build ----------
FROM gradle:8.5-jdk21 AS build
WORKDIR /build

# Inject built frontend before Gradle picks up the source tree
COPY --from=frontend /frontend/dist api/src/main/resources/static
COPY . .

RUN gradle :api:bootJar --no-daemon

# ---------- Runtime ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

RUN useradd -ms /bin/bash appuser

COPY --from=build /build/api/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java","-jar","app.jar"]
