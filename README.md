# Background Job System

An asynchronous image processing service. Submit an image via the browser GUI or REST API, and receive a smart-cropped, resized 128×128 result. Static images output as PNG; animated GIFs are processed frame-by-frame and returned as animated GIFs.

---

## Quickstart

The only prerequisite is [Docker Desktop](https://www.docker.com/products/docker-desktop/).

```bash
git clone https://github.com/KristerJLawlor/Background-Job-System.git
cd Background-Job-System
docker compose up -d --build
```

Wait ~30 seconds for services to start, then open **http://localhost:8080** in your browser.

---

## GUI

The web interface is served from the API container at **http://localhost:8080**.

- **Upload Files tab** — drag-and-drop or click to browse; select multiple images at once (.png, .jpg, .gif, up to 10 MB each)
- **Enter URLs tab** — paste one URL per line; all are submitted as a batch
- Each submitted item appears as a job card showing real-time status; a **Download** button appears automatically when processing completes
- Click ⚙ to set your API key (stored in browser localStorage; default is `changeme`)

---

## Services

| Service | URL | Notes |
|---------|-----|-------|
| API + GUI | http://localhost:8080 | Job submission, status, result download, and web interface |
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
| `core` | Library | Image download, resize, OpenCV smart crop, animated GIF processing |
| `api` | Spring Boot (8080) | Job submission, status/result endpoints, auth, rate limiting, serves GUI |
| `worker` | Spring Boot (8081) | Dequeues jobs from Redis and processes them |
| `cli` | Java app | Legacy standalone CLI |

**Job flow**

```
POST /api/jobs?url=...          (URL submission)
POST /api/jobs/upload           (file upload → stored at uploads/{jobId} in S3)
  → enqueue to Redis (jobs:queue)
  → worker BRPOP
  → download bytes (HTTP or S3 for uploads)
  → animated GIF? → process all frames, return GIF
  → static image?  → smart crop (OpenCV face detection) → resize to 128×128, return PNG
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

### Submit a job from a URL

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

Returns `400` if the URL is invalid or points to a private/reserved IP address. Returns `429` if the per-IP rate limit is exceeded (10 requests/minute by default).

### Submit a job from a file upload

```
POST /api/jobs/upload
Content-Type: multipart/form-data
```

```bash
curl -X POST "http://localhost:8080/api/jobs/upload" \
     -H "X-Api-Key: changeme" \
     -F "file=@avatar.png"
```

```json
{ "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2" }
```

Maximum file size: 10 MB. File must have an `image/*` content type.

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

Returns the processed image bytes. Content type is `image/png` for static images and `image/gif` for animated GIFs. Returns `400` if the job is not yet complete. Returns `410 Gone` if the result was already downloaded or has expired (default TTL: 1 day). Results are one-shot — downloading consumes the result.

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

Requires Java 21, Node.js 20, Gradle, and a Redis instance on `localhost:6379`.

```bash
# Build all Java modules
./gradlew build

# Start the API (http://localhost:8080)
./gradlew :api:bootRun

# Start the worker (separate terminal)
./gradlew :worker:bootRun

# Run the frontend dev server (http://localhost:5173, proxies /api to port 8080)
cd frontend && npm install && npm run dev
```

The frontend dev server proxies all `/api` requests to the Spring Boot API, so you can develop the UI without rebuilding the Java jar. Without LocalStack, S3 storage is unavailable.

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
