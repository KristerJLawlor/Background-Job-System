# Architecture & Design

This document explains how the Background Job System is structured and the reasoning behind each major decision.

---

## Problem Statement

The system resizes and smart-crops images to Discord avatar dimensions (128×128). Two properties make this hard to do synchronously in a web request:

1. **Variable latency** — downloading from an arbitrary URL can take seconds, and DNN face detection can spike CPU for a moment.
2. **Bursty load** — many requests can arrive at once, but processing capacity is bounded by CPU and memory.

The solution is a classic producer/consumer pipeline: the API accepts work instantly and returns a job ID, a worker pool consumes the queue asynchronously, and the client polls for the result.

---

## Module Structure

The project is split into five Gradle modules:

| Module | Type | Responsibility |
|--------|------|---------------|
| `shared` | `java-library` | `RedisJobStore`, `S3ResultStore`, `JobStatus` — consumed by both apps |
| `core` | `java-library` | All image processing logic |
| `api` | Spring Boot (port 8080) | Job intake, status/result endpoints, auth, rate limiting |
| `worker` | Spring Boot (port 8081) | Dequeues and processes jobs |
| `cli` | Java application | Legacy standalone batch tool |

**Why separate `api` and `worker` into two Spring Boot apps instead of one?**

Because they have different scaling and resource profiles. The API is I/O-bound and benefits from horizontal scaling; the worker is CPU-bound and needs tuning around thread count and JVM heap. Running them as independent processes means you can deploy more API replicas without duplicating worker threads, and a crash in the worker doesn't take down the API. In Docker Compose they communicate only through Redis — neither service has a direct network dependency on the other.

**Why `shared` and `core` as `java-library` modules instead of Spring Boot apps?**

They contain no web layer and don't need embedded Tomcat. Making them plain `java-library` modules keeps the dependency graph clean: `api` and `worker` each pull in exactly what they need without inheriting each other's classpath.

---

## Queue: Redis List

Jobs are enqueued with `LPUSH jobs:queue` and dequeued with `BRPOP jobs:queue`. This is a blocking pop — worker threads sleep inside Redis until work arrives, with a 2-second timeout so they can check a shutdown flag.

**Why Redis instead of a dedicated message broker (RabbitMQ, Kafka)?**

The system already needs Redis for job status storage. Adding a second broker would double the operational complexity (two services to run, monitor, and keep available) for a workload that doesn't need Kafka's log retention or RabbitMQ's topic routing. A Redis list is a queue: LPUSH pushes to the head, BRPOP pops from the tail, giving FIFO order. It is battle-tested at this use case and costs nothing extra.

**Why not a database table as a queue?**

Polling a database for new rows (SELECT ... WHERE status = 'pending') causes lock contention under load and adds latency between enqueue and dequeue. BRPOP is a push notification — the database polling pattern is an anti-pattern for queues.

---

## Job State: Redis Strings with TTL

Job status (`PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`) is stored in `job:{id}:status` as a plain string with a TTL (default 60 minutes). When the key expires, the job is forgotten automatically — no background cleanup job needed.

**Why TTL-based expiry instead of explicit deletion?**

Results are transient by nature. A completed avatar is only useful until the user downloads it. TTL expiry is a zero-maintenance cleanup mechanism: Redis handles it, it scales automatically, and there is no risk of a cleanup batch job falling behind under load.

**Why store status in Redis rather than a relational database?**

Job status is hot, write-heavy, and short-lived. A relational database is durable, transactional, and designed for long-lived data. Using a database for job status would require a schema, migrations, connection pooling, and periodic cleanup — all for data that doesn't need to outlive a TTL. Redis is the right tool for ephemeral state.

---

## Result Storage: S3 (or Compatible)

Processed image bytes are written to S3 at `results/{jobId}` with the content type in S3 object metadata. The `claimResult()` method reads the bytes and then deletes the object in a single operation — results are one-shot.

**Why S3 instead of storing results in Redis?**

Redis keeps everything in memory. Storing binary image data in Redis would consume gigabytes of RAM under load and conflict with Redis's role as a fast key-value cache. S3 (and compatible stores like Cloudflare R2 or LocalStack in dev) is designed for large binary objects, is cheap per GB, and has native lifecycle policies for automatic expiry.

