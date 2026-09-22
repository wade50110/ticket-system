package com.example.ticket.lock;

import java.time.Duration;

public interface DistributedLock {

    LockHandle tryLock(String key, Duration waitTime, Duration leaseTime);
}
