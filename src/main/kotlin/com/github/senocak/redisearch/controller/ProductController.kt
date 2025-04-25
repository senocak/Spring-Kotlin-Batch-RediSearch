package com.github.senocak.redisearch.controller

import com.github.senocak.redisearch.config.RedisConfig
import com.github.senocak.redisearch.logger
import com.github.senocak.redisearch.model.TrafficDensity
import com.github.senocak.redisearch.model.TrafficDensityRepository
import jakarta.annotation.PostConstruct
import org.redisson.api.RSearch
import org.redisson.api.RedissonClient
import org.redisson.api.search.index.FieldIndex
import org.redisson.api.search.index.IndexOptions
import org.redisson.api.search.index.IndexType
import org.redisson.api.search.query.Document
import org.redisson.api.search.query.QueryOptions
import org.redisson.api.search.query.ReturnAttribute
import org.redisson.api.search.query.SearchResult
import org.slf4j.Logger
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobParameters
import org.springframework.batch.core.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.FileSystemResource
import org.springframework.data.redis.connection.DataType
import org.springframework.data.redis.connection.RedisConnection
import org.springframework.data.redis.connection.RedisConnectionFactory
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
import redis.clients.jedis.search.RediSearchCommands
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlin.use

@RestController
@RequestMapping("/api")
class ProductController(
    @Qualifier("redisSearchClient") private val redisSearchClient: RedissonClient,
    private val redisCommands: RedisConfig.RedisCommands,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val redisConnectionFactory: RedisConnectionFactory,
    private val trafficDensityRepository: TrafficDensityRepository,
    private val jedisPool: JedisPooled,

    private val jobLauncher: JobLauncher,
    private val importVehicleCountJob: Job,
) {
    private val log: Logger by logger()
    private val search: RSearch = redisSearchClient.search
    private val INDEX_TRAFFIC_DENSITY = "traffic_idx"

    private val opsForHash: HashOperations<String, String, Any> = redisTemplate.opsForHash()
    private val opsForSet: SetOperations<String, Any> = redisTemplate.opsForSet()
    private val rediSearch: RediSearchCommands = jedisPool as RediSearchCommands

    @PostConstruct
    fun start() {
        redisConnectionFactory.connection.use { it.ping() } // Forces connection setup
        try {
            search.dropIndex(INDEX_TRAFFIC_DENSITY) // Drop the existing index
        } catch (e: Exception) {
            log.warn("Error dropping index: ${e.localizedMessage}")
        }
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
        print("Created enhanced traffic density index")
        if (redisCommands.isCluster()) {
            val hset = redisCommands.asClusterCommands()
                ?.hset("user", "anil2", "senocak2")
                ?.await(30, TimeUnit.SECONDS)
            val expire = redisCommands.asClusterCommands()
                ?.expire("user", 5)
                ?.await(5, TimeUnit.SECONDS)
        } else {
            val hset = redisCommands.asSingleServerCommands()
                ?.hset("user", "anil2", "senocak2")
                ?.await(30, TimeUnit.SECONDS)
            val expire = redisCommands.asSingleServerCommands()
                ?.expire("user", 5)
                ?.await(5, TimeUnit.SECONDS)

            val get = redisCommands.asSingleServerCommands()
                ?.hget("user", "anil2")
                ?.get()
        }
    }

    /*
    fun initializeIndexJedis() {
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
            val opt: redis.clients.jedis.search.IndexOptions = redis.clients.jedis.search.IndexOptions.defaultOptions().setNoStopwords()
            rediSearch.ftCreate(INDEX_TRAFFIC_DENSITY, opt.setDefinition(def), sc)
            log.info("Created enhanced traffic density index with geo-spatial support")
        }
    }
    */

    /* count all hashes in redis
    EVAL "local count = 0; for _,k in ipairs(redis.call('keys','*')) do if redis.call('type',k).ok == 'hash' then count = count + 1 end end; return count;" 0
    */
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
        /*
        Result: {allByRediSearch=24, countByRepository=67, allKeysByRedisTemplateExecute=32505,
        allByOpsForHashEntries=16430, allByRepository=20672, allByFindAllByLatitudeAndLongitude=58,
        allKeysByRedisTemplate=196}
        */
    }

    @PostMapping("/run")
    fun run(@RequestParam csvName: String): String {
        log.info("Starting batch job with file: $csvName")
        val csvFile = FileSystemResource(csvName)
        if (!csvFile.exists()) {
            val error = "CSV file not found: $csvName"
            log.error(error)
            throw kotlin.IllegalArgumentException(error)
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
}

//data class TrafficDensity(
//    val id: UUID,
//    val dateTime: String,
//    val latitude: String,
//    val longitude: String,
//    val geohash: String,
//    val minimumSpeed: Int,
//    val maximumSpeed: Int,
//    val averageSpeed: Int,
//    val numberOfVehicles: Int,
//): Serializable