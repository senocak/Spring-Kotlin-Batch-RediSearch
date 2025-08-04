# 🎯 Building High-Performance Search with Redis: A Deep Dive into RediSearch with Spring Boot and Kotlin

## Introduction
In today's data-driven world, the ability to search through large datasets quickly and efficiently is crucial for any application. Traditional SQL databases, while powerful, can become bottlenecks when dealing with complex search queries on massive datasets. This is where **RediSearch** comes into play—a full-text search engine built on top of Redis that provides lightning-fast search capabilities.

In this article, we'll explore how to build a high-performance search system using **RediSearch** with **Spring Boot** and **Kotlin**. We'll also compare two popular Redis Java clients: **Jedis** and **Redisson**, examining their strengths and weaknesses in the context of search operations.

## What is RediSearch?
RediSearch is a Redis module that adds full-text search capabilities to Redis. It provides:

- **Full-text search** with advanced query syntax
- **Geo-spatial search** for location-based queries
- **Numeric range filtering**
- **Tag-based filtering**
- **Auto-complete and suggestion features**
- **Secondary indexing** for fast data retrieval

Unlike traditional search engines like Elasticsearch, RediSearch operates entirely in memory, making it incredibly fast for real-time search operations.

## 🛠️ Project Overview
Our project demonstrates RediSearch capabilities using **Istanbul's traffic density data**. The application provides geo-spatial search functionality, allowing users to:

- Search for traffic data within a specific radius of coordinates
- Filter by speed ranges (minimum, maximum, average)
- Filter by number of vehicles
- Compare performance between different Redis clients

The large dataset is retrieved from a CSV file containing traffic density information and downloaded from <a href="https://data.ibb.gov.tr/dataset/3ee6d744-5da2-40c8-9cd6-0e3e41f1928f/resource/bedd5ab2-9a00-4966-9921-9672d4478a51/download/traffic_density_202411.csv">İstanbul Büyükşehir Belediyesi</a>, which is then inserted and indexed in Redis for fast search operations.

### 🏗️ Technology Stack
- **Spring Boot 3.5.0** - Application framework
- **Kotlin 1.9.25** - Programming language
- **Redis with RediSearch** - Search engine
- **Jedis 5.2.0** - Low-level Redis client
- **Redisson 3.45.1** - High-level Redis client
- **Lettuce 6.1.8** - Alternative Redis client (Spring Data Redis default)

### PreRequirements
Need to configure your Redis instance with the RediSearch module. If you're using Docker, you can run a Redis Stack server that includes RediSearch:
```yaml
version: '3.8'
services:
  redis-stack-single:
    image: redis/redis-stack-server:7.2.0-v18
    ports:
      - "6382:6379"
    healthcheck:
      test: [ "CMD", "redis-cli", "--raw", "incr", "ping" ]
```

## Data Model
Our traffic density model captures real-time traffic information:

```kotlin
@RedisHash(value = "traffic_density")
data class TrafficDensity(
    @Id val id: UUID? = UUID.randomUUID(),
    val dateTime: String,
    val latitude: String,
    val longitude: String,
    val geohash: String,
    val minimumSpeed: Int,
    val maximumSpeed: Int,
    val averageSpeed: Int,
    val numberOfVehicles: Int,
): Serializable {
    var location: String? = null
}
```
The `@RedisHash` annotation tells Spring Data Redis to store this as a hash in Redis with the prefix `traffic_density:`

## 📊 Configuration and Dependencies
Need to add the necessary dependencies in your `build.gradle.kts` file:
```kotlin
implementation("redis.clients:jedis:5.2.0") // Jedis client for Redis

// Following dependencies are for Redisson
implementation("io.lettuce:lettuce-core:6.1.8.RELEASE")
implementation("org.redisson:redisson-spring-boot-starter:3.45.1")
implementation("org.redisson:redisson:3.45.1")
```
Then, redis configuration should be set to match your Redis instance. You can use either Jedis or Redisson based on your preference.

