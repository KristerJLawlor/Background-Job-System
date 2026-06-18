# AI_CONTEXT.md

# Background Job System

## Project Overview

An asynchronous image processing service built in Java 21 / Spring Boot 3. Accepts image jobs via REST API or browser GUI, processes them in background worker threads, and produces smart-cropped 128×128 Discord avatars stored in S3-compatible object storage.

Structured as a Gradle multi-module monorepo, containerized with Docker Compose for local development, and deployed to Render's free tier for public access.

---

## Modules

| Module | Type | Role |
|--------|------|------|
| `shared` | `java-library` | `RedisJobStore`, `S3ResultStore`, `AwsConfig`, `JobStatus` — shared by both Spring Boot apps |
| `core` | `java-library` | `DiscordImageResizer` (download + multi-step bicubic resize), `SmartCropper` (OpenCV DNN SSD ResNet face detection, falls back to center crop), `AnimatedGifProcessor` (frame-by-frame GIF resize) |
| `api` | Spring Boot (8080) | Job submission (URL + file upload), status/result endpoints, API key auth, IP rate limiting, global daily quota, serves React GUI; also runs `JobWorkerPool`, `JobProcessor`, and `RetryPromoter` in-process for the Render free-tier cloud deployment |
| `worker` | Spring Boot (8081) | Standalone worker process — used in local Docker Compose; the same worker logic (`JobWorkerPool`, `JobProcessor`, `RetryPromoter`) exists in both `api` and `worker` packages to support both deployment topologies |
| `cli` | Java app | Legacy batch CLI for local testing |

---

## Current Features

- Async background job processing via Redis LPUSH/BRPOP queue
- REST API for job submission (URL + multipart file upload), status polling, result download
- Browser GUI (React + Vite) served from the API jar; polls status every 2 s, auto-downloads on completion
- OpenCV DNN SSD ResNet face detection with smart crop; center-crop fallback; images pre-downsampled to 600 px max before DNN to reduce CPU cost
- Animated GIF support: frame-by-frame crop + resize, timing preserved; single crop rectangle computed from first frame and applied to all frames
- WebP support via TwelveMonkeys ImageIO (`imageio-webp`) — plugs into `ImageIO.read()` via ServiceLoader
- Content-Type validation on URL downloads: fails fast with a descriptive error if the URL returns HTML (e.g. Tenor/Giphy share pages) instead of an image
- Exponential backoff retries (3 attempts: 10 s, 20 s, 40 s) via Redis sorted set + `RetryPromoter`
- Dead letter queue (`jobs:dlq` Redis hash) + `AdminController` (list / requeue / delete failed jobs)
- S3 result storage: LocalStack locally, Cloudflare R2 in production
- IP-based token bucket rate limiting (Bucket4j, default 10 req/min)
- Global daily job quota (default 500/day) via Redis atomic INCR
- Prometheus metrics, Grafana dashboards, OTLP/Jaeger distributed tracing
- Docker Compose full stack: api, worker, redis, localstack:3.8, prometheus, grafana, jaeger

---

## Job Flow

```
POST /api/jobs?url=...          (URL submission)
POST /api/jobs/upload           (multipart file upload)
  → file upload: S3ResultStore.storeUpload(jobId, bytes) → uploads/{jobId}
  → setStatus(PENDING) + LPUSH jobs:queue

  → [JobWorkerPool BRPOP]
  → setStatus(PROCESSING)
  → url starts with "s3://uploads/"? → S3ResultStore.downloadUpload(jobId)
  → else → DiscordImageResizer.downloadRaw(url)
      → Content-Type check: throws immediately if text/* (page URL, not image)
  → AnimatedGifProcessor.isAnimatedGif(bytes)?
      yes → AnimatedGifProcessor.process(bytes) → result contentType = "image/gif"
      no  → ImageIO.read → DiscordImageResizer.resizeImage (SmartCropper inside) → PNG → "image/png"
  → S3ResultStore.storeResult(jobId, ProcessingResult) → results/{jobId}
  → if upload: deleteUpload(jobId)
  → setStatus(COMPLETED)

On failure (attempt < 3):
  → ZADD jobs:retry (score = now + backoffSeconds)
  → RetryPromoter @Scheduled every 5 s moves due entries back to jobs:queue

On final failure:
  → setStatus(FAILED) + pushToDlq(jobId, url, attempts, error)
```

---

## Deployment

### Local (Docker Compose)

```bash
docker compose up -d --build
```

Opens at http://localhost:8080. API and worker run as separate containers; Redis and LocalStack are local.

### Cloud (Render free tier)

Live at https://avatar-api-gzvg.onrender.com

- **Free tier constraints:** 0.1 CPU, 512 MB RAM. Processing takes 20–60 s per job depending on image size. Service sleeps after 15 min inactivity (~30 s cold start).
- Worker logic merged into the `api` module — no separate worker service needed, which avoids Render's paid background worker tier ($7/month minimum).
- S3 storage: Cloudflare R2 (free tier: 10 GB, no egress fees).
- `render.yaml` blueprint creates `avatar-api` (web, free) and `avatar-redis` (Redis, free).

---

## Storage Model

- `results/{jobId}` — processed output; S3 object metadata stores content type; expires after 1 day.
- `uploads/{jobId}` — raw upload bytes awaiting worker; deleted after successful processing; expires after 1 day.
- Results are one-shot: `claimResult()` downloads and deletes in one call.

---

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/jobs?url=...` | Submit URL job; returns `{"jobId":"..."}` |
| `POST` | `/api/jobs/upload` | Submit file upload job; multipart `file` field |
| `GET` | `/api/jobs/{jobId}` | Status: `PENDING` / `PROCESSING` / `COMPLETED` / `FAILED` |
| `GET` | `/api/jobs/{jobId}/result` | Claim result (one-shot, deletes from S3) |
| `GET` | `/api/admin/jobs/failed` | List DLQ entries |
| `POST` | `/api/admin/jobs/failed/{jobId}/requeue` | Move DLQ entry back to queue |
| `DELETE` | `/api/admin/jobs/failed/{jobId}` | Discard DLQ entry |
| `GET` | `/actuator/health` | Health check |

All `/api/**` endpoints require `X-Api-Key` header. Default: `changeme`.

---

## Run Commands

```bash
# Build all modules
./gradlew build

# Local dev (requires Redis on localhost:6379)
./gradlew :api:bootRun
./gradlew :worker:bootRun

# Full stack via Docker
docker compose up -d --build

# End-to-end smart crop validation (requires stack running)
bash scripts/validate-smart-crop.sh

# Frontend dev server (proxies /api to localhost:8080)
cd frontend && npm install && npm run dev
```

---

## Hard Constraints

Do NOT:
- Replace Gradle with Maven
- Collapse modules into a single project
- Remove Docker support
- Remove asynchronous processing
- Remove the modular multi-module structure
