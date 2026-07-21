package com.example.dream.integration.service.redis.lock;

import java.time.Duration;

/**
 * 跨应用实例的互斥锁服务。
 */
public interface DistributedLockService {

    Duration DEFAULT_WAIT_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 在默认等待时间内获得指定业务键的锁。
     *
     * @param key 全局唯一的业务锁键
     * @return 仅可由持有者释放的锁句柄
     */
    default LockHandle acquire(String key) {
        return acquire(key, DEFAULT_WAIT_TIMEOUT);
    }

    /**
     * 在指定等待时间内获得业务锁，避免无限阻塞请求线程。
     *
     * @param key         全局唯一的业务锁键
     * @param waitTimeout 最长等待时间，允许为零（仅尝试一次）
     * @return 仅可释放当前持有者锁的锁句柄
     * @throws LockAcquisitionException 等待超时、线程中断或服务正在关闭
     */
    LockHandle acquire(String key, Duration waitTimeout);

    /**
     * 获取锁失败时抛出的异常。
     */
    class LockAcquisitionException extends RuntimeException {
        public LockAcquisitionException(String message) {
            super(message);
        }

        public LockAcquisitionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
