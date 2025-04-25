package com.github.senocak.redisearch.model

import org.springframework.data.annotation.Id
import org.springframework.data.keyvalue.repository.KeyValueRepository
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.index.Indexed
import java.io.Serializable
import java.util.UUID

@RedisHash(value = "traffic_density")
data class TrafficDensity(
    @Id
    val id: UUID? = UUID.randomUUID(),
    val dateTime: String,
    @Indexed val latitude: String,
    @Indexed val longitude: String,
    val geohash: String,
    val minimumSpeed: Int,
    val maximumSpeed: Int,
    val averageSpeed: Int,
    val numberOfVehicles: Int,
): Serializable {
    var location: String? = null
}

interface TrafficDensityRepository : KeyValueRepository<TrafficDensity, UUID> {
    fun findAllByLatitude(latitude: String): List<TrafficDensity>
    fun findAllByLongitude(longitude: String): List<TrafficDensity>
    fun findAllByLatitudeAndLongitude(latitude: String, longitude: String): List<TrafficDensity>
}