## Setting Up RediSearch Indexes
### Redisson Implementation
With Redisson, creating a RediSearch index is straightforward and type-safe:
```kotlin
search.createIndex(INDEX_TRAFFIC_DENSITY,
    IndexOptions.defaults().on(IndexType.HASH).prefix(listOf("traffic_density:")),
    FieldIndex.tag("id"),
    FieldIndex.text("_class"),
    FieldIndex.numeric("latitude"),
    FieldIndex.numeric("longitude"),
    FieldIndex.geo("location"),
    FieldIndex.text("geohash"),
    FieldIndex.numeric("numberOfVehicles"),
    FieldIndex.numeric("minimumSpeed"),
    FieldIndex.numeric("averageSpeed"),
    FieldIndex.numeric("maximumSpeed"),
)
```

### Jedis Implementation
Jedis requires more verbose configuration but offers fine-grained control:
```kotlin
val params: FTCreateParams = FTCreateParams.createParams().addPrefix("traffic_density:")
val sc: Schema = Schema()
    .addTagField("id")
    .addTextField("dateTime", 1.0)
    .addTextField("latitude", 1.0)
    .addTextField("longitude", 1.0)
    .addGeoField("location")
    .addTextField("geohash", 1.0)
    .addNumericField("minimumSpeed")
    .addNumericField("maximumSpeed")
    .addNumericField("averageSpeed")
    .addNumericField("numberOfVehicles")
val def: IndexDefinition = IndexDefinition().setPrefixes("traffic_density:")
val opt: redis.clients.jedis.search.IndexOptions = redis.clients.jedis.search.IndexOptions.defaultOptions().setNoStopwords()
rediSearch.ftCreate(INDEX_TRAFFIC_DENSITY, opt.setDefinition(def), sc)
```

## ⚖️Jedis vs Redisson: A Detailed Comparison
### Jedis: The Lightweight Champion
**Pros:**
- **Lightweight**: Minimal overhead and dependencies
- **Direct control**: Low-level access to Redis commands
- **Performance**: Excellent performance for simple operations
- **Mature**: Long-established with extensive community support
- **RediSearch support**: Native support for RediSearch operations

- **Cons:**
- **Verbose**: Requires more boilerplate code
- **Manual resource management**: Need to handle connections manually
- **Limited async support**: Primarily synchronous operations
- **Error handling**: More manual error handling required

### Redisson: The Feature-Rich Alternative
**Pros:**
- **High-level abstractions**: Object-oriented approach to Redis
- **Async/Reactive support**: Built-in async and reactive programming support
- **Connection management**: Automatic connection pooling and management
- **Type safety**: Better type safety with generics
- **Rich features**: Built-in distributed objects, locks, and collections

**Cons:**
- **Heavier**: Larger footprint and more dependencies
- **Learning curve**: More complex API to master
- **Abstraction overhead**: May hide important Redis details
- **Performance overhead**: Additional layers can impact performance

### Search Implementation
Both Jedis and Redisson provide powerful search capabilities with RediSearch. Here's how to implement search functionality in both clients.

