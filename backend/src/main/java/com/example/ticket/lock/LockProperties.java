package com.example.ticket.lock;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "ticket.lock")
public class LockProperties {

    private String type = "redisson";
    private long waitMillis = 200;
    private long leaseMillis = 3000;

    public Duration waitTime() {
        return Duration.ofMillis(waitMillis);
    }

    public Duration leaseTime() {
        return Duration.ofMillis(leaseMillis);
    }
}
