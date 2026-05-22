# Background Job System

A Java backend for asynchronous image processing. Clients submit an image URL, receive a job ID immediately, and poll for the result when processing is complete. The system resizes images to 128×128 Discord avatar format with center-cropping and multi-step BICUBIC interpolation.

Built for AWS ECS deployment. Designed to evolve toward a Redis-backed distributed queue.

---

## Architecture

Three Gradle modules:

| Module | Responsibility |
|--------|---------------|
| `core` | Image download, crop, and resize logic |
| `api`  | Spring Boot REST API — job submission, status tracking, result delivery |
| `cli`  | Standalone CLI for manual and batch testing without the API |

**Job flow**

```
POST /api/jobs?url=...
        │
        ▼
  Rate limiter + URL validator
        │
        ▼
  Job queue (bounded thread pool)
        │
        ▼
  Worker thread: download → center-crop → resize → store
        │
        ▼
  GET /api/jobs/{jobId}/result  →  PNG bytes
```

---

## Running locally

Docker Compose starts the API, Redis, and Jaeger (trace UI) together:

```bash
docker compose up
```

| Service | URL |
|---------|-----|
| API     | http://localhost:8080 |
| Jaeger trace UI | http://localhost:16686 |
| Redis   | localhost:6379 |

To rebuild the API image after a code change:

```bash
docker compose up --build api
```

---

## Running without Docker

Requires Java 21 and Gradle.

```bash
# Build all modules
./gradlew build

# Run the API (http://localhost:8080)
./gradlew :api:bootRun

# Run the CLI
./gradlew :cli:run
```

For human-readable logs during local development, activate the `dev` profile:

```bash
./gradlew :api:bootRun --args='--spring.profiles.active=dev'
```

---

## API

### Submit a job

```
POST /api/jobs?url={imageUrl}
```

```bash
curl -X POST "http://localhost:8080/api/jobs?url=https://picsum.photos/300"
```

```json
{ "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2" }
```

### Check job status

```
GET /api/jobs/{jobId}
```

```bash
curl http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2
```

Response is one of: `PENDING` `PROCESSING` `COMPLETED` `FAILED`

### Retrieve the processed image

```
GET /api/jobs/{jobId}/result
```

Returns PNG bytes. Only available once; claiming the result frees it from memory.

```bash
curl http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2/result \
     -o avatar.png
```

Returns `400` if the job is not yet complete, `410 Gone` if the result was already claimed or expired.

### Health and metrics

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/metrics/jobs.processing.duration
```

---

## Environment variables

All variables have defaults and are optional unless deploying to a non-local environment.

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `AVATAR_DIR` | `/app/avatars` | Output directory for processed images |
| `JOB_EXECUTOR_CORE_POOL_SIZE` | `2` | Worker threads kept alive when idle |
| `JOB_EXECUTOR_MAX_POOL_SIZE` | `4` | Maximum concurrent worker threads |
| `JOB_EXECUTOR_QUEUE_CAPACITY` | `100` | Job backlog before queue-full rejection |
| `JOB_RATE_LIMIT_RPM` | `10` | Max job submissions per IP per minute |
| `JOB_RESULT_TTL_MINUTES` | `60` | Minutes before unclaimed results are evicted |
| `JOB_RESULT_EVICTION_INTERVAL_MS` | `60000` | How often the eviction sweep runs (ms) |
| `TRACING_SAMPLE_RATE` | `1.0` | Fraction of requests to trace (lower in high-traffic prod) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP collector endpoint |

---

## Observability

**Structured logs** — JSON output by default (one object per line), readable by CloudWatch Logs Insights and Datadog without a parser. Every log line during job processing carries `jobId`, `traceId`, and `spanId` as top-level fields.

Switch to human-readable output for local development with `--spring.profiles.active=dev`.

**Metrics** — available at `/actuator/metrics`:

| Metric | Type | Description |
|--------|------|-------------|
| `jobs.submitted` | Counter | Successful job submissions |
| `jobs.rejected` | Counter | Rejected submissions, tagged by `reason` (`rate_limited`, `invalid_url`, `queue_full`) |
| `jobs.active` | Gauge | Jobs currently being processed |
| `jobs.processing.duration` | Timer | End-to-end processing time, tagged by `status` (`completed`, `failed`) |
| `jobs.evicted` | Counter | Jobs cleaned up by the TTL sweep |

**Distributed tracing** — spans exported via OTLP. In Docker Compose, traces are visible in the Jaeger UI at http://localhost:16686. Each trace shows the HTTP submission span and a `job.process` child span covering the actual image work.

---

## Roadmap

- [ ] Redis-backed distributed queue (replace in-memory executor queue)
- [ ] Dedicated worker service
- [ ] AWS S3 result storage (replace in-memory + local disk)
- [ ] Retry handling for transient download failures
- [ ] Smart subject-aware cropping (OpenCV face detection)
- [ ] Manual crop override via API