```kotlin
@GetMapping("/redisearch")
fun search(
    @RequestParam(required = false) latitude: String? = null,
    @RequestParam(required = false) longitude: String? = null,
    @RequestParam(required = false, defaultValue = "10") radius: Int = 10,
    @RequestParam(required = false) minSpeed: Int? = null,
    @RequestParam(required = false) maxSpeed: Int? = null,
    @RequestParam(required = false) numberOfVehicles: Int? = null,
    @RequestParam(defaultValue = "10") limit: Int = 10,
    @RequestParam(defaultValue = "0") offset: Int = 0,
    @RequestParam(defaultValue = "lettuce") type: String = "lettuce",
): Map<String, Any> {
    val queryBuilder = StringBuilder()
    val conditions: MutableList<String> = mutableListOf()
    if (latitude != null && longitude != null)
        conditions.add(element = "@location:[$longitude $latitude $radius km]") // FT.SEARCH traffic_idx "@location:[28.887702226638797 41.076237426965875 1 km]"
    if (minSpeed != null || maxSpeed != null)
        conditions.add(element = "@averageSpeed:[${minSpeed} ${maxSpeed}]")
    if (numberOfVehicles != null)
        conditions.add(element = "@numberOfVehicles:[${numberOfVehicles} ${numberOfVehicles}]")
    when {
        conditions.isNotEmpty() -> queryBuilder.append(conditions.joinToString(separator = " "))
        else -> queryBuilder.append("*") // If no conditions, default to "*"
    }
    val queryString: String = queryBuilder.toString()
    var searchDuration: Duration
    val totalResults: Long
    val searchResults: List<TrafficDensity>
    searchDuration = measureTime {
        if (type == "jedis") {
            val searchResult: redis.clients.jedis.search.SearchResult = rediSearch.ftSearch(INDEX_TRAFFIC_DENSITY,
                redis.clients.jedis.search.Query(queryBuilder.toString()).limit(offset, limit))
            totalResults = searchResult.totalResults
            searchResults = searchResult.documents.map { doc: redis.clients.jedis.search.Document ->
                val properties: MutableIterable<MutableMap.MutableEntry<String, Any>> = doc.properties
                TrafficDensity(
                    id = UUID.fromString(properties.firstOrNull { it.key == "id" }?.value as? String ?: UUID.randomUUID().toString()),
                    dateTime = doc.get("dateTime") as String,
                    latitude = properties.firstOrNull { it.key == "latitude" }?.value as? String ?: "",
                    longitude = properties.firstOrNull { it.key == "longitude" }?.value as? String ?: "",
                    geohash = properties.firstOrNull { it.key == "geohash" }?.value as? String ?: "",
                    minimumSpeed = (properties.firstOrNull { it.key == "minimumSpeed" }?.value as? String)?.toIntOrNull() ?: 0,
                    maximumSpeed = (properties.firstOrNull { it.key == "maximumSpeed" }?.value as? String)?.toIntOrNull() ?: 0,
                    averageSpeed = (properties.firstOrNull { it.key == "averageSpeed" }?.value as? String)?.toIntOrNull() ?: 0,
                    numberOfVehicles = (properties.firstOrNull { it.key == "numberOfVehicles" }?.value as? String)?.toIntOrNull() ?: 0
                ).also { td: TrafficDensity ->
                    td.location = properties.firstOrNull { it.key == "location" }?.value as? String ?: ""
                }
            }
        } else {
            val result: SearchResult = search.search(INDEX_TRAFFIC_DENSITY, queryString, QueryOptions.defaults()
                .returnAttributes(
                    ReturnAttribute("id"),
                    ReturnAttribute("latitude"),
                    ReturnAttribute("longitude"),
                    ReturnAttribute("geohash"),
                    ReturnAttribute("minimumSpeed"),
                    ReturnAttribute("maximumSpeed"),
                    ReturnAttribute("averageSpeed"),
                    ReturnAttribute("numberOfVehicles"),
                    ReturnAttribute("location"),
                    ReturnAttribute("dateTime")
                )
                .limit(offset, limit)
            )
            totalResults = result.total
            searchResults = result.documents.map { doc: Document ->
                val attributes: Map<String, Any> = doc.attributes
                TrafficDensity(
                    id = UUID.fromString(attributes["id"] as? String ?: UUID.randomUUID().toString()),
                    dateTime = attributes["dateTime"] as String,
                    latitude = attributes["latitude"] as String,
                    longitude = attributes["longitude"] as String,
                    geohash = attributes["geohash"] as String,
                    minimumSpeed = (attributes["minimumSpeed"] as String).toInt(),
                    maximumSpeed = (attributes["maximumSpeed"] as String).toInt(),
                    averageSpeed = (attributes["averageSpeed"] as String).toInt(),
                    numberOfVehicles = (attributes["numberOfVehicles"] as String).toInt(),
                ).also {
                    it.location = attributes["location"] as String
                }
            }
        }
    }
    return mapOf(
        "searchDuration" to searchDuration.inWholeMilliseconds,
        "query" to queryString,
        "total" to totalResults,
        "results" to searchResults,
        "limit" to limit,
        "offset" to offset,
    )
}
```
The idea is to build a flexible search query based on user input parameters, allowing for geo-spatial searches, speed filtering, and pagination. Based on the <a href="https://redis.io/docs/latest/develop/ai/search-and-query/advanced-concepts/query_syntax/">documentation</a>, the query syntax supports complex conditions, making it easy to filter results based on multiple criteria.

## Advanced Search Features
Each case can be handled with Jedis or Redisson, but the syntax and API differ slightly. Passing the `type` parameter allows you to switch between Jedis and Redisson implementations seamlessly.

### Geo-Spatial Queries
Find all traffic points within 5km of a coordinate using Jedis:
```http request
GET /api/redisearch?latitude=41.076&longitude=28.887&radius=5&type=jedis
```

