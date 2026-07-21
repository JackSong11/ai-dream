package com.example.dream.integration.service.redis.lock;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Redis SET NX 的可续租分布式锁。
 */
@Slf4j
@Service
public class RedisDistributedLockService implements DistributedLockService {

    private static final Duration LEASE_TIME = Duration.ofSeconds(30);
    private static final Duration RENEW_INTERVAL = LEASE_TIME.dividedBy(3);
    private static final long MIN_RETRY_DELAY_MILLIS = 50;
    private static final long MAX_RETRY_DELAY_MILLIS = 150;
    private static final int RENEW_THREAD_COUNT = 2;
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end", Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ScheduledExecutorService renewExecutor = createRenewExecutor();
    private final Set<RedisLockHandle> activeLocks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object lifecycleMonitor = new Object();

    public RedisDistributedLockService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private static ScheduledExecutorService createRenewExecutor() {
        AtomicInteger threadNumber = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "redis-lock-renewer-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                RENEW_THREAD_COUNT, threadFactory, new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override
    public LockHandle acquire(String key, Duration waitTimeout) {
        validateArguments(key, waitTimeout);
        if (!running.get()) {
            throw new LockAcquisitionException("分布式锁服务正在关闭，无法获取锁: " + key);
        }

        String ownerToken = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        long timeoutNanos = toNanosSaturated(waitTimeout);
        while (!tryAcquire(key, ownerToken)) {
            ensureCanRetry(key, startedAt, timeoutNanos);
            sleepBeforeRetry(key, startedAt, timeoutNanos);
        }

        RedisLockHandle handle = new RedisLockHandle(key, ownerToken);
        synchronized (lifecycleMonitor) {
            if (!running.get()) {
                release(key, ownerToken);
                throw new LockAcquisitionException("分布式锁服务正在关闭，已释放刚获取的锁: " + key);
            }
            try {
                activeLocks.add(handle);
                handle.startRenewal();
                return handle;
            } catch (RejectedExecutionException e) {
                activeLocks.remove(handle);
                release(key, ownerToken);
                throw new LockAcquisitionException("续租任务无法启动，已释放分布式锁: " + key, e);
            }
        }
    }

    private boolean tryAcquire(String key, String ownerToken) {
        return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, ownerToken, LEASE_TIME));
    }

    private void ensureCanRetry(String key, long startedAt, long timeoutNanos) {
        if (!running.get()) {
            throw new LockAcquisitionException("分布式锁服务正在关闭，停止等待锁: " + key);
        }
        if (System.nanoTime() - startedAt >= timeoutNanos) {
            throw new LockAcquisitionException("等待分布式锁超时: " + key);
        }
    }

    private void sleepBeforeRetry(String key, long startedAt, long timeoutNanos) {
        long remainingNanos = timeoutNanos - (System.nanoTime() - startedAt);
        long randomDelayMillis = ThreadLocalRandom.current()
                .nextLong(MIN_RETRY_DELAY_MILLIS, MAX_RETRY_DELAY_MILLIS + 1);
        long sleepNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(randomDelayMillis), remainingNanos);
        try {
            TimeUnit.NANOSECONDS.sleep(Math.max(1, sleepNanos));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquisitionException("等待分布式锁时线程被中断: " + key, e);
        }
    }

    // 这段 @PreDestroy 修饰的 shutdown() 方法，是整个分布式锁服务在 应用停机（Graceful Shutdown） 时的“优雅收尾”保障。
    @PreDestroy
    void shutdown() {
        synchronized (lifecycleMonitor) { // 消除并发竞态（Race Condition）
            running.set(false);  // 关上大门，拒绝新请求
            // 遍历当前应用节点持有的所有未释放的分布式锁，并逐个调用它们的 close() 方法。
            activeLocks.forEach(RedisLockHandle::close);  // 主动退租，释放资源
            renewExecutor.shutdownNow();  // 强制清场，销毁续租线程池
        }
    }

    private static long toNanosSaturated(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private void release(String key, String ownerToken) {
        try {
            redisTemplate.execute(RELEASE_SCRIPT, Collections.singletonList(key), ownerToken);
        } catch (RuntimeException e) {
            log.error("Redis 分布式锁释放异常，将等待租约自动过期: key={}", key, e);
        }
    }

    private final class RedisLockHandle implements LockHandle {
        private final String key;
        private final String ownerToken;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private volatile long leaseDeadlineNanos = System.nanoTime() + LEASE_TIME.toNanos();
        private volatile ScheduledFuture<?> renewal;

        private RedisLockHandle(String key, String ownerToken) {
            this.key = key;
            this.ownerToken = ownerToken;
        }

        private void startRenewal() {
            renewal = renewExecutor.scheduleWithFixedDelay(this::renew,
                    RENEW_INTERVAL.toMillis(), RENEW_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }

        private void renew() {
            if (closed.get()) {
                return;
            }
            try {
                Long renewed = redisTemplate.execute(RENEW_SCRIPT, Collections.singletonList(key), ownerToken,
                        String.valueOf(LEASE_TIME.toMillis()));
                if (Long.valueOf(1L).equals(renewed)) {
                    leaseDeadlineNanos = System.nanoTime() + LEASE_TIME.toNanos();
                    return;
                }
                invalidate("Redis 分布式锁续租失败，锁已不属于当前持有者: key={}", null);
            } catch (RuntimeException e) {
                if (System.nanoTime() - leaseDeadlineNanos >= 0) {
                    invalidate("Redis 分布式锁租约已因持续续租异常而失效: key={}", e);
                } else {
                    log.error("Redis 分布式锁续租异常，将在租约到期前继续重试: key={}", key, e);
                }
            }
        }

        private void invalidate(String message, RuntimeException cause) {
            valid.set(false);
            cancelRenewal();
            activeLocks.remove(this);
            if (cause == null) {
                log.warn(message, key);
            } else {
                log.error(message, key, cause);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            valid.set(false);
            cancelRenewal();
            activeLocks.remove(this);
            release(key, ownerToken);
        }

        private void cancelRenewal() {
            ScheduledFuture<?> currentRenewal = renewal;
            if (currentRenewal != null) {
                currentRenewal.cancel(false);
            }
        }
    }

    private static void validateArguments(String key, Duration waitTimeout) {
        if (StringUtils.isBlank(key)) {
            throw new IllegalArgumentException("分布式锁 key 不能为空");
        }
        if (waitTimeout == null || waitTimeout.isNegative()) {
            throw new IllegalArgumentException("分布式锁等待时间不能为空或负数");
        }
    }
}
