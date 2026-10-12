package com.easyshopping.common.id;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花 ID 生成器（对齐裁决 C13：分库分表主键一律 BIGINT + 应用层雪花，禁止自增，
 * 见 plans/admin-backend.md 1.2.1 约束 1 / architecture/00-conflict-register.md）。
 *
 * <p>布局：1bit 符号 + 41bit 毫秒时间戳（自定义纪元 2024-01-01）+ 5bit workerId + 12bit 序列。
 * P0 workerId 由配置 {@code app.worker-id}（各服务各实例需唯一，K8s 可用 StatefulSet 序号/环境变量注入）。</p>
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private final long workerId;
    private final AtomicLong lastTimestamp = new AtomicLong(-1L);
    private final AtomicLong sequence = new AtomicLong(0L);

    public SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER) {
            throw new IllegalArgumentException("app.worker-id 必须在 0~" + MAX_WORKER + " 之间");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        long last = lastTimestamp.get();
        if (now < last) {
            // 时钟回拨：等待追平（P0 简化处理，生产可拒绝发号并告警）
            now = last;
        }
        if (now == last) {
            long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
            if (seq == 0) {
                now = waitNextMillis(last);
            }
            sequence.set(seq);
        } else {
            sequence.set(0);
        }
        lastTimestamp.set(now);
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence.get();
    }

    private long waitNextMillis(long last) {
        long now = System.currentTimeMillis();
        while (now <= last) {
            now = System.currentTimeMillis();
        }
        return now;
    }
}