### Combined Filters
Search for high-traffic areas with specific speed ranges using redisson(aka lettuce):
```http request
GET /api/redisearch?latitude=41.076&longitude=28.887&radius=10&minSpeed=30&maxSpeed=60&numberOfVehicles=50&type=lettuce
```
Full examples can be found in the source code repository.

### Range Queries
RediSearch supports sophisticated range queries:
- `@averageSpeed:[30 60]` - Speed between 30-60 km/h
- `@numberOfVehicles:[50 +inf]` - More than 50 vehicles
- `@location:[28.887 41.076 5 km]` - Within 5km radius

### Performance Comparison in Our Implementation
Our controller includes a performance comparison endpoint that measures search operations using both clients:

```kotlin
@GetMapping
fun compareEach(): Any {
    val allByRediSearch: Duration = measureTime {
        search()
    }
    val countByRepository: Duration = measureTime {
        val count: Long = trafficDensityRepository.count()
        log.info("countByRepository: $count")
    }
    val keys: MutableSet<String>?
    val allKeysByRedisTemplateExecute: Duration = measureTime  {
        keys = redisTemplate.execute { connection: RedisConnection ->
            var cursor: Cursor<ByteArray?>? = null
            val keysTmp: MutableSet<String> = HashSet()
            try {
                cursor = connection.scan(ScanOptions.scanOptions().match("traffic_density*").count(100).build())
                while (cursor.hasNext()) {
                    log.info("Found key: ${String(bytes = cursor.next()!!)}")
                    val key: String = String(cursor.next()!!) // Convert ByteArray to String
                    if (connection.type(key.toByteArray()) == DataType.HASH) {
                        keysTmp.add(element = key)
                    }
                }
            } catch (e: Exception) {
                log.error("Error getting traffic_density keys. Error: ${e.localizedMessage}")
            } finally {
                if (cursor != null && !cursor.isClosed) {
                    try {
                        cursor.close()
                    } catch (e: IOException) {
                        log.error("Error closing cursor. Error: ${e.localizedMessage}")
                    }
                }
            }
            keysTmp
        }
    }
    val entityDatas: ArrayList<TrafficDensity> = arrayListOf()
    val allByOpsForHashEntries: Duration = measureTime  {
        for (key: String in keys!!) {
            val linkedHashMap: MutableMap<String, Any> = opsForHash.entries(key)
            val entityData = TrafficDensity(
                id = UUID.fromString(linkedHashMap["id"] as String),
                dateTime = "${linkedHashMap["dateTime"]}",
                latitude = linkedHashMap["latitude"] as String,
                longitude = linkedHashMap["longitude"] as String,
                geohash = linkedHashMap["geohash"] as String,
                minimumSpeed = Integer.parseInt("${linkedHashMap["minimumSpeed"]}"),
                maximumSpeed = Integer.parseInt("${linkedHashMap["maximumSpeed"]}"),
                averageSpeed = Integer.parseInt("${linkedHashMap["averageSpeed"]}"),
                numberOfVehicles = Integer.parseInt("${linkedHashMap["numberOfVehicles"]}")
            )
            entityDatas.add(element = entityData)
        }
    }
    val firstElement: TrafficDensity
    val allByRepository: Duration = measureTime  {
        val findAll: MutableList<TrafficDensity> = trafficDensityRepository.findAll()
        firstElement = findAll.first()
    }
    val allByFindAllByLatitudeAndLongitude: Duration = measureTime  {
        val findAllByLatitudeAndLongitude = trafficDensityRepository.findAllByLatitudeAndLongitude(latitude = firstElement.latitude, longitude = firstElement.longitude)
    }
    val allKeysByRedisTemplate: Duration = measureTime  {
        val keys1 = redisTemplate.keys("traffic_density:*")
    }
    return mapOf(
        "allByRediSearch" to allByRediSearch.inWholeMilliseconds,
        "countByRepository" to countByRepository.inWholeMilliseconds,
        "allKeysByRedisTemplateExecute" to allKeysByRedisTemplateExecute.inWholeMilliseconds,
        "allByOpsForHashEntries" to allByOpsForHashEntries.inWholeMilliseconds,
        "allByRepository" to allByRepository.inWholeMilliseconds,
        "allByFindAllByLatitudeAndLongitude" to allByFindAllByLatitudeAndLongitude.inWholeMilliseconds,
        "allKeysByRedisTemplate" to allKeysByRedisTemplate.inWholeMilliseconds,
    ).also { it: Map<String, Long> ->
        log.info("Result: $it")
    }
    
}
```
This endpoint compares the performance of different search methods, including RediSearch, repository-based queries, and Redis template operations. The results show that RediSearch provides the fastest response times for complex queries, while repository methods are slower due to the overhead of ORM operations.
```json
{
  "allByRediSearch":24,
  "countByRepository": 67,
  "allKeysByRedisTemplateExecute": 32505,
  "allByOpsForHashEntries": 16430,
  "allByRepository": 20672,
  "allByFindAllByLatitudeAndLongitude": 58,
  "allKeysByRedisTemplate": 196
}
```
If the data is big enough, redistemplate will be failed through the `java.lang.OutOfMemoryError: Java heap space` error, while RediSearch will return the results in less than 30ms. This demonstrates the power of RediSearch for handling large datasets efficiently, especially for geo-spatial.

