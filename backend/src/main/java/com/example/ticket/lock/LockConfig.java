package com.example.ticket.lock;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Slf4j
@Configuration
public class LockConfig {

    @Bean
    @ConditionalOnProperty(name = "ticket.lock.type", havingValue = "redisson", matchIfMissing = true)
    public DistributedLock redissonDistributedLock(RedissonClient client) {
        log.info("DistributedLock impl = Redisson");
        return new RedissonDistributedLock(client);
    }

    @Bean
    @ConditionalOnProperty(name = "ticket.lock.type", havingValue = "manual")
    public DistributedLock redisTemplateDistributedLock(StringRedisTemplate redis) {
        log.info("DistributedLock impl = RedisTemplate (manual Lua)");
        return new RedisTemplateDistributedLock(redis);
    }
}
