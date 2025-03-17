# Spring Kotlin Batch RediSearch

A Spring Boot application written in Kotlin that processes traffic density data using Spring Batch and stores it in Redis. The application processes CSV files containing traffic density information in batches and provides search capabilities using Redis Search.

## Technologies Used

- Kotlin 1.9.25
- Spring Boot 3.4.3
- Spring Batch
- Spring Data Redis
- Redis Stack (with Redis Search)
- Spring Data JPA
- PostgreSQL (for Spring Batch metadata)
- Gradle
- Java 21

## Prerequisites

- JDK 21
- Redis Stack (or Redis with RediSearch module)
- PostgreSQL database (for Spring Batch metadata)
- Gradle (wrapper included)
- Docker and Docker Compose (optional, for running Redis)

## Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/senocak/Spring-Kotlin-Batch-Redis.git
   cd Spring-Kotlin-Batch-Redis
   ```

2. Start Redis using Docker Compose:
   ```bash
   docker-compose up -d redis-stack-single
   ```

   Alternatively, to run a Redis cluster:
   ```bash
   docker-compose up -d
   ```

3. Configure the database:
   - Create a PostgreSQL database for Spring Batch metadata
   - Update the database configuration in `application.yml` if needed (default values provided)

4. Build the project:
   ```bash
   ./gradlew build
   ```

## Configuration

The application can be configured through `application.yml` or environment variables:

```yaml
# PostgreSQL Configuration (for Spring Batch metadata)
SERVER_IP: localhost (default)
POSTGRESQL_PORT: 54321 (default)
POSTGRESQL_DB: batch (default)
POSTGRESQL_SCHEMA: public (default)
POSTGRESQL_USER: postgres (default)
POSTGRESQL_PASSWORD: senocak (default)

# Redis Configuration
REDIS_HOST: localhost (default)
REDIS_PORT: 6382 (default)
REDIS_PASSWORD: (empty by default)
REDIS_TIMEOUT: 300 (default)
```

Additional configuration options:
- Server port: 8099
- Hikari connection pool settings
- JPA/Hibernate configuration
- Spring Batch settings
- Redis cluster configuration (commented out by default)

## Usage

### API Endpoints

1. Run Batch Job:
```http
POST /api/batch/traffic-density/run
```
Parameters:
- `csvName`: Name of the CSV file to process

2. Get Redis Data Information:
```http
GET /api/batch/traffic-density
```
Returns information about the Redis data store, including keys, data types, and statistics.

3. Search Traffic Density Data:
```http
GET /api/batch/traffic-density/search
```
Parameters:
- `latitude` (optional): Filter by latitude
- `longitude` (optional): Filter by longitude
- `limit` (optional, default: 10): Number of results to return
- `offset` (optional, default: 0): Offset for pagination

### Example Usage

1. Run the batch job:
```bash
curl -X POST "http://localhost:8099/api/batch/traffic-density/run?csvName=./traffic_density_202412.csv"
```

2. Get Redis data information:
```bash
curl -X GET "http://localhost:8099/api/batch/traffic-density"
```

3. Search traffic density data:
```bash
curl -X GET "http://localhost:8099/api/batch/traffic-density/search?latitude=41&longitude=28&limit=100&offset=0"
```

## Features

- Batch processing of traffic density data
- Redis storage with efficient data structures
- Redis Search for fast querying of traffic density data
- Secondary indexes for latitude and longitude
- RESTful API endpoints
- Configurable Redis and PostgreSQL connections
- Support for both standalone Redis and Redis Cluster
- Robust error handling
- Performance metrics for search operations

## Reference Documentation

For further reference, please consider the following sections:

* [Official Gradle documentation](https://docs.gradle.org)
* [Spring Boot Gradle Plugin Reference Guide](https://docs.spring.io/spring-boot/3.4.3/gradle-plugin)
* [Spring Batch](https://docs.spring.io/spring-boot/3.4.3/how-to/batch.html)
* [Spring Data Redis](https://docs.spring.io/spring-boot/3.4.3/reference/data/nosql.html#data.nosql.redis)
* [Redis Stack Documentation](https://redis.io/docs/stack/)
* [Redis Search Documentation](https://redis.io/docs/stack/search/)
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