## Performance Optimization Tips
### 1. Index Design
- Use appropriate field types (numeric for numbers, geo for coordinates)
- Index only searchable fields to reduce memory usage
- Use tag fields for exact matches

### 2. Query Optimization
- Use specific field queries instead of full-text search when possible
- Implement pagination to limit result sets
- Cache frequently used queries

### 3. Connection Management
```kotlin
// Redisson with optimized connection pool
config.useSingleServer().apply {
    connectionPoolSize = 16
    connectionMinimumIdleSize = 4
    timeout = 10_000
    retryAttempts = 5
}
```

### 4. Memory Management
- Use appropriate Redis eviction policies
- Monitor memory usage with Redis monitoring tools
- Implement data archiving strategies for old records

## Real-World Use Cases
### 1. Location-Based Services
- Restaurant recommendations within radius
- Ride-sharing driver matching
- Real estate property search

### 2. E-commerce
- Product search with filters
- Price range queries
- Inventory location search

### 3. IoT and Monitoring
- Sensor data analysis
- Traffic monitoring (as in our example)
- Environmental data search

## When to Choose What

### Choose Jedis When:
- Building high-performance, low-latency applications
- Need direct control over Redis operations
- Working with simple data structures
- Memory usage is critical
- Team has strong Redis expertise

### Choose Redisson When:
- Rapid application development is priority
- Need distributed computing features
- Working with complex data structures
- Async/reactive programming is required
- Team prefers high-level abstractions

## Problems
### `OFFSET exceeds maximum of 10000`
`FT.CONFIG SET MAXSEARCHRESULTS 100000` or 0 to disable it all

### **Asynchronous Indexing**
- RediSearch indexes data asynchronously. When you add large amounts of data quickly, the indexing process may lag behind data insertion.
- RediSearch is still processing and indexing the documents in the background.

### **Memory Pressure**
With 829,001 records, Redis may experience memory pressure, causing:
- Partial index evictions
- Incomplete indexing cycles
- Index corruption under load

### Production Recommendations
1. **Use Redis Cluster** for datasets > 1M records
2. **Implement circuit breakers** for search operations
3. **Add retry logic** with exponential backoff
4. **Monitor index health** continuously
5. **Set up alerts** for index inconsistencies
6. **Consider data partitioning** by time or geography
7. **Implement graceful degradation** when search is unavailable

By implementing these strategies, you can ensure consistent search results even with large datasets, while maintaining the performance benefits that RediSearch provides.

## Conclusion
RediSearch with Spring Boot and Kotlin provides a powerful combination for building high-performance search applications. The choice between Jedis and Redisson depends on your specific requirements:

- **Jedis** excels in performance-critical applications where every millisecond counts
- **Redisson** shines in rapid development scenarios with complex requirements

Both clients successfully demonstrate RediSearch's capabilities in our traffic density search application, providing sub-30ms search responses for geo-spatial queries on large datasets.

The key to success with RediSearch is understanding your data access patterns, designing appropriate indexes, and choosing the right client library for your team's expertise and application requirements.

## Source Code

The complete source code for this project is available on GitHub, demonstrating both Jedis and Redisson implementations with comprehensive examples of geo-spatial search, range queries, and performance comparisons.

Whether you choose Jedis for its performance or Redisson for its convenience, RediSearch provides the foundation for building lightning-fast search experiences that can scale with your application's growth.
