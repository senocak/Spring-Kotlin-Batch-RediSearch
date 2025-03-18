package com.github.senocak.controller

import com.github.senocak.logger
import com.github.senocak.model.TrafficDensity
import com.github.senocak.model.TrafficDensityRepository
import jakarta.annotation.PostConstruct
import org.slf4j.Logger
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.core.io.FileSystemResource
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.core.Cursor
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.SetOperations
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import redis.clients.jedis.JedisPooled
import redis.clients.jedis.search.Document
import redis.clients.jedis.search.FTCreateParams
import redis.clients.jedis.search.IndexDefinition
import redis.clients.jedis.search.IndexOptions
import redis.clients.jedis.search.Query
import redis.clients.jedis.search.RediSearchCommands
import redis.clients.jedis.search.Schema
import redis.clients.jedis.search.SearchResult
import redis.clients.jedis.search.aggr.AggregationBuilder
import redis.clients.jedis.search.aggr.AggregationResult
import redis.clients.jedis.search.aggr.Group
import redis.clients.jedis.search.aggr.Reducers
import redis.clients.jedis.search.aggr.SortedField
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.measureTime

@RestController
@RequestMapping("/api/batch/traffic-density")
class BatchController(
    private val jobLauncher: JobLauncher,
    private val importVehicleCountJob: Job,
    private val trafficDensityRepository: TrafficDensityRepository,
    private val jedisPool: JedisPooled,
    private val redisTemplate: RedisTemplate<String, Any>,
) {
    private val log: Logger by logger()
    private val opsForHash: HashOperations<String, String, Any> = redisTemplate.opsForHash()
    private val opsForSet: SetOperations<String, Any> = redisTemplate.opsForSet()
    private val rediSearch: RediSearchCommands = jedisPool as RediSearchCommands

    // Helper method to update location field for geo-spatial queries
    private fun updateLocationField(trafficDensity: TrafficDensity) {
        try {
            val key = "traffic_density:${trafficDensity.id}"
            // Add location field for geo-spatial queries
            if (trafficDensity.latitude.isNotBlank() && trafficDensity.longitude.isNotBlank()) {
                try {
                    val lat = trafficDensity.latitude.toDouble()
                    val lon = trafficDensity.longitude.toDouble()
                    jedisPool.hset(key, "location", "$lon,$lat")
                    log.debug("Updated location field for $key: $lon,$lat")
                } catch (e: NumberFormatException) {
                    log.warn("Invalid latitude/longitude format for $key: ${trafficDensity.latitude},${trafficDensity.longitude}")
                }
            }
        } catch (e: Exception) {
            log.error("Error updating location field: ${e.localizedMessage}")
        }
    }

    @PostMapping("/run")
    fun run(@RequestParam csvName: String): String {
        log.info("Starting batch job with file: $csvName")
        val csvFile = FileSystemResource(csvName)
        if (!csvFile.exists()) {
            val error = "CSV file not found: $csvName"
            log.error(error)
            throw IllegalArgumentException(error)
        }
        val jobId: String = System.currentTimeMillis().toString()
        val params: JobParameters = JobParametersBuilder()
            .addString("filePath", csvFile.file.absolutePath)
            .addString("jobId", jobId)
            .toJobParameters()
        try {
            val jobExecution: JobExecution = jobLauncher.run(importVehicleCountJob, params)
            log.info("Job completed with status: ${jobExecution.status}")
            return "Batch job completed with status: ${jobExecution.status}"
        } catch (e: Exception) {
            log.error("Error running batch job: ${e.message}", e)
            throw e
        }
    }

    @GetMapping
    fun get(): Any {
        val countByRepository: Duration = measureTime {
            val count: Long = trafficDensityRepository.count()
        }
        val keys: MutableSet<String>?
        val allKeysByRedisTemplateExecute: Duration = measureTime  {
            keys = redisTemplate.execute { connection: RedisConnection ->
                var cursor: Cursor<ByteArray?>? = null
                val keysTmp: MutableSet<String> = HashSet()
                try {
                    cursor = connection.scan(ScanOptions.scanOptions().match("traffic_density*").count(100).build())
                    while (cursor.hasNext()) {
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
            "countByRepository" to countByRepository.inWholeMilliseconds,
            "allKeysByRedisTemplateExecute" to allKeysByRedisTemplateExecute.inWholeMilliseconds,
            "allByOpsForHashEntries" to allByOpsForHashEntries.inWholeMilliseconds,
            "allByRepository" to allByRepository.inWholeMilliseconds,
            "allByFindAllByLatitudeAndLongitude" to allByFindAllByLatitudeAndLongitude.inWholeMilliseconds,
            "allKeysByRedisTemplate" to allKeysByRedisTemplate.inWholeMilliseconds,
        )
    }

    private val INDEX_TRAFFIC_DENSITY = "idx_traffic_density"

    @GetMapping("/search")
    fun search(
        @RequestParam(required = false) latitude: String? = null,
        @RequestParam(required = false) longitude: String? = null,
        @RequestParam(required = false) minSpeed: Int? = null,
        @RequestParam(required = false) maxSpeed: Int? = null,
        @RequestParam(defaultValue = "false") fuzzy: Boolean = false,
        @RequestParam(defaultValue = "10") limit: Int = 10,
        @RequestParam(defaultValue = "0") offset: Int = 0
    ): Map<String, Any> {
        val queryBuilder = StringBuilder()
        val conditions: MutableList<String> = mutableListOf()
        latitude?.let { it: String ->
            conditions.add(element = if (fuzzy) "@latitude:%${it}%" else "@latitude:*${it}*")
        }
        longitude?.let { it: String ->
            conditions.add(element = if (fuzzy) "@longitude:%${it}%" else "@longitude:*${it}*")
        }
        if (minSpeed != null || maxSpeed != null)
            conditions.add(element = "@averageSpeed:[${minSpeed} ${maxSpeed}]")
        // Combine all conditions with AND
        when {
            conditions.isNotEmpty() -> queryBuilder.append(conditions.joinToString(separator = " "))
            else -> queryBuilder.append("*") // If no conditions, default to "*"
        }
        val queryString: String = queryBuilder.toString()
        // Create query
        val query: Query = Query(queryString)
            .limit(offset, limit)
            .setWithScores()
            .returnFields("id", "dateTime", "latitude", "longitude", "geohash", "minimumSpeed", "maximumSpeed", "averageSpeed", "numberOfVehicles")
        // Execute search
        var searchDuration: Duration = Duration.ZERO
        var searchResults: List<TrafficDensity> = emptyList()
        var totalResults: Long = 0
        try {
            val searchResult: SearchResult
            searchDuration = measureTime {
                searchResult = rediSearch.ftSearch(INDEX_TRAFFIC_DENSITY, query)
            }
            totalResults = searchResult.totalResults
            if (totalResults > 0) {
                searchResults = searchResult.documents.map { doc: Document ->
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
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Error executing search: ${e.localizedMessage}")
            return mapOf("Error" to e.localizedMessage)
        }
        return mapOf(
            "searchDuration" to searchDuration.inWholeMilliseconds,
            "query" to queryString,
            "total" to totalResults,
            "results" to searchResults
        )
    }

    @GetMapping("/geo-search")
    fun geoSearch(
        @RequestParam latitude: Double,
        @RequestParam longitude: Double,
        @RequestParam(defaultValue = "1.0") radius: Double,
        @RequestParam(defaultValue = "km") unit: String = "km",
        @RequestParam(defaultValue = "10") limit: Int = 10,
        @RequestParam(defaultValue = "0") offset: Int = 0
    ): Map<String, Any> {
        val queryString = "@location:[$longitude $latitude $radius $unit]" // Build geo query
        val query: Query = Query(queryString)
            .limit(offset, limit)
            .setWithScores()
            .returnFields("id", "dateTime", "latitude", "longitude", "geohash", "minimumSpeed", "maximumSpeed", "averageSpeed", "numberOfVehicles")

        // Execute search
        var searchDuration: Duration = Duration.ZERO
        var searchResults: List<TrafficDensity> = emptyList()
        var totalResults: Long = 0
        try {
            val searchResult: SearchResult
            searchDuration = measureTime {
                searchResult = rediSearch.ftSearch(INDEX_TRAFFIC_DENSITY, query)
            }
            totalResults = searchResult.totalResults
            if (totalResults > 0) {
                searchResults = searchResult.documents.map { doc: Document ->
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
                    )
                }
            }
        } catch (e: Exception) {
            log.error("Error executing geo search: ${e.localizedMessage}")
            return mapOf("Error" to e.localizedMessage)
        }
        return mapOf(
            "searchDuration" to searchDuration.inWholeMilliseconds,
            "query" to queryString,
            "total" to totalResults,
            "results" to searchResults
        )
    }

    @GetMapping("/aggregate")
    fun aggregate(
        @RequestParam(required = false) geohash: String? = null,
        @RequestParam(defaultValue = "geohash") groupBy: String = "geohash",
        @RequestParam(defaultValue = "10") limit: Int = 10
    ): Map<String, Any> {
        val queryString: String = if (geohash != null) "@geohash:$geohash" else "*"
        // Create aggregation builder
        val aggregationBuilder: AggregationBuilder = AggregationBuilder(queryString)
            .groupBy("@$groupBy") // Group by the specified field
            .limit(0, limit)

        // Execute aggregation
        val aggregationDuration: Duration
        val aggregationResults: List<Map<String, Any>>
        try {
            aggregationDuration = measureTime {
                val aggResult: AggregationResult = rediSearch.ftAggregate(INDEX_TRAFFIC_DENSITY, aggregationBuilder)
                aggregationResults = aggResult.results.map { row: MutableMap<String, Any> ->
                    row.entries.associate { entry: MutableMap.MutableEntry<String, Any> ->
                        entry.key to entry.value
                    }
                }
            }
        } catch (e: Exception) {
            log.error("Error executing aggregation: ${e.localizedMessage}")
            return mapOf("Error" to e.localizedMessage)
        }
        return mapOf(
            "aggregationDuration" to aggregationDuration.inWholeMilliseconds,
            "query" to queryString,
            "groupBy" to groupBy,
            "results" to aggregationResults
        )
    }

    @PostConstruct
    fun initializeIndex() {
        try {
            rediSearch.ftDropIndex(INDEX_TRAFFIC_DENSITY) // Drop the existing index
        } catch (e: Exception) {
            log.warn("Error dropping index: ${e.localizedMessage}")
        }
        // Create traffic density index if it doesn't exist
        if (!rediSearch.ftList().contains(INDEX_TRAFFIC_DENSITY)) {
            val params: FTCreateParams = FTCreateParams.createParams().addPrefix("traffic_density:")
            val sc: Schema = Schema()
                .addTagField("id")
                .addTextField("dateTime", 1.0)
                // Keep latitude and longitude as text fields for backward compatibility
                .addTextField("latitude", 1.0)
                .addTextField("longitude", 1.0)
                // Add a geo field for spatial queries
                .addGeoField("location")
                .addTextField("geohash", 1.0)
                .addNumericField("minimumSpeed")
                .addNumericField("maximumSpeed")
                .addNumericField("averageSpeed")
                .addNumericField("numberOfVehicles")
            val def: IndexDefinition = IndexDefinition().setPrefixes("traffic_density:")
            val opt: IndexOptions = IndexOptions.defaultOptions().setNoStopwords()
            rediSearch.ftCreate(INDEX_TRAFFIC_DENSITY, opt.setDefinition(def), sc)
            log.info("Created enhanced traffic density index with geo-spatial support")

            // Create a secondary index for aggregation queries if needed
            createAggregationIndex()
        }
    }

    private fun createAggregationIndex() {
        val AGG_INDEX = "idx_traffic_agg"
        try {
            rediSearch.ftDropIndex(AGG_INDEX)
        } catch (e: Exception) {
            log.warn("Error dropping index: ${e.localizedMessage}")
        }
        val sc: Schema = Schema()
            .addTagField("id")
            .addNumericField("averageSpeed")
            .addNumericField("numberOfVehicles")
            .addTextField("geohash", 1.0)
        val def: IndexDefinition = IndexDefinition().setPrefixes("traffic_density:")
        val opt: IndexOptions = IndexOptions.defaultOptions()
        rediSearch.ftCreate(AGG_INDEX, opt.setDefinition(def), sc)
        log.info("Created aggregation index for traffic density data")
    }
}
