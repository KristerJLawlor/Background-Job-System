# ---------- Build Stage ----------
FROM gradle:8.5-jdk21 AS build
WORKDIR /build

COPY . .

RUN gradle :worker:bootJar --no-daemon

# ---------- Runtime Stage ----------
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# opencv_highgui (a transitive dep of opencv_objdetect/CascadeClassifier in the
# bytedeco prebuilt JARs) links against GTK2 even in headless usage.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgtk2.0-0 \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -ms /bin/bash appuser

COPY --from=build /build/worker/build/libs/*.jar app.jar

USER appuser

EXPOSE 8081

HEALTHCHECK --interval=30s --timeout=5s \
  CMD curl -f http://localhost:8081/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
