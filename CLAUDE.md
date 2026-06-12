# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build all modules
./gradlew build

# Run a specific module's tests
./gradlew :api:test
./gradlew :shared:test
./gradlew :core:test

# Run a single test class
./gradlew :api:test --tests "com.krister.avatar.api.ImageJobControllerTest"

# Run a single test method
./gradlew :api:test --tests "com.krister.avatar.api.RedisJobStoreIntegrationTest.pushToDlq_appearsInList"

# Start individual services (local dev, requires Redis on localhost:6379)
./gradlew :api:bootRun
./gradlew :worker:bootRun

# Human-readable logs during local dev
./gradlew :api:bootRun --args='--spring.profiles.active=dev'

# Full stack via Docker
docker compose up -d --build

# Force rebuild after dependency changes (required when build.gradle changes affect native JARs)
docker compose build worker --no-cache

# End-to-end smart crop validation (requires stack running)
bash scripts/validate-smart-crop.sh

# Frontend — dev server (proxies /api to localhost:8080)
cd frontend && npm install && npm run dev

# Frontend — production build (outputs to frontend/dist/)
cd frontend && npm run build
```

## Architecture

Five Gradle modules with distinct responsibilities:

| Module | Type | Role |
|--------|------|------|
| `shared` | `java-library` | `RedisJobStore`, `S3ResultStore`, `AwsConfig`, `JobStatus` — shared by both Spring Boot apps |
| `core` | `java-library` | `DiscordImageResizer` (download + resize), `SmartCropper` (OpenCV DNN SSD ResNet face detection, falls back to center crop), `AnimatedGifProcessor` (frame-by-frame GIF resize) |
| `api` | Spring Boot (8080) | Job submission (URL + file upload), status/result endpoints, auth, rate limiting, serves GUI static files |
| `worker` | Spring Boot (8081) | Dequeues jobs from Redis and processes them |
| `cli` | Java application | Legacy batch CLI, standalone |

**Job flow**

```
POST /api/jobs?url=...          (URL submission)
POST /api/jobs/upload           (multipart file upload)
  → file upload: S3ResultStore.storeUpload(jobId, bytes, contentType) → uploads/{jobId}
  → ImageJobService: setStatus(PENDING) + LPUSH jobs:queue
    → URL jobs:   payload url = "https://..."
    → file jobs:  payload url = "s3://uploads/{jobId}"

  → worker BRPOP (JobWorkerPool threads, default 2)
  → JobProcessor: setStatus(PROCESSING)
  → url starts with "s3://uploads/"? → S3ResultStore.downloadUpload(jobId)
  → else → DiscordImageResizer.downloadRaw(url)
  → AnimatedGifProcessor.isAnimatedGif(bytes)?
      yes → AnimatedGifProcessor.process(bytes) → result contentType = "image/gif"
      no  → ImageIO.read → DiscordImageResizer.resizeImage (SmartCropper.smartCrop inside) → ImageIO.write PNG → result contentType = "image/png"
  → S3ResultStore.storeResult(jobId, ProcessingResult) → results/{jobId}
  → if upload: S3ResultStore.deleteUpload(jobId)   ← only after result is stored
  → setStatus(COMPLETED)

On failure (attempt < maxAttempts=3):
  → ZADD jobs:retry (score = now + exponential backoff)
  → RetryPromoter @Scheduled every 5s moves due entries back to jobs:queue
  → upload bytes remain in S3 for retry to re-read

On final failure:
  → setStatus(FAILED) + pushToDlq(jobId, url, attempts, error)

