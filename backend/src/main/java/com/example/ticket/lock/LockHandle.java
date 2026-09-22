package com.example.ticket.lock;

public interface LockHandle extends AutoCloseable {

    boolean isLocked();

    void release();

    @Override
    default void close() {
        release();
    }
}
