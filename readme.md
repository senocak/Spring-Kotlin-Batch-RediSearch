# Spring-Kotlin-Batch-RediSearch-Redisson-Jedis

A Spring Boot application using Kotlin that demonstrates efficient search capabilities on traffic density data using Redis Stack with RediSearch module and Redisson client.

## Overview

This application showcases how to use RediSearch with Spring Boot, Kotlin, and Redisson to implement efficient search capabilities for traffic density data. It provides a RESTful API for importing, searching, and comparing performance of different Redis clients.

## Features

- Import traffic density data from CSV files using Spring Batch
- Search traffic density data with various filters:
  - Location-based search (latitude, longitude, radius)
  - Speed range (minimum, maximum)
  - Number of vehicles
- Compare performance between different Redis clients:
  - Redisson
  - Jedis
  - Lettuce
- RediSearch indexing for efficient querying

## Prerequisites

- JDK 21
- Gradle
- Docker and Docker Compose

## Getting Started

### Setting up Redis with RediSearch

The easiest way to get started with Redis and RediSearch is to use the provided Docker Compose file:

```bash
docker-compose up -d
```

This will start:
- Redis Stack Server (with RediSearch module) on port 6382
- RedisInsight (Redis GUI) on port 5540

### Running the Application

```bash
./gradlew bootRun
```

The application will start on port 8091.

### Importing Data

To import traffic density data from the provided CSV file:

```bash
curl -X POST "http://localhost:8091/api/run?csvName=./traffic_density_202412.csv"
```

## API Endpoints

### Data Import

- `POST /api/run?csvName={csvFilePath}` - Run batch job to import data from CSV file

### Search Operations

- `GET /api/redisearch` - Search traffic density data with various filters
  - Optional parameters:
    - `latitude` - Latitude coordinate
    - `longitude` - Longitude coordinate
    - `radius` - Search radius (default: 10)
    - `minSpeed` - Minimum speed
    - `maxSpeed` - Maximum speed
    - `numberOfVehicles` - Number of vehicles
    - `limit` - Maximum number of results (default: 10)
    - `offset` - Pagination offset (default: 0)
    - `type` - Redis client type to use (default: "lettuce", options: "jedis", "lettuce")

### Performance Comparison

- `GET /api` - Compare performance between different Redis clients

## Example API Requests

### Import Data
```bash
curl -X POST "http://localhost:8091/api/run?csvName=./traffic_density_202412.csv"
```

### Basic Search
```bash
curl "http://localhost:8091/api/redisearch"
```

### Search by Location
```bash
curl "http://localhost:8091/api/redisearch?latitude=41.076237426965875&longitude=28.887702226638797&radius=1"
```

### Search by Speed Range
```bash
curl "http://localhost:8091/api/redisearch?minSpeed=99&maxSpeed=100&limit=10"
```

### Search by Number of Vehicles
```bash
curl "http://localhost:8091/api/redisearch?numberOfVehicles=28&limit=5"
```

### Compare Redis Clients Performance
```bash
curl "http://localhost:8091/api"
```

## How It Works

The application uses Redis Stack with RediSearch module and multiple Redis clients to demonstrate different approaches to Redis integration. The key components are:

1. **TrafficDensity Model**: Defines the structure of the traffic density data with appropriate annotations for Redis storage and indexing.
2. **BatchConfig**: Configures Spring Batch job for importing data from CSV files into Redis.
3. **RedisConfig**: Configures Redis connections and clients (Redisson, Jedis, Spring Data Redis).
4. **ProductController**: Exposes RESTful endpoints for searching and comparing Redis clients.

## Project Structure

- `src/main/kotlin/com/github/senocak/redisearch/`
  - `RediSearchApplication.kt` - Main application class
  - `config/` - Configuration classes
    - `BatchConfig.kt` - Spring Batch configuration
    - `RedisConfig.kt` - Redis configuration
  - `controller/` - REST controllers
    - `ProductController.kt` - Controller for search operations
  - `model/` - Data models
    - `TrafficDensity.kt` - Traffic density data model and repository

## Dependencies

- Spring Boot 3.4.3
- Spring Batch
- Kotlin 1.9.25
- Redis Clients:
  - Redisson 3.45.1
  - Jedis 5.2.0
  - Lettuce (via Spring Data Redis)
- H2 Database (for Spring Batch metadata)

## Notes

The application demonstrates different approaches to Redis integration and search capabilities. It's designed to showcase performance differences between different Redis clients and the power of RediSearch for efficient querying of large datasets.

## Discussion

Redis does not have a built-in secondary index feature. The @Indexed annotation is not a true Redis index.

In Spring Data Redis, the `@Indexed` annotation does not create a "real" index on Redis (not like B-tree, hash index, etc. in traditional databases).

The @Indexed annotation is used to make a field queryable.

It uses Redis Sets (SET) and creates a set key for each unique value.

For fields marked with @Indexed, it creates a Redis Set for each different value of that field.

This allows querying using methods like findByLatitude(...).

These SETs consume memory space.
