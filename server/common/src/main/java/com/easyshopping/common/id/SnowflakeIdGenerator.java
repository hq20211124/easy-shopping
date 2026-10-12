package com.easyshopping.common.id;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 雪花 ID 生成器（对齐裁决 C13：分库分表主键一律 BIGINT + 应用层雪花，禁止自增，
 * 见 plans/admin-backend.md 1.2.1 约束 1 / architecture/00-conflict-register.md）。
 *
 * <p>布局：1bit 符号 + 41bit 毫秒时间戳（自定义纪元 2024-01-01）+ 5bit workerId + 12bit 序列。
 * P0 workerId 由配置 {@code app.worker-id}（各服务各实例需唯一，K8s 可用 StatefulSet 序号/环境变量注入）。</p>
 *
 * <p><b>序列位留白（跨端精度契约）</b>：当前时间戳段已占 37bit，ID 落在 2^53~2^54 区间，
 * 该区间 IEEE754 只能精确表示<b>偶数</b>整数。Web 端（web-admin / web-buyer / miniprogram）
 * 用 JS {@code JSON.parse} 读 ID，奇数 ID 会被四舍五入成 ±1，回传后再查就是
 * 「订单不存在 4001 / 购物车项不存在 3004」。因此序列只写 bit1~bit11（写入值恒为偶数），
 * 让 ID 在 JS Number 下无损往返；代价是单实例每毫秒 2048 个号（足够 P0/P1）。
 * 到 ID 超过 2^54（约 2028-05）需改走「雪花 ID 序列化为字符串」的 P1 方案。</p>
 */
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER = (1L << WORKER_BITS) - 1;
    /** 内部步进上限（2^11-1）：步进左移 1 位写入 12bit 序列段，既不溢出 workerId 位段，也保证 ID 恒为偶数。 */
    private static final long MAX_SEQUENCE_STEP = (1L << (SEQUENCE_BITS - 1)) - 1;

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
            long step = sequence.incrementAndGet() & MAX_SEQUENCE_STEP;
            if (step == 0) {
                now = waitNextMillis(last);
            }
            sequence.set(step);
        } else {
            sequence.set(0);
        }
        lastTimestamp.set(now);
        // 序列左移 1bit 写入（bit0 留白）→ ID 恒为偶数，JS Number 在 2^53~2^54 区间可无损表示
        return ((now - EPOCH) << (WORKER_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | (sequence.get() << 1);
    }

    private long waitNextMillis(long last) {
        long now = System.currentTimeMillis();
        while (now <= last) {
            now = System.currentTimeMillis();
        }
        return now;
    }
}
