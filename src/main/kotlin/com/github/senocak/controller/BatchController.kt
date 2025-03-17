package com.github.senocak.controller

import com.github.senocak.logger
import com.github.senocak.model.TrafficDensity
import com.github.senocak.model.TrafficDensityRepository
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
import redis.clients.jedis.JedisPool
import redis.clients.jedis.search.FTCreateParams
import redis.clients.jedis.search.IndexDefinition
import redis.clients.jedis.search.IndexOptions
import redis.clients.jedis.search.RediSearchCommands
import redis.clients.jedis.search.Schema
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
    private val jedisPool: JedisPool,
    private val redisTemplate: RedisTemplate<String, Any>,
) {
    private val log: Logger by logger()
    private val opsForHash: HashOperations<String, String, Any> = redisTemplate.opsForHash()
    private val opsForSet: SetOperations<String, Any> = redisTemplate.opsForSet()

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

    private val INDEX_CUSTOMER = "idx_cust"

    fun initializeIndex(): Long {
        val rediSearch: RediSearchCommands = jedisPool as RediSearchCommands
        // create index if it doesn't exist
        if (!rediSearch.ftList().contains(INDEX_CUSTOMER)) {
            // Disable stopwords so we can search for people with the name "An"
            // FT.CREATE idx_cust PREFIX 1 "cust:" STOPWORDS 0 SCHEMA "First Name" AS firstname TEXT NOSTEM "Last Name" AS lastname TEXT NOSTEM "CIF" AS cif TAG
            // "Credit card 1" as "cc1" TAG "Credit card 2" as "cc2" TAG
            val params: FTCreateParams = FTCreateParams.createParams()
            params.addPrefix("cust:")

            val firstName = Schema.TextField("First name", 2.0, false, true) // no stemming
            val lastName = Schema.TextField("Last name", 2.0, false, true) // no stemming

            val sc: Schema = Schema()
                .addField(firstName).`as`("firstname")
                .addField(lastName).`as`("lastname")
                .addTagField("CIF").`as`("cif")
                .addTagField("Credit card 1").`as`("cc1")
                .addTagField("Credit card 2").`as`("cc2")
                .addNumericField("Registration date epoch").`as`("regdate")

            val def: IndexDefinition = IndexDefinition().setPrefixes("cust:")

            val opt: IndexOptions = IndexOptions.defaultOptions()
            opt.setNoStopwords()

            rediSearch.ftCreate(INDEX_CUSTOMER, opt.setDefinition(def), sc)
        }

        return 0
    }
}
