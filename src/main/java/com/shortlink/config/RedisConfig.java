package com.shortlink.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

/**
 * Redis configuration: RedisTemplate, StringRedisTemplate, and Redisson cluster settings.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.cluster.nodes}")
    private List<String> clusterNodes;

    @Value("${spring.data.redis.timeout:3000ms}")
    private String timeout;

    /**
     * Jackson ObjectMapper with Java 8 time support.
     */
    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * Generic RedisTemplate with String key and JSON value.
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory,
                                                        ObjectMapper redisObjectMapper) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(redisObjectMapper, Object.class);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.afterPropertiesSet();
        return template;
    }

    /**
     * Customizer to configure Redisson cluster from application.yml nodes.
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonClusterCustomizer() {
        return config -> {
            Config redissonConfig = new Config();
            String[] nodeAddresses = clusterNodes.stream()
                    .map(node -> "redis://" + node)
                    .toArray(String[]::new);
            redissonConfig.useClusterServers()
                    .addNodeAddress(nodeAddresses)
                    .setConnectTimeout(3000)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500)
                    .setScanInterval(2000)
                    .setMasterConnectionPoolSize(50)
                    .setSlaveConnectionPoolSize(50);
            // Apply cluster config to the provided config object
            config.useClusterServers()
                    .addNodeAddress(nodeAddresses)
                    .setConnectTimeout(3000)
                    .setTimeout(3000)
                    .setRetryAttempts(3)
                    .setRetryInterval(1500)
                    .setScanInterval(2000)
                    .setMasterConnectionPoolSize(50)
                    .setSlaveConnectionPoolSize(50);
        };
    }
}