**Why Cloudflare R2 / endpoint override instead of AWS S3?**

R2 has no egress fees. For a public-facing service that delivers image files to browsers, egress costs on AWS S3 would be the dominant infrastructure cost. R2's S3-compatible API means zero code changes — only the endpoint URL and credentials differ between environments. LocalStack is used in development for the same reason: identical code paths, zero cloud costs during development.

**Why S3 lifecycle policies instead of application-level cleanup?**

S3 lifecycle policies run server-side on a schedule without application involvement. Application-level cleanup is error-prone: a crashed worker or a bug in cleanup logic leaves orphaned data. Lifecycle policies are declarative and guaranteed by the storage layer.

---

## Retry Queue: Redis Sorted Set

When a job fails and has remaining attempts, it is added to `jobs:retry` with `ZADD`, using the target fire-at timestamp as the score. A `RetryPromoter` thread polls every 5 seconds, finds entries with scores ≤ now, and moves them back to `jobs:queue`.

**Why a sorted set instead of re-enqueuing immediately?**

Re-enqueuing immediately after failure causes hot loops: if a transient error (network timeout, S3 unavailable) is causing failures, hammering the same job repeatedly makes it worse. Exponential backoff (10s, 20s, 40s by default) gives the downstream system time to recover. A sorted set ordered by fire-at time is a natural priority queue for time-delayed work — Redis's `ZRANGEBYSCORE` retrieves exactly the entries due without scanning the whole set.

**Why a separate thread for retry promotion instead of a timer in the worker?**

Concerns are separated: the worker threads do only I/O-bound job processing. The `RetryPromoter` is a lightweight scheduler that does only Redis reads and writes. This makes each component easier to reason about and test independently.

---

## Dead Letter Queue: Redis Hash

After exhausting all retry attempts, a job is written to `jobs:dlq` (a Redis hash, keyed by job ID) with the error message, URL, and attempt count. The Admin API exposes endpoints to list, requeue, and delete DLQ entries.

**Why a hash instead of another list?**

DLQ entries need to be addressed individually — you want to requeue or delete one specific failed job, not process them in order. A hash provides O(1) lookup by job ID. A list would require scanning to find a specific entry.

**Why keep a DLQ at all?**

Without a DLQ, permanently failed jobs vanish silently. The DLQ makes failures visible and recoverable. An operator can inspect the error, fix the underlying problem (e.g., the source URL is now available), and requeue the job without re-submitting it through the API.

---

## Worker Thread Pool

`JobWorkerPool` starts a fixed number of platform threads (default 2) at startup. Each thread loops on `BRPOP` with a 2-second timeout, processing one job at a time. Shutdown is coordinated by setting a volatile boolean flag that each thread checks on the BRPOP timeout.

**Why platform threads instead of virtual threads?**

The worker threads block on CPU-intensive work (DNN inference, image scaling). Virtual threads shine when threads block on I/O and the scheduler can multiplex them onto a small carrier thread pool. Pinning a virtual thread to a carrier during CPU-bound work defeats the purpose. Platform threads are the right model when work is CPU-bound.

**Why a fixed thread count instead of a dynamic pool?**

Image processing is CPU-bound. Adding more threads than CPU cores doesn't increase throughput — it increases context-switching overhead. The default of 2 threads can be tuned via `JOB_WORKER_THREAD_COUNT` to match the available core count of the deployment host.

---

## Rate Limiting

Two layers of rate limiting protect the system:

**Per-IP token bucket** (`IpRateLimiter`, powered by Bucket4j): Each IP address gets a token bucket with a configurable refill rate (default 10 requests/minute). Tokens are consumed on each job submission. The bucket uses a greedy refill strategy, which distributes token replenishment smoothly rather than giving a full burst at the top of each minute.

**Global daily quota** (`GlobalJobQuota`): A Redis INCR counter keyed by UTC date (`global:jobs:daily:{yyyy-MM-dd}`) tracks total jobs accepted across all IPs. When the counter exceeds the configured limit (default 500/day), the API returns HTTP 429. The key expires after 2 days, so no cleanup is needed. The quota check **fails open** — if Redis is unavailable, the job is accepted rather than rejected, because Redis downtime should not cause user-facing errors for a non-critical limit.

**Why both layers?**

