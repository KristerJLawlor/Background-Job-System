# Background-Job-System

This is a scalable backend system designed to process submitted jobs asynchronously.
The job system in its current state downloads and resizes images for small avatar-style size requirements.
Returned results from processed jobs are then stored in AWS.
The application is Docker-ized and Deployed on to an AWS ECS.

---------------------------------------

~Features~

• Parallel image processing using Java thread pools
• Asynchronous background job execution
• REST API for job submission and status tracking
• Multi-step high-quality image resizing
• Modular monorepo structure (CLI, Core, API)
• Gradle multi-module project structure

--------------------------------------

~Architecture~

The system is organized into three modules:

-core
Contains the background job system and image processing logic.

-cli
A command-line interface used to manually test the system before the API was introduced.

-api
A Spring Boot application that exposes REST endpoints to submit and monitor jobs.

Job Flow:

Client → API → Job Queue → Worker Thread → Image Processing → File Output

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

~Project Structure~

Background-Job-System

core/
Contains image processing and job worker logic.

cli/
Command-line interface for testing background jobs.

api/
Spring Boot REST API used to submit and track jobs.

avatars/
Output directory for processed images.

build.gradle
settings.gradle

--------------------------------------

~Setup~

Clone the repository

git clone https://github.com/yourusername/background-job-system.git

Build the project

./gradlew build

Run the API

./gradlew :api

The server will start on:

http://localhost:8080

--------------------------------------

~API Usage~

Submitting a Job:

POST /api/jobs

Example Request Body:

{
"imageUrl": "https://picsum.photos/300"
}

Example Response:

{
"jobId": "ee0d7b58-363e-4017-a48c-960bc09967f2"
}

Checking Job Status:

GET /api/jobs/{jobId}

Possible statuses

PENDING
RUNNING
COMPLETED
FAILED