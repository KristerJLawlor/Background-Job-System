# AI_CONTEXT.md

# Background Job System

## Project Overview

This project is a Java backend system for asynchronous image processing.

The system accepts image-processing jobs through a REST API, processes them asynchronously using worker threads, resizes images to Discord avatar dimensions, and stores the processed output locally.

The project is structured as a Gradle multi-module monorepo and is containerized with Docker.

---

# Architecture

The project currently contains three modules:

## api
Spring Boot REST API.

Responsibilities:
- Accept job submissions
- Return job IDs immediately
- Expose endpoints for checking job status
- Coordinate background processing

## core
Contains the core business logic.

Responsibilities:
- Image downloading
- Image resizing (multi-step, bicubic)
- Smart cropping: OpenCV DNN SSD ResNet face detection; falls back to center crop
- Animated GIF processing (frame-by-frame crop + resize)

## cli
Original command-line testing interface.

Responsibilities:
- Manual local testing
- Batch image processing tests
- Verifying processing behavior without the API

---

# Current Features

- Asynchronous background job processing via Redis queue
- REST API for job submission, status polling, and result download
- File upload (multipart) and URL submission
- OpenCV DNN SSD ResNet face detection with smart crop; center-crop fallback
- Animated GIF support: frame-by-frame crop + resize, timing preserved
- Exponential backoff retries (3 attempts) + dead letter queue
- S3 result storage (LocalStack locally, real S3 in prod)
- Prometheus metrics, Grafana dashboards, OTLP/Jaeger distributed tracing
- React + Vite frontend served from the API jar
- Docker Compose full stack (api, worker, redis, localstack, prometheus, grafana, jaeger)

---

# Current Job Flow

Client
  ->
Spring Boot API
  ->
Job Queue
  ->
Worker Thread Pool
  ->
Image Processing
  ->
avatars/ output directory

---

## Run Commands

Build:
./gradlew build

Run API:
./gradlew :api:bootRun

Run CLI:
./gradlew :cli:run

Docker:
docker build -t avatar-api .
docker run -p 8080:8080 avatar-api

---

# Technology Stack

## Languages
- Java 21

## Frameworks
- Spring Boot 3

## Build Tools
- Gradle

## Infrastructure
- Docker

## APIs
- REST API

---

# Current API Endpoints

## Submit Job

POST /api/jobs

Request Body:

```json
{
  "imageUrl": "https://picsum.photos/300"
}

---

## Important Constraints

Do NOT:
- replace Gradle with Maven
- collapse modules into one project
- remove Docker support
- remove asynchronous processing

Keep the current architecture modular.

---

## Planned Improvements

Next goals:
- Redis-backed distributed queue
- worker service
- Docker Compose
- AWS S3 integration
- retry handling
- implement Smart Cropping features

---

# Image Processing Roadmap

The image pipeline currently performs:
- image download
- center crop
- resize to Discord avatar dimensions

Future improvements should add intelligent cropping support.

---

# Smart Cropping (Implemented)

`SmartCropper` in the `core` module uses the OpenCV DNN module with the
`res10_300x300_ssd_iter_140000` Caffe SSD ResNet model (bundled in the JAR under
`core/src/main/resources/dnn/`). It handles frontal, angled, and partially-obscured
faces that the older Haar cascade missed. Confidence threshold is 0.1 to accommodate
lower scores from distant or non-frontal subjects. Falls back to a center crop when
no face is detected above the threshold.

## Possible future improvements

- Manual crop override (API + frontend UI)
- Per-user API keys and job ownership

# Additional Architectural Notes

## Dockerization

The API module is containerized using Docker multi-stage builds.

The service:
- runs on Java 21
- exposes port 8080
- is intended for AWS ECS deployment

---

## Current Storage Model

Processed avatars are:
- stored temporarily in memory
- also written to the local avatars/ directory

Future plans will replace local storage with AWS S3.

---

## Future Image Processing Improvements

Planned enhancements:
- OpenCV-based face detection
- smart subject-aware cropping
- optional manual crop override support

---

## Long-Term Architecture Direction

The project is evolving toward:

Client
 ->
API Service
 ->
Redis Queue
 ->
Worker Services
 ->
S3 Storage

The current architecture should gradually evolve toward distributed processing while preserving the modular design.