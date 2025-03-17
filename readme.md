# Spring Kotlin Batch

A Spring Boot application written in Kotlin that processes traffic density data using Spring Batch. The application downloads CSV files containing traffic density information and processes them in batches.

## Technologies Used

- Kotlin 1.9.25
- Spring Boot 3.4.3
- Spring Batch
- Spring Data JPA
- PostgreSQL
- Gradle
- Java 21

## Prerequisites

- JDK 21
- PostgreSQL database
- Gradle (wrapper included)

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/senocak/Spring-Kotlin-Batch.git
   cd Spring-Kotlin-Batch
   ```

2. Configure the database:
   - Create a PostgreSQL database
   - Update the database configuration in `application.yml` if needed (default values provided)

3. Build the project:
   ```bash
   ./gradlew build
   ```

## Configuration

The application can be configured through `application.yml` or environment variables:

```yaml
SERVER_IP: localhost (default)
POSTGRESQL_PORT: 54321 (default)
POSTGRESQL_DB: batch (default)
POSTGRESQL_SCHEMA: public (default)
POSTGRESQL_USER: postgres (default)
POSTGRESQL_PASSWORD: senocak (default)
```

Additional configuration options:
- Server port: 8089
- Hikari connection pool settings
- JPA/Hibernate configuration
- Spring Batch settings

## Usage

### API Endpoints

1. Download Traffic Density Data:
```http
POST /api/batch/traffic-density/download
```
Parameters:
- `url` (optional): URL of the CSV file to download
  - Default: IBB traffic density data

2. Run Batch Job:
```http
POST /api/batch/traffic-density/run
```
Parameters:
- `csvName`: Name of the CSV file to process

3. Get All Job Executions:
```http
GET /api/batch/traffic-density/jobs
```
Returns a list of all job executions with their details.

4. Get Running Job Executions:
```http
GET /api/batch/traffic-density/jobs/running
```
Returns a list of currently running job executions.

5. Stop Job Execution:
```http
POST /api/batch/traffic-density/jobs/{executionId}/stop
```
Parameters:
- `executionId`: ID of the job execution to stop

### Example Usage

1. Download traffic density data:
```bash
curl -X POST "http://localhost:8089/api/batch/traffic-density/download"
```

2. Run the batch job:
```bash
curl -X POST "http://localhost:8089/api/batch/traffic-density/run?csvName=traffic_density_2024.02.20.10.30.00.csv"
```

3. Get all job executions:
```bash
curl -X GET "http://localhost:8089/api/batch/traffic-density/jobs"
```

4. Get running job executions:
```bash
curl -X GET "http://localhost:8089/api/batch/traffic-density/jobs/running"
```

5. Stop a job execution:
```bash
curl -X POST "http://localhost:8089/api/batch/traffic-density/jobs/1/stop"
```

## Features

- Asynchronous file download with progress tracking
- Batch processing of traffic density data
- Database storage of processed data
- RESTful API endpoints
- Configurable database connection
- Robust error handling
- Comprehensive job management:
  - Job execution monitoring
  - Running jobs tracking
  - Job execution control (stop/restart)
  - Detailed job execution history

## Reference Documentation

For further reference, please consider the following sections:

* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/3.4.3/gradle-plugin)
* [Spring Batch](https://docs.spring.io/spring-boot/3.4.3/how-to/batch.html)
* [Spring Data JPA](https://docs.spring.io/spring-boot/3.4.3/reference/data/sql.html#data.sql.jpa-and-spring-data)
* [Spring Web](https://docs.spring.io/spring-boot/3.4.3/reference/web/servlet.html)

## Contributing

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

This project is licensed under the MIT License - see the LICENSE file for details.
