<a href="https://fireflysoftware.io/openplatform/">
<picture>
  <source media="(max-width: 640px) and (prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-dark.svg">
  <source media="(max-width: 640px)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-narrow-light.svg">
  <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-dark.svg">
  <img src="https://raw.githubusercontent.com/firefly-oss/.github/main/profile/assets/banner-light.svg" alt="Firefly OpenPlatform - Core Common - General Ledger" width="100%">
</picture>
</a>

# Firefly OpenPlatform - Core Common - General Ledger

Developed by the Firefly Software Foundation. [dev@fireflysoftware.io](mailto:dev@fireflysoftware.io)

[Website](https://fireflysoftware.io/) · [OpenPlatform](https://fireflysoftware.io/openplatform/) · [Module catalogue](https://fireflysoftware.io/openplatform/modules/)

A microservice for General Ledger (GL) accounting management within the Firefly platform. This service provides a comprehensive API for managing GL accounts, journal entries, journal batches, and journal lines.

## Overview

The Common Platform Accounting GL microservice is designed to handle all general ledger accounting operations for the Firefly platform. It provides a reactive API built with Spring WebFlux and uses PostgreSQL with R2DBC for data storage and retrieval.

### Key Features

- GL Account management with hierarchical structure support
- Journal Batch processing
- Journal Entry creation and management
- Journal Line recording
- Reactive programming model for high throughput and scalability
- OpenAPI/Swagger documentation
- Health monitoring endpoints

## Architecture

This project follows a modular architecture with clear separation of concerns:

### Modules

- **core-common-accounting-gl-interfaces**: Contains DTOs and interfaces that define the API contract
- **core-common-accounting-gl-models**: Contains database entities and repositories
- **core-common-accounting-gl-core**: Contains business logic and services
- **core-common-accounting-gl-web**: Contains REST controllers and web configuration

### Technology Stack

- **Java 25**: Utilizing the latest Java features including virtual threads
- **Spring Boot**: Framework for building microservices
- **Spring WebFlux**: Reactive web framework
- **R2DBC**: Reactive Relational Database Connectivity for PostgreSQL
- **Flyway**: Database migration tool
- **OpenAPI/Swagger**: API documentation
- **Maven**: Build and dependency management
- **PostgreSQL**: Relational database

## Setup and Installation

### Prerequisites

- Java 25 or higher
- Maven 3.8 or higher
- PostgreSQL 14 or higher
- Docker (optional, for containerized deployment)

### Environment Variables

The following environment variables need to be set:

```
DB_HOST=localhost
DB_PORT=5432
DB_NAME=accounting_gl
DB_USERNAME=postgres
DB_PASSWORD=postgres
DB_SSL_MODE=disable
```

### Building the Application

```bash
mvn clean install
```

### Running the Application

```bash
mvn spring-boot:run -pl core-common-accounting-gl-web
```

### Running with Docker

First, build the application JAR file:

```bash
mvn clean package -DskipTests
# Copy the JAR to the root directory for Docker to find it
cp core-common-accounting-gl-web/target/core-common-accounting-gl-web-1.0.0-SNAPSHOT.jar core-common-accounting-gl.jar
```

Then build and run the Docker container:

```bash
# Build the Docker image
docker build -t core-common-accounting-gl .

# Run the container
docker run -p 8080:8080 \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=accounting_gl \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=postgres \
  -e DB_SSL_MODE=disable \
  core-common-accounting-gl
```

### Main API Endpoints

- **GL Accounts**: `/api/v1/gl-accounts`
  - Create, read, update, delete GL accounts
  - Manage hierarchical account structures

- **Journal Batches**: `/api/v1/journal-batches`
  - Create, read, update, delete journal batches

- **Journal Entries**: `/api/v1/journal-entries`
  - Create, read, update, delete journal entries

- **Journal Lines**: `/api/v1/journal-lines`
  - Create, read, update, delete journal lines

## Development Guidelines

### Code Style

This project follows standard Java code style conventions. Use the provided code formatter to ensure consistency.

### Testing

Write unit tests for all new functionality. Integration tests should be added for critical paths.

```bash
# Run tests
mvn test

# Run tests with coverage
mvn test jacoco:report
```

### Database Migrations

Database schema changes should be made through Flyway migrations. Add new migration scripts in:
`core-common-accounting-gl-models/src/main/resources/db/migration`

### Branching Strategy

- `main`: Production-ready code
- `develop`: Integration branch for features
- `feature/*`: Feature branches
- `bugfix/*`: Bug fix branches
- `release/*`: Release preparation branches

## Monitoring

The application exposes the following monitoring endpoints:

- Health: `/actuator/health`
- Info: `/actuator/info`
- Prometheus metrics: `/actuator/prometheus`

## Profiles

The application supports multiple Spring profiles:

- `dev`: Development environment with detailed logging
- `testing`: Testing environment
- `prod`: Production environment with minimal logging and disabled Swagger UI
