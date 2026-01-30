# Background-Job-System

This is a scalable backend system designed to process submitted jobs asynchronously.
Returned results from processed jobs are then stored in AWS.
The application is Docker-ized and Deployed on to an AWS ECS.

--------------------------------------

The Planned Tech Stack is as follows per layer:

Backend API -> Java + spring Boot

Asynchronous Queue -> AWS SQS

Worker Service -> Java + Spring Boot

Storage -> AWS S3

Containerization -> Docker

Deployment -> AWS ECS (API + Worker)

CI/CD -> Github Actions -> ECR -> ECS

Monitoring/Logging -> Cloudwatch



