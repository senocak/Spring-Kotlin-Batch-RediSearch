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

//@Configuration
class RedisConfig2(
    private val redisProperties: RedisProperties
){

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


    @Bean
    fun abstractRedisClientConnection(redisClient: AbstractRedisClient): StatefulConnection<String, String> {
        if (redisProperties.cluster != null) {
            return (redisClient as RedisClusterClient).connect()
        }
        return (redisClient as RedisClient).connect()
    }

    @Bean
    fun redisAsyncCommands(connection: StatefulConnection<String, String>): RedisClusterAsyncCommands<String, String> {
        if (redisProperties.cluster != null) {
            return (connection as StatefulRedisClusterConnection).async()
        }
        return (connection as StatefulRedisConnection).async()
    }

    @Bean(name = ["redisSearchClient"])
    fun redisSearchClient(): RedissonClient? {
        val config = Config()
        when {
            redisProperties.cluster != null -> {
                config.useClusterServers()
                    .addNodeAddress(*redisProperties.cluster.nodes.map { host -> "redis://$host" }.toTypedArray())
                    //.setPassword("")
                    .setTimeout(10_000)
                    .setRetryAttempts(5)
                    .setRetryInterval(500)
                    .setMasterConnectionPoolSize(16)
                    .setSlaveConnectionMinimumIdleSize(50)
                    .setMasterConnectionMinimumIdleSize(4)
                    .setCheckSlotsCoverage(false)
                config.setThreads(4)
                config.setNettyThreads(64)
                config.setTransportMode(TransportMode.NIO)
            }
            else -> {
                config.useSingleServer()
                    .setAddress("redis://${redisProperties.host}:${redisProperties.port}")
                    .setDatabase(0)
                    .setClientName("RedisClient") // Bağlantı havuzu ayarları (Master ve Slave)
                    .setTimeout(10_000)
                    .setRetryAttempts(5)
                    .setRetryInterval(500)
                config.setNettyThreads(64)
                config.setCodec(StringCodec(StandardCharsets.UTF_8))
            }
        }

        return Redisson.create(config)
    }
}


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
    private sealed class RedisConfigType {
        data class SingleServer(val host: String, val port: Int, val password: String?) : RedisConfigType()
        data class Cluster(val nodes: List<String>, val password: String?) : RedisConfigType()
    }

    /**
     * Creates the appropriate Redis configuration based on properties
     */
    private fun getRedisConfigType(): RedisConfigType = when {
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
    fun redisClient(): AbstractRedisClient {
        return when (val config = getRedisConfigType()) {
            is RedisConfigType.SingleServer -> createSingleServerClient(config)
            is RedisConfigType.Cluster -> createClusterClient(config)
        }
    }

    private fun createSingleServerClient(config: RedisConfigType.SingleServer): RedisClient {
        val redisURI = RedisURI.Builder
            .redis(config.host, config.port)
            .withTimeout(Duration.ofSeconds(60))
            .apply { config.password?.let { withPassword(it) } }
            .build()
        return RedisClient.create(redisURI)
    }

    private fun createClusterClient(config: RedisConfigType.Cluster): RedisClusterClient {
        val redisURIs = config.nodes.map { node ->
            RedisURI.Builder
                .redis(node)
                .withTimeout(Duration.ofSeconds(60))
                .apply { config.password?.let { withPassword(it) } }
                .build()
        }
        return RedisClusterClient.create(redisURIs)
    }

    @Bean(destroyMethod = "close")
    fun redisConnection(redisClient: AbstractRedisClient): StatefulConnection<String, String> {
        return when (redisClient) {
            is RedisClusterClient -> redisClient.connect()
            is RedisClient -> redisClient.connect()
            else -> throw IllegalStateException("Unsupported Redis client type")
        }
    }

    @Bean
    fun redisAsyncCommands(connection: StatefulConnection<String, String>): RedisCommands {
        return when (connection) {
            is StatefulRedisClusterConnection -> RedisCommands.Cluster(connection.async())
            is StatefulRedisConnection -> RedisCommands.SingleServer(connection.async())
            else -> throw IllegalStateException("Unsupported connection type")
        }
    }

    @Bean(name = ["redisSearchClient"], destroyMethod = "shutdown")
    fun redisSearchClient(): RedissonClient {
        val config = Config()
        when (val redisConfig = getRedisConfigType()) {
            is RedisConfigType.SingleServer -> config.applySingleServerConfig(redisConfig)
            is RedisConfigType.Cluster -> config.applyClusterConfig(redisConfig)
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

    private fun Config.applySingleServerConfig(config: RedisConfigType.SingleServer) {
        useSingleServer().apply {
            address = "redis://${config.host}:${config.port}"
            database = 0
            clientName = "RedisClient"
            timeout = 10_000
            retryAttempts = 5
            retryInterval = 500
            setPassword(config.password)
        }
        setCommonConfig()
        setCodec(StringCodec(StandardCharsets.UTF_8))
    }

    private fun Config.applyClusterConfig(config: RedisConfigType.Cluster) {
        useClusterServers().apply {
            addNodeAddress(*config.nodes.map { "redis://$it" }.toTypedArray())
            timeout = 10_000
            retryAttempts = 5
            retryInterval = 500
            masterConnectionPoolSize = 16
            slaveConnectionMinimumIdleSize = 50
            masterConnectionMinimumIdleSize = 4
            setCheckSlotsCoverage(false)
            setPassword(config.password)
        }
        setCommonConfig()
    }

    private fun Config.setCommonConfig() {
        setThreads(4)
        setNettyThreads(64)
        setTransportMode(TransportMode.NIO)
    }
}