package com.github.senocak.model

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import org.springframework.data.annotation.Id
import org.springframework.data.keyvalue.repository.KeyValueRepository
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.index.Indexed
import java.io.Serializable
import java.util.UUID

@RedisHash(value = "traffic_density")
data class TrafficDensity(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID? = UUID.randomUUID(),
    val dateTime: String,
    @Column(name = "latitude", nullable = false, updatable = false) @Indexed val latitude: String,
    @Column(name = "latitude", nullable = false, updatable = false) @Indexed val longitude: String,
    val geohash: String,
    val minimumSpeed: Int,
    val maximumSpeed: Int,
    val averageSpeed: Int,
    val numberOfVehicles: Int,
): Serializable

interface TrafficDensityRepository : KeyValueRepository<TrafficDensity, UUID> {
    fun findAllByLatitude(latitude: String): List<TrafficDensity>
    fun findAllByLongitude(longitude: String): List<TrafficDensity>
    fun findAllByLatitudeAndLongitude(latitude: String, longitude: String): List<TrafficDensity>
}

