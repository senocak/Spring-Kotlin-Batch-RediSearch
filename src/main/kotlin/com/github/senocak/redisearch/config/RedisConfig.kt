package com.github.senocak.redisearch.config

import io.lettuce.core.AbstractRedisClient
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulConnection
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.BaseRedisAsyncCommands
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.client.codec.StringCodec
import org.redisson.config.Config
import org.redisson.config.TransportMode
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.JedisPooled
import java.nio.charset.StandardCharsets
import java.time.Duration

@Configuration
class RedisConfig(private val redisProperties: RedisProperties) {

    /**
     * Sealed interface to represent Redis async commands for both single-server and cluster configurations
     */
    sealed interface RedisCommands {
        val commands: BaseRedisAsyncCommands<String, String>

        fun isCluster(): Boolean = this is Cluster

        fun asSingleServerCommands(): RedisAsyncCommands<String, String>? =
            if (this is SingleServer) commands as RedisAsyncCommands<String, String> else null

        fun asClusterCommands(): RedisClusterAsyncCommands<String, String>? =
            if (this is Cluster) commands as RedisClusterAsyncCommands<String, String> else null

        data class SingleServer(override val commands: RedisAsyncCommands<String, String>) : RedisCommands
        data class Cluster(override val commands: RedisClusterAsyncCommands<String, String>) : RedisCommands
    }

    /**
     * Sealed class to represent Redis configuration types
     */
    sealed class RedisConfigType {
        data class SingleServer(val host: String, val port: Int, val password: String?) : RedisConfigType()
        data class Cluster(val nodes: List<String>, val password: String?) : RedisConfigType()
    }

    /*
    @Bean
    fun redisClusterClient(): AbstractRedisClient =
        when {
            redisProperties.cluster != null -> {
                val redisURIs: MutableList<RedisURI> = mutableListOf()
                redisProperties.cluster.nodes.forEach { node: String? ->
                    redisURIs.add(element = RedisURI.Builder.redis(node)
                        .withTimeout(Duration.ofSeconds(60))
                        .withPassword(redisProperties.password)
                        .build())
                }
                RedisClusterClient.create(redisURIs)
            }
            else -> {
                val redisURI: RedisURI = RedisURI.Builder.redis(redisProperties.host, redisProperties.port)
                    .withTimeout(Duration.ofSeconds(60))
                    .build()
                if (redisProperties.password != null)
                    redisURI.setPassword(redisProperties.password)
                RedisClient.create(redisURI)
            }
        }
    */

    /**
     * Creates the appropriate Redis configuration based on properties
     */
    @Bean
    fun getRedisConfigType(): RedisConfigType = when {
        redisProperties.cluster?.nodes?.isNotEmpty() == true -> RedisConfigType.Cluster(
            nodes = redisProperties.cluster.nodes,
            password = redisProperties.password
        )
        else -> RedisConfigType.SingleServer(
            host = redisProperties.host,
            port = redisProperties.port,
            password = redisProperties.password
        )
    }

    @Bean(destroyMethod = "shutdown")
    fun redisClient(redisConfigType: RedisConfigType): AbstractRedisClient =
        when (val config = redisConfigType) {
            is RedisConfigType.SingleServer -> {
                val redisURI = RedisURI.Builder
                    .redis(config.host, config.port)
                    .withTimeout(Duration.ofSeconds(60))
                    .apply { config.password?.let { withPassword(it) } }
                    .build()
                RedisClient.create(redisURI)
            }
            is RedisConfigType.Cluster -> {
                val redisURIs = config.nodes.map { node ->
                    RedisURI.Builder
                        .redis(node)
                        .withTimeout(Duration.ofSeconds(60))
                        .apply { config.password?.let { withPassword(it) } }
                        .build()
                }
                RedisClusterClient.create(redisURIs)
            }
        }

    @Bean(destroyMethod = "close")
    fun redisConnection(redisClient: AbstractRedisClient): StatefulConnection<String, String> =
        when (redisClient) {
            is RedisClusterClient -> redisClient.connect()
            is RedisClient -> redisClient.connect()
            else -> throw IllegalStateException("Unsupported Redis client type")
        }

    @Bean
    fun redisAsyncCommands(connection: StatefulConnection<String, String>): RedisCommands =
        when (connection) {
            is StatefulRedisClusterConnection -> RedisCommands.Cluster(connection.async())
            is StatefulRedisConnection -> RedisCommands.SingleServer(connection.async())
            else -> throw IllegalStateException("Unsupported connection type")
        }

    @Bean(name = ["redisSearchClient"], destroyMethod = "shutdown")
    fun redisSearchClient(redisConfigType: RedisConfigType): RedissonClient {
        val config = Config()
        config.setThreads(4)
        config.setNettyThreads(64)
        config.setTransportMode(TransportMode.NIO)
        when (val redisConfig = redisConfigType) {
            is RedisConfigType.SingleServer -> {
                config.useSingleServer().apply {
                    address = "redis://${redisConfig.host}:${redisConfig.port}"
                    database = 0
                    clientName = "RedisClient"
                    timeout = 10_000
                    retryAttempts = 5
                    retryInterval = 500
                    setPassword(redisConfig.password)
                }
                config.setCodec(StringCodec(StandardCharsets.UTF_8))
            }
            is RedisConfigType.Cluster -> {
                config.useClusterServers().apply {
                    addNodeAddress(*redisConfig.nodes.map { "redis://$it" }.toTypedArray())
                    timeout = 10_000
                    retryAttempts = 5
                    retryInterval = 500
                    masterConnectionPoolSize = 16
                    slaveConnectionMinimumIdleSize = 50
                    masterConnectionMinimumIdleSize = 4
                    setCheckSlotsCoverage(false)
                    setPassword(redisConfig.password)
                }
            }
        }
        return Redisson.create(config)
    }

    @Bean
    fun redisTemplate(redisConnectionFactory: RedisConnectionFactory): RedisTemplate<String, Any> =
        RedisTemplate<String, Any>()
            .apply {
                this.connectionFactory = redisConnectionFactory
                this.keySerializer = StringRedisSerializer()
                this.valueSerializer = StringRedisSerializer()
                this.hashKeySerializer = StringRedisSerializer()
                this.hashValueSerializer = StringRedisSerializer()
                //this.hashValueSerializer = GenericJackson2JsonRedisSerializer()
                afterPropertiesSet()
            }

    @Bean
    fun jedisConnectionFactory(): RedisConnectionFactory {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        redisStandaloneConfiguration.hostName = redisProperties.host
        if (!redisProperties.password.isNullOrEmpty())
            redisStandaloneConfiguration.password = RedisPassword.of(redisProperties.password)
        redisStandaloneConfiguration.port = redisProperties.port
        return JedisConnectionFactory(redisStandaloneConfiguration)
    }

    @Bean
    fun jedisPooled(): JedisPooled =
        JedisPooled(ConnectionPoolConfig(), redisProperties.host, redisProperties.port, redisProperties.username,
            if(!redisProperties.password.isNullOrEmpty()) redisProperties.password else null)
}
