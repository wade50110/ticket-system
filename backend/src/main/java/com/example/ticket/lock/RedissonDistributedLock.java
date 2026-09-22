package com.example.ticket.lock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@RequiredArgsConstructor
public class RedissonDistributedLock implements DistributedLock {

    private final RedissonClient client;

    @Override
    public LockHandle tryLock(String key, Duration waitTime, Duration leaseTime) {
        RLock lock = client.getLock(key);
        try {
            boolean ok = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            return new Handle(lock, ok);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Handle(lock, false);
        }
    }

    private record Handle(RLock lock, boolean locked) implements LockHandle {
        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public void release() {
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("Redisson unlock failed for {}: {}", lock.getName(), e.getMessage());
                }
            }
        }
    }
}
