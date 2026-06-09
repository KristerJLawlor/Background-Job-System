# Background Job System

An asynchronous image processing service. Submit an image URL via the REST API, receive a job ID immediately, and poll for the result when processing is complete. Images are resized to 128×128 PNG with OpenCV face detection for smart cropping (falls back to center crop).

---

## Quickstart

The only prerequisite is [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bash
git clone https://github.com/KristerJLawlor/Background-Job-System.git
cd Background-Job-System
docker compose up -d --build
```

Wait ~30 seconds for services to start, then submit a job:

```bash
curl -X POST "http://localhost:8080/api/jobs?url=https://picsum.photos/300" \
     -H "X-Api-Key: changeme"
```

```json
{ "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2" }
```

Poll until `COMPLETED`, then download the result:

```bash
curl "http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2" \
     -H "X-Api-Key: changeme"
# → COMPLETED

curl "http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2/result" \
     -H "X-Api-Key: changeme" \
     -o avatar.png
```

---

## Services

| Service | URL | Notes |
|---------|-----|-------|
| API | http://localhost:8080 | Job submission and status |
| Grafana | http://localhost:3000 | Metrics dashboards (admin / admin) |
| Jaeger | http://localhost:16686 | Distributed trace viewer |
| Prometheus | http://localhost:9090 | Raw metrics scrape |
| LocalStack (S3) | http://localhost:4566 | Local S3 emulation |
| Redis | localhost:6379 | Queue and job state |

---

## Architecture

Five Gradle modules:

| Module | Type | Role |
|--------|------|------|
| `shared` | Library | `RedisJobStore`, `S3ResultStore`, `JobStatus` — used by both Spring Boot apps |
| `core` | Library | Image download, resize, and OpenCV smart crop |
| `api` | Spring Boot (8080) | Job submission, status/result endpoints, auth, rate limiting |
| `worker` | Spring Boot (8081) | Dequeues jobs from Redis and processes them |
| `cli` | Java app | Legacy standalone CLI |

**Job flow**

```
POST /api/jobs?url=...  (X-Api-Key required)
  → enqueue to Redis (jobs:queue)
  → worker BRPOP
  → download image → smart crop (OpenCV face detection) → resize to 128×128
  → store result in S3 (LocalStack locally)
  → status → COMPLETED

On failure (attempt < 3):
  → schedule exponential backoff retry (10s, 20s, 40s) via Redis sorted set

On final failure:
  → status → FAILED, pushed to dead letter queue (jobs:dlq)
```

---

## API

All endpoints require the `X-Api-Key` header. The default key for local development is `changeme`.

### Submit a job

```
POST /api/jobs?url={imageUrl}
```

```bash
curl -X POST "http://localhost:8080/api/jobs?url=https://picsum.photos/300" \
     -H "X-Api-Key: changeme"
```

```json
{ "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2" }
```

Returns `429 Too Many Requests` if the per-IP rate limit is exceeded (10 requests/minute by default).

### Check status

```
GET /api/jobs/{jobId}
```

```bash
curl "http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2" \
     -H "X-Api-Key: changeme"
```

Response is one of: `PENDING` `PROCESSING` `COMPLETED` `FAILED`

### Download result

```
GET /api/jobs/{jobId}/result
```

```bash
curl "http://localhost:8080/api/jobs/ee0d7b58-363e-4017-a48c-960bc09967f2/result" \
     -H "X-Api-Key: changeme" \
     -o avatar.png
```

Returns PNG bytes. Returns `400` if the job is not yet complete. Returns `410 Gone` if the result was already claimed or has expired (default TTL: 1 day).

### Health

```bash
curl http://localhost:8080/actuator/health
```

---

## Admin endpoints

Manage failed jobs in the dead letter queue. These also require `X-Api-Key`.

```bash
# List failed jobs
curl "http://localhost:8080/api/admin/jobs/failed" -H "X-Api-Key: changeme"

# Requeue a failed job (resets attempt count)
curl -X POST "http://localhost:8080/api/admin/jobs/failed/{jobId}/requeue" \
     -H "X-Api-Key: changeme"

# Delete a failed job from the DLQ
curl -X DELETE "http://localhost:8080/api/admin/jobs/failed/{jobId}" \
     -H "X-Api-Key: changeme"
```

---

## Configuration

Copy `.env.example` to `.env` and edit before starting:

```bash
cp .env.example .env
```

| Variable | Default | Description |
|----------|---------|-------------|
| `API_KEY` | `changeme` | Key required in `X-Api-Key` header — change before exposing externally |
| `REDIS_HOST` | `localhost` (`redis` in Docker) | Redis hostname |
| `JOB_WORKER_THREADS` | `2` | Worker threads blocking on the Redis queue |
| `JOB_RETRY_MAX_ATTEMPTS` | `3` | Max processing attempts before a job goes to the DLQ |
| `JOB_RETRY_BASE_DELAY_SECONDS` | `10` | Base retry delay in seconds; doubles per attempt (10s, 20s, 40s) |
| `JOB_RESULT_TTL_MINUTES` | `60` | How long job status is kept in Redis |
| `JOB_RESULT_EXPIRY_DAYS` | `1` | S3 lifecycle expiry for stored results |
| `JOB_RATE_LIMIT_RPM` | `10` | Max job submissions per IP per minute |
| `TRACING_SAMPLE_RATE` | `1.0` | Fraction of requests to trace (lower for high traffic) |

---

## Useful commands

```bash
# Rebuild after code changes
docker compose up -d --build

# Force rebuild of the worker (e.g. after dependency changes)
docker compose build worker --no-cache

# View logs
docker compose logs -f api
docker compose logs -f worker

# Stop everything
docker compose down

# Stop and wipe all volumes (resets Redis state and S3 data)
docker compose down -v
```

---

## Running without Docker

Requires Java 21, Gradle, and a Redis instance on `localhost:6379`.

```bash
# Build all modules
./gradlew build

# Start the API (http://localhost:8080)
./gradlew :api:bootRun

# Start the worker (separate terminal)
./gradlew :worker:bootRun

# Human-readable logs
./gradlew :api:bootRun --args='--spring.profiles.active=dev'
```

Without LocalStack, S3 storage is unavailable. To test without S3, you would need to swap `S3ResultStore` for a local implementation — not supported out of the box.

---

## Observability

**Grafana** — http://localhost:3000 (admin / admin). A pre-provisioned dashboard shows job throughput, processing duration, queue depth, and retry/DLQ counts.

**Jaeger** — http://localhost:16686. Every job submission produces a trace with a child span covering the actual image processing work.

**Metrics** — available at `/actuator/metrics`:

| Metric | Type | Description |
|--------|------|-------------|
| `jobs.submitted` | Counter | Successful job submissions |
| `jobs.rejected` | Counter | Rejected submissions, tagged by `reason` (`rate_limited`, `invalid_url`) |
| `jobs.processing.duration` | Timer | End-to-end processing time, tagged by `status` |
