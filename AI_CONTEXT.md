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
- Background job queue
- Worker thread pool
- Image downloading
- Image resizing
- Job status management

## cli
Original command-line testing interface.

Responsibilities:
- Manual local testing
- Batch image processing tests
- Verifying processing behavior without the API

---

# Current Features

- Asynchronous background job processing
- REST API for job submission and tracking
- Parallel image processing using Java thread pools
- Multi-step high-quality image resizing
- Center-cropping for square Discord-style avatars
- Dockerized Spring Boot API
- Gradle multi-module architecture
- Health checks using Spring Boot Actuator

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

# Planned Smart Cropping Features

## Automatic Subject Detection

Planned functionality:
- detect faces or primary subjects automatically
- crop around the detected subject
- preserve avatar framing quality

Preferred implementation:
- OpenCV Java bindings
- lightweight face detection initially
- architecture should support future saliency/object detection

---

## Manual Crop Override

Future frontend/API support should allow users to:
- manually define crop regions
- override automatic crop behavior
- preview crop window before processing

Expected future API format:

```json
{
  "imageUrl": "...",
  "cropMode": "AUTO"
}
```

or

```json
{
  "imageUrl": "...",
  "cropMode": "MANUAL",
  "crop": {
    "x": 100,
    "y": 50,
    "width": 300,
    "height": 300
  }
}
```

---

# Desired Future Pipeline

Upload
 ->
Smart Crop Detection
 ->
Optional Manual Crop Override
 ->
Resize Pipeline
 ->
Storage

---

# Important Constraints

Smart cropping should:
- integrate into the existing processing pipeline
- preserve current asynchronous job architecture
- remain modular inside the core module
- avoid overengineering initially

Preferred approach:
1. face detection first
2. later generalized subject detection
3. later optional frontend crop UI