Per-IP limiting prevents a single client from monopolizing the queue. Global limiting protects S3 storage costs and prevents queue saturation regardless of how many distinct IPs are hammering the system. They defend against different attack shapes.

**Why Bucket4j's token bucket instead of a simple request counter?**

A counter resets at a fixed interval, which creates a "double burst" window: a client can fire 10 requests just before the reset and 10 more just after. Token buckets smooth this out — tokens replenish continuously, not all at once, so the effective rate is enforced over time rather than per window.

---

## SSRF Prevention

Before a URL is enqueued, `UrlValidator` performs two checks:

1. **Format validation** — only `http://` and `https://` schemes are accepted.
2. **DNS resolution + reserved range filtering** — the hostname is resolved to an IP address, which is checked against loopback (127.x.x.x), private (10.x, 172.16-31.x, 192.168.x), link-local (169.254.x), and multicast ranges.

**Why is SSRF a concern here?**

Without validation, an attacker could submit `http://169.254.169.254/latest/meta-data/` (AWS instance metadata) or `http://localhost:6379/` (Redis) and the worker would faithfully download and process it, potentially leaking internal network responses. SSRF is in the OWASP Top 10 because it is a real attack vector on any service that fetches URLs on behalf of users.

**Why check at the DNS level rather than the URL level?**

DNS-based filtering catches rebinding attacks where a hostname initially resolves to a public IP but is later updated to resolve to an internal IP. Validating only the URL string lets through `http://internal.attacker.com/` if the attacker controls the DNS record.

---

## Auth: API Key Interceptor

`ApiKeyInterceptor` is a Spring `HandlerInterceptor` that validates the `X-Api-Key` header on every request to `/api/**`. It is registered in `WebConfig`, not via Spring Security.

**Why a plain `HandlerInterceptor` instead of Spring Security?**

Spring Security adds significant complexity: filter chains, security contexts, authentication providers, authorization rules. For a single shared API key, that overhead is not justified. An interceptor is a dozen lines of code, trivially testable, and perfectly sufficient. If per-user API keys with roles and scopes were needed, Spring Security would become worthwhile.

**Why a header instead of a query parameter?**

Query parameters appear in server logs and browser history. Headers do not. The API key should not leak into access logs.

---

## Image Processing

### Smart Crop (OpenCV DNN SSD ResNet)

`SmartCropper` runs a Single Shot MultiBox Detector (SSD) with a ResNet-10 backbone, distributed with OpenCV, to detect faces in an image before cropping. If a face is found, the crop is centered on it with padding; if no face is found, a center crop is used as a fallback.

**Why face detection instead of always center-cropping?**

Discord avatars are profile pictures. People are rarely centered in their photos — they may be off to one side, partially cropped, or at an angle. Center-cropping a portrait often produces a chest-level crop. Face detection produces a visually correct avatar even when the subject is not centered.

**Why SSD ResNet-10 instead of a more accurate model?**

Accuracy vs. latency tradeoff. SSD ResNet-10 is a lightweight detector that runs in milliseconds on CPU. MTCNN or larger YOLO-family models are more accurate but add seconds of per-image latency on CPU. For avatar cropping, the threshold for "good enough" detection is low — even a rough bounding box produces a better crop than center cropping.

**Why a confidence threshold of 0.1 (very low)?**

The use case includes photos with angled faces, distant faces, and partial occlusion. A high confidence threshold (0.5+) would reject most of these and fall back to center crop, negating the purpose of detection. A low threshold accepts weak detections. The worst case is an incorrect bounding box that still produces a face-region crop, which is typically better than a center crop for portrait photos.

**Why pre-downsample to 600px before inference?**

DNN inference time scales with input resolution. A 4000px image fed directly to the model wastes compute — faces are detectable at much lower resolutions. Pre-downsampling to 600px max reduces inference time significantly with negligible impact on detection accuracy.

**Why `ThreadLocal<Net>` for the OpenCV model?**

`cv::dnn::Net` is not thread-safe. The worker runs multiple threads simultaneously. `ThreadLocal` gives each thread its own model instance, avoiding synchronization and preventing race conditions in native code.

### Step-Down Scaling

`DiscordImageResizer` resizes large images by repeatedly halving dimensions until the image is close to the target size, then applying a final bicubic scale.

**Why step-down scaling instead of a single bicubic scale?**

