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
        @RequestParam(defaultValue = "10") limit: Int = 10,
        @RequestParam(defaultValue = "0") offset: Int = 0
    ): Map<String, Any> {
        val queryBuilder = StringBuilder()
        when {
            !latitude.isNullOrBlank() || !longitude.isNullOrBlank() -> {
                if (!latitude.isNullOrBlank()) {
                    queryBuilder.append("@latitude:*${latitude}*") // Use wildcard for partial match
                }
                if (!longitude.isNullOrBlank()) {
                    if (queryBuilder.isNotEmpty()) {
                        queryBuilder.append(" ")
                    }
                    queryBuilder.append("@longitude:*${longitude}*") // Use wildcard for partial match
                }
            }
            else -> queryBuilder.append("*") // If neither is provided, default to "*"
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
        }
        return mapOf(
            "searchDuration" to searchDuration.inWholeMilliseconds,
            "query" to queryString,
            "total" to totalResults,
            "results" to searchResults
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
                .addTextField("latitude", 1.0)
                .addTextField("longitude", 1.0)
                .addTextField("geohash", 1.0)
                .addNumericField("minimumSpeed")
                .addNumericField("maximumSpeed")
                .addNumericField("averageSpeed")
                .addNumericField("numberOfVehicles")
            val def: IndexDefinition = IndexDefinition().setPrefixes("traffic_density:")
            val opt: IndexOptions = IndexOptions.defaultOptions().setNoStopwords()
            rediSearch.ftCreate(INDEX_TRAFFIC_DENSITY, opt.setDefinition(def), sc)
            log.info("Created traffic density index")
        }
    }
}
