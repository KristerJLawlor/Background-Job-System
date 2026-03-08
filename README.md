## -Background-Job-System-

A scalable backend system designed to process submitted jobs asynchronously.

The job system currently downloads and resizes images to small avatar-style formats.
Processed images are stored locally and can be uploaded to AWS storage in production.

The application is Dockerized and designed to be deployed to AWS ECS.

---------------------------------------

## -Features-

Parallel image processing using Java thread pools

Asynchronous background job execution

REST API for job submission and status tracking

Multi-step high-quality image resizing

Modular monorepo structure (CLI, Core, API)

Gradle multi-module project structure

Dockerized service for cloud deployment

Health check endpoint for container orchestration

--------------------------------------

## -Architecture-

The system is organized into three modules:

-core
Contains the background job system and image processing logic.

-cli
A command-line interface used to manually test the system before the API was introduced.

-api
A Spring Boot application that exposes REST endpoints to submit and monitor jobs.

Job Flow:

The API immediately returns a Job ID while processing occurs asynchronously.


Client
  │
  ▼
Spring Boot API
  │
  ▼
Job Queue
  │
  ▼
Worker Thread Pool
  │
  ▼
Image Processor
  │
  ▼
avatars/

--------------------------------------

## -Project Structure-

Background-Job-System

core/
Contains image processing and job worker logic.

cli/
Command-line interface for testing background jobs.

api/
Spring Boot REST API used to submit and track jobs.

avatars/
Output directory for processed images.

Dockerfile
build.gradle
settings.gradle

--------------------------------------

## -Setup-

  ## -Prerequisites-
  To run locally, you need Docker and Git. 
  If you run without Docker, you will only need Java 21 and Gradle.

  ## -Steps-
  1)Clone the repository

  2)git clone https://github.com/yourusername/background-job-system.git

  3)Build the Docker Image:

    docker build -t avatar-api .

  4)Run the container:

    docker run -p 8080:8080 avatar-api

  5)The API will start at:

    http://localhost:8080

--------------------------------------

## -API Usage-

  ## Submitting a Job: 
  
    POST /api/jobs

  ## Example Request:

    curl -X POST http://localhost:8080/api/jobs \
    -H "Content-Type: application/json" \
    -d '{"imageUrl":"https://picsum.photos/300"}'

  ## Example Response:

    {
    "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2"
    }

  ## Checking Job Status:

    GET /api/jobs/{jobId}

    Possible statuses:

    PENDING
    RUNNING
    COMPLETED
    FAILED

  ## Example request:

    curl http://localhost:8080/api/jobs/{jobId}

  ## Example response:

    {
      "jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2",
      "status": "COMPLETED",
      "outputFile": "avatars/avatar.png"
    }

--------------------------------------
## -Running without Docker-

  Build the project:

  ./gradlew build

  Run the API locally:

  ./gradlew :api:bootRun

  The server will start on:

  http://localhost:8080

--------------------------------------

## Running the CLI Tool (Optional)

The CLI module allows manual testing of the background job system without the API.

./gradlew :cli:run

--------------------------------------

## Health Check Endpoint

  The service exposes a health endpoint used by container orchestration systems like AWS ECS.

  GET /actuator/health

  Example:

  curl http://localhost:8080/actuator/health

  Response:

  {
    "status": "UP"
  }

  --------------------------------------

## Environment Configuration

  The application supports configuration via environment variables.

  Variable	Default	Description
  PORT	8080	Server port
  AVATAR_DIR	/app/avatars	Output directory for processed images

  Example:

  docker run -p 8080:8080 \
  -e AVATAR_DIR=/data/avatars \
  avatar-api