Direct downscaling from a very large image to a very small target loses significant detail because too many pixels are averaged in one step. Step-down scaling preserves more intermediate detail at each halving step, producing a sharper result. This is the same technique used by image editing software for high-quality thumbnail generation.

### Animated GIF Handling

`AnimatedGifProcessor` detects animated GIFs by their magic header bytes (GIF87a / GIF89a), decomposes them frame by frame, applies the smart crop to every frame, and re-encodes with the Netscape 2.0 loop extension preserved.

**Why composite frames onto a canvas instead of treating each frame independently?**

Many animated GIFs use delta encoding: frames contain only the pixels that changed since the previous frame, not full images. Processing each frame independently would produce frames with transparent or missing pixels. Compositing onto a running canvas (with disposal method handling) reconstructs the full image at each frame before cropping.

**Why preserve the Netscape loop extension?**

Without it, the output GIF plays once and stops. Discord users expect animated avatars to loop continuously.

---

## Observability

The stack includes three observability layers:

**Metrics (Micrometer → Prometheus → Grafana):** The worker exposes `jobs.active` (a gauge showing in-flight jobs), `jobs.retried` (a counter), and `jobs.processing.duration` (a timer). Prometheus scrapes both services every 15 seconds. Grafana provides dashboards. This stack is open-source and runs in Docker Compose with no external dependencies.

**Distributed tracing (Micrometer Tracing → Jaeger via OTLP):** Each job gets a trace that spans the API submission and the worker processing, connected by a trace ID. When a job fails or is slow, you can find the trace in Jaeger and see exactly where time was spent — download, DNN inference, S3 write — without adding log statements.

**Structured logging (MDC):** `JobProcessor` adds `jobId` and `attempt` to the MDC (Mapped Diagnostic Context) for every log line during job processing. This means every log line for a given job includes its ID, making log correlation trivial without distributed tracing.

**Why all three instead of just logging?**

Logs answer "what happened." Metrics answer "how often and how fast." Traces answer "where did the time go." They are complementary, not redundant. For a job processing system where a job might be slow for many reasons (network, CPU, S3 latency), traces are particularly valuable because they make per-job timing breakdown visible without instrumenting every code path manually.

---

## Frontend

The frontend is a React + Vite single-page app. In production it is built into `frontend/dist/` by a Node 20 Docker build stage and injected into `api/src/main/resources/static/`, so it is served directly by the Spring Boot API with no separate web server.

The UI polls `GET /api/jobs/{jobId}` every 2 seconds. On `COMPLETED`, it fetches `GET /api/jobs/{jobId}/result` to download the image. The API key is stored in `localStorage`.

**Why polling instead of WebSockets or Server-Sent Events?**

Polling is the simplest correct implementation. A typical job completes in 2–5 seconds. The polling interval of 2 seconds means the user waits at most 2 extra seconds beyond job completion, which is acceptable UX for a batch operation. WebSockets add server-side connection state, reconnection handling, and infrastructure complexity (proxies, load balancers) for marginal UX improvement. The result endpoint is also one-shot (it deletes the result after serving it), which fits a pull model naturally.

**Why serve the frontend from the Spring Boot API jar?**

One fewer service to deploy, monitor, and configure. The alternative — a separate Nginx container to serve static files — adds no capability for a single-page app that consumes an API on the same origin. Embedding the static files in the jar means a single Docker image hosts both API and UI.

---

## Local Development Stack (Docker Compose)

| Service | Purpose |
|---------|---------|
| `redis` | Job queue and state store |
| `localstack` | S3-compatible object store (free, no AWS account needed) |
| `api` | Spring Boot API |
| `worker` | Spring Boot worker |
| `prometheus` | Scrapes `/actuator/prometheus` from both services |
| `grafana` | Pre-provisioned dashboards connected to Prometheus |
| `jaeger` | Receives OTLP traces; UI at localhost:16686 |

**Why LocalStack instead of MinIO for local S3?**

LocalStack implements the S3 API with enough fidelity for this use case (PutObject, GetObject, DeleteObject, lifecycle configuration). MinIO is a full S3-compatible object store that requires additional configuration. LocalStack starts in seconds and requires no credentials beyond placeholders. The pin at version `3.8` avoids newer LocalStack versions that require a paid license for some features.
