package com.github.senocak.config

import com.github.senocak.logger
import org.slf4j.Logger
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisClusterConfiguration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.RedisNode
import org.springframework.data.redis.connection.RedisPassword
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.StringRedisSerializer
import redis.clients.jedis.ConnectionPoolConfig
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import redis.clients.jedis.JedisPooled

@Configuration
class RedisConfig(
    private val redisProperties: RedisProperties
){
    private val log: Logger by logger()

//    @Bean
//    fun jedisPool(): JedisPool =
//        JedisPool(JedisPoolConfig(), redisProperties.host, redisProperties.port, redisProperties.timeout.seconds.toInt(),
//            if(!redisProperties.password.isNullOrEmpty()) redisProperties.password else null)

    @Bean
    fun jedisPooled(): JedisPooled =
        JedisPooled(ConnectionPoolConfig(), redisProperties.host, redisProperties.port, redisProperties.username,
            if(!redisProperties.password.isNullOrEmpty()) redisProperties.password else null)

    @Bean
    fun jedisConnectionFactory(): RedisConnectionFactory {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        redisStandaloneConfiguration.hostName = redisProperties.host
        if (!redisProperties.password.isNullOrEmpty())
            redisStandaloneConfiguration.password = RedisPassword.of(redisProperties.password)
        redisStandaloneConfiguration.port = redisProperties.port
        return LettuceConnectionFactory(redisStandaloneConfiguration)
    }

//    @Bean
//    fun redisConnectionFactory(): RedisConnectionFactory =
//        LettuceConnectionFactory(RedisClusterConfiguration()
//            .also { it: RedisClusterConfiguration ->
//                it.clusterNode(RedisNode(redisProperties.host, redisProperties.port))
//                it.password = when {
//                    redisProperties.password.isNullOrEmpty() -> RedisPassword.none()
//                    else -> RedisPassword.of(redisProperties.password)
//                }
//            })

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
}
