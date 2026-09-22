package com.example.ticket.config;

import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * ShedLock 設定：讓帶 {@code @SchedulerLock} 的排程在多 pod(k8s autoscale)下,
 * 全叢集同一時刻只有一個實例執行。用現有 Redis 當鎖儲存,不引入新基礎設施。
 *
 * <p>{@code @EnableScheduling} 已在 {@code TicketApplication} 主類別啟用,此處不重複。
 * defaultLockAtMostFor 是未指定 lockAtMostFor 的排程的預設保險絲。
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
public class SchedulerConfig {

    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "ticket");
    }
}