Admin: GET/POST/DELETE /api/admin/jobs/failed  (AdminController)
```

**Redis key layout**

| Key | Structure | Purpose |
|-----|-----------|---------|
| `job:{id}:status` | String | Job status with TTL |
| `jobs:queue` | List | LPUSH/BRPOP work queue |
| `jobs:retry` | Sorted Set | Score = fire-at epoch second |
| `jobs:dlq` | Hash | `jobId → serialized DlqEntry` |

**Auth**

`ApiKeyInterceptor` (plain class, not a Spring bean) intercepts all `/api/**` requests and validates the `X-Api-Key` header against `${API_KEY:changeme}`. Registered in `WebConfig`. Actuator endpoints are excluded.

**S3 / LocalStack**

`S3ResultStore` uses two key prefixes:
- `results/{jobId}` — processed output; content type stored in S3 metadata (no file extension). Lifecycle: expires after `job.result.s3-expiry-days` (default 1 day).
- `uploads/{jobId}` — raw uploaded bytes awaiting worker processing. Lifecycle: expires after 1 day. Workers delete the upload after successfully storing the result; retries re-download it.

`S3ResultStore.@PostConstruct` creates the bucket if missing and sets both lifecycle rules. This fires in every Spring test context — any test that loads the full application context must `@MockBean S3ResultStore s3ResultStore`.

`AwsConfig` uses `pathStyleAccessEnabled(true)` for LocalStack compatibility. LocalStack is pinned to `3.8` in docker-compose.yml — newer versions require a paid license.

**Frontend**

React + Vite app in `frontend/`. Built output (`frontend/dist/`) is injected into `api/src/main/resources/static/` during the Docker build (Node 20 stage in `Dockerfile`), so the GUI is served directly from the Spring Boot jar at `/`.

For local development, run the Vite dev server (`npm run dev` in `frontend/`) which proxies `/api` to `localhost:8080`. The `api/src/main/resources/static/` directory is gitignored — it is generated at build time only.

The GUI polls `GET /api/jobs/{jobId}` every 2 seconds. On `COMPLETED`, it calls `GET /api/jobs/{jobId}/result` to claim and download the result. The API key is stored in browser `localStorage` (default `changeme`).

## Critical Dependency Notes

**OpenCV native JARs** — `core` must declare both platform-specific JARs explicitly as `runtimeOnly`:
```groovy
runtimeOnly 'org.bytedeco:opencv:4.10.0-1.5.11:linux-x86_64'
runtimeOnly 'org.bytedeco:opencv:4.10.0-1.5.11:windows-x86_64'
runtimeOnly 'org.bytedeco:openblas:0.3.28-1.5.11:linux-x86_64'
runtimeOnly 'org.bytedeco:openblas:0.3.28-1.5.11:windows-x86_64'
```
Gradle does not pull classifier-specific JARs transitively. Omitting `openblas` causes `UnsatisfiedLinkError: no jniopenblas_nolapack` at runtime in Docker.

**GTK2 in worker Dockerfile** — `libjniopencv_highgui.so` links against GTK2 even in headless usage. The runtime stage of `worker/Dockerfile` installs `libgtk2.0-0`. Do not remove it.

**AWS SDK scope in `shared/build.gradle`** — `software.amazon.awssdk:s3` is `implementation` (not `api`), so its types are not on consumers' compile classpath. Tests for `S3ResultStore` must live in `shared/src/test/`, not in `api/src/test/`.

**JDK 21 GIF writer limitation** — `GIFWritableStreamMetadata.mergeNativeTree` only accepts `Version`, `LogicalScreenDescriptor`, and `GlobalColorTable`. The Netscape infinite-loop `ApplicationExtension` must go in the **first frame's image metadata** (`GIFWritableImageMetadata`), not the stream metadata. `AnimatedGifProcessor.buildFrameMetadata` handles this; do not move the `ApplicationExtensions` node to stream metadata.

## Testing Patterns

**`@WebMvcTest` controller tests** — must `@MockBean` both `RedisJobStore` and `S3ResultStore` because `shared` is on the component scan path.

**`RedisJobStoreIntegrationTest`** — uses Testcontainers (`redis:7-alpine`) with `@DynamicPropertySource`. Requires Docker; the class is annotated `@Testcontainers(disabledWithoutDocker = true)` so it skips gracefully without Docker.

**OpenCV tests in `SmartCropperTest`** — OpenCV JNI fails on Windows in the Gradle test runner. The `@BeforeAll` probes for native availability and sets a flag; all OpenCV-dependent test methods guard themselves with `assumeTrue(openCvAvailable, ...)`. Pure-Java center-crop tests always run.

## Environment Variables

All variables have working defaults for local dev. Key ones for production:

| Variable | Default | Notes |
|----------|---------|-------|
| `API_KEY` | `changeme` | Set in production |
| `REDIS_HOST` | `localhost` | `redis` in Docker Compose |
| `S3_BUCKET_NAME` | `avatar-results` | |
| `S3_ENDPOINT_OVERRIDE` | _(empty)_ | Set to `http://localstack:4566` for local |
| `JOB_RETRY_MAX_ATTEMPTS` | `3` | |
| `JOB_RETRY_BASE_DELAY_SECONDS` | `10` | Doubles per attempt (10s, 20s, 40s) |
| `JOB_RESULT_EXPIRY_DAYS` | `1` | S3 lifecycle policy for results and uploads |
| `TRACING_SAMPLE_RATE` | `1.0` | Lower in high-traffic prod |
