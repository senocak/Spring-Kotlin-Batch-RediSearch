# RediSearch

A Spring Boot application using Kotlin that performs dynamic search on large datasets using RediSearch for efficient querying.

## Overview

This application demonstrates how to use RediSearch with Spring Boot and Kotlin to implement efficient search capabilities for large datasets. It provides a RESTful API for managing products and performing various types of searches.

## Features

- Full CRUD operations for products
- Simple search operations (by name, category, price range, tag)
- Advanced search with multiple criteria
- Integration with Redis and RediSearch for efficient querying

## Prerequisites

- JDK 21
- Gradle
- Redis with RediSearch module

## Getting Started

### Setting up Redis with RediSearch

The easiest way to get started with Redis and RediSearch is to use Docker:

```bash
docker run -p 6379:6379 redis/redis-stack
```

This will start a Redis instance with the RediSearch module enabled.

### Running the Application

```bash
./gradlew bootRun
```

The application will start on port 8080.

## API Endpoints

### Product Management

- `POST /api/products` - Create a new product
- `GET /api/products/{id}` - Get a product by ID
- `GET /api/products` - Get all products
- `PUT /api/products/{id}` - Update a product
- `DELETE /api/products/{id}` - Delete a product

### Search Operations

- `GET /api/products/search/name?name={name}` - Search products by name
- `GET /api/products/search/category?category={category}` - Search products by category
- `GET /api/products/search/price?minPrice={minPrice}&maxPrice={maxPrice}` - Search products by price range
- `GET /api/products/search/tag?tag={tag}` - Search products by tag
- `GET /api/products/search` - Advanced search with multiple criteria
  - Optional parameters: `name`, `category`, `minPrice`, `maxPrice`, `tag`

## Example Product JSON

```json
{
  "id": "1",
  "name": "Laptop",
  "description": "High-performance laptop",
  "category": "Electronics",
  "price": 1200.00,
  "tags": ["computer", "tech", "portable"],
  "attributes": {
    "brand": "TechBrand",
    "color": "Silver"
  }
}
```

## How It Works

The application uses Redis OM Spring to integrate with RediSearch. The key components are:

1. **Product Model**: Defines the structure of the product data with appropriate annotations for RediSearch indexing.
2. **ProductRepository**: Provides methods for CRUD operations and basic search queries.
3. **ProductService**: Implements business logic and advanced search capabilities.
4. **ProductController**: Exposes RESTful endpoints for interacting with the application.

## Advanced Search

The advanced search endpoint allows you to search for products using multiple criteria:

```
GET /api/products/search?name=laptop&category=Electronics&minPrice=1000&maxPrice=2000&tag=portable
```

## Discussion

Redis'in kendi içinde bir secondary index özelliği yoktur. @Indexed gerçek Redis index değildir.

Spring Data Redis'te`@Indexed` anotasyonu Redis üzerinde gerçek anlamda bir "index" oluşturmaz (yani veri tabanlarındaki gibi B-tree, hash index vs. değil).

@Indexed anotasyonu bir alanı arama yapılabilir (queryable) hale getirmek için kullanılır.

Redis Set'leri (SET) kullanır ve her benzersiz değer için bir set key oluşturur.

@Indexed ile işaretlenmiş alanlar için, o alanın her farklı değeri için bir Redis Seti oluşturur.

Bu sayede findByLatitude(...) gibi metotlarla sorgulama yapabilir.

SET’ler bellekte yer kaplar. 
