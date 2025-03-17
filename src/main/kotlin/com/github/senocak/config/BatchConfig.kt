package com.github.senocak.config

import com.github.senocak.logger
import com.github.senocak.model.TrafficDensity
import com.github.senocak.model.TrafficDensityRepository
import org.slf4j.Logger
import org.springframework.batch.core.Job
import org.springframework.batch.core.JobExecution
import org.springframework.batch.core.JobExecutionListener
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.launch.support.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.file.FlatFileItemReader
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder
import org.springframework.batch.item.file.transform.FieldSet
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.FileSystemResource
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.SetOperations
import org.springframework.transaction.PlatformTransactionManager
import java.util.Date

@Configuration
class BatchConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val trafficDensityRepository: TrafficDensityRepository,
) {
    private val hashOperations: HashOperations<String, String, Any> = redisTemplate.opsForHash<String, Any>()
    private val setOperations: SetOperations<String, Any> = redisTemplate.opsForSet()

    @Bean
    @StepScope
    fun reader(@Value("#{jobParameters['filePath']}") path: String?): FlatFileItemReader<TrafficDensity> =
        FlatFileItemReaderBuilder<TrafficDensity>()
            .name("trafficDensityReader")
            .resource(FileSystemResource(path ?: "traffic_density_202412.csv"))
            .linesToSkip(1) // skip header row
            .delimited()
            .delimiter(",")
            .names("DATE_TIME","LATITUDE","LONGITUDE","GEOHASH","MINIMUM_SPEED","MAXIMUM_SPEED","AVERAGE_SPEED","NUMBER_OF_VEHICLES")
            .fieldSetMapper { fieldSet: FieldSet ->
                TrafficDensity(
                    dateTime = fieldSet.readString("DATE_TIME"),
                    latitude = fieldSet.readString("LATITUDE"),
                    longitude = fieldSet.readString("LONGITUDE"),
                    geohash = fieldSet.readString("GEOHASH"),
                    minimumSpeed = fieldSet.readInt("MINIMUM_SPEED"),
                    maximumSpeed = fieldSet.readInt("MAXIMUM_SPEED"),
                    averageSpeed = fieldSet.readInt("AVERAGE_SPEED"),
                    numberOfVehicles = fieldSet.readInt("NUMBER_OF_VEHICLES"),
                )
            }
            .build()

    @Bean
    fun importTrafficDensityStep(): Step =
        StepBuilder("importTrafficDensityStep", jobRepository)
            .chunk<TrafficDensity, TrafficDensity>(1_000, transactionManager)
            .reader(reader(path = null)) // null path just for type resolution
            .processor { it: TrafficDensity -> it }
            .writer { list: Chunk<out TrafficDensity> ->
                if (redisTemplate.keys("traffic_density:*").size < 1_000) {
                    list.forEach { it: TrafficDensity ->
                        val entityKey = "traffic_density:${it.id}"
                        val entityMap: Map<String, String> = mapOf(
                            "id" to it.id.toString(),
                            "dateTime" to it.dateTime,
                            "latitude" to it.latitude,
                            "longitude" to it.longitude,
                            "geohash" to it.geohash,
                            "minimumSpeed" to it.minimumSpeed.toString(),
                            "maximumSpeed" to it.maximumSpeed.toString(),
                            "averageSpeed" to it.averageSpeed.toString(),
                            "numberOfVehicles" to it.numberOfVehicles.toString(),
                            "_class" to it.javaClass.name,
                        )
                        hashOperations.putAll(entityKey, entityMap)
                        // Add to secondary indexes (latitude and longitude)
                        val latitudeKey = "traffic_density:latitude:${it.latitude}"
                        setOperations.add(latitudeKey, it.id.toString())
                        val longitudeKey = "traffic_density:longitude:${it.longitude}"
                        setOperations.add(longitudeKey, it.id.toString())
                        setOperations.add("traffic_density:${it.id.toString()}:idx", latitudeKey, longitudeKey)
                        setOperations.add("traffic_density", it.id.toString())
                    }
                }
                //trafficDensityRepository.saveAll(list)
            }
            .build()

    @Bean
    fun importTrafficDensityJob(): Job =
        JobBuilder("importTrafficDensityJob", jobRepository)
            .incrementer(RunIdIncrementer()) // Optional: Ensures unique job runs
            .start(importTrafficDensityStep())
            .listener(object: JobExecutionListener {
                private val log: Logger by logger()
                private var start: Long = 0

                override fun beforeJob(jobExecution: JobExecution) {
                    start = System.currentTimeMillis()
                    log.info("Job with id ${jobExecution.jobId} is about to start at ${Date()}")
                }
                override fun afterJob(jobExecution: JobExecution) {
                    log.info("Job completed at ${Date()}, execution time in mills ${(System.currentTimeMillis() - start)}, status ${jobExecution.status}")
                }
            })
            .build()
}
