package com.order.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 订单号生成器：24 位定长编码 = 时间 14 + 分片槽位 4 + 序列 6（00-架构总纲 4.3.1 / ios-backend 1.2.1）。
 *
 * <ul>
 *   <li>时间 14 位：{@code yyyyMMddHHmmss}，服务端时区 Asia/Shanghai（DEV.md 4.3）；</li>
 *   <li>槽位 4 位：{@code userId % 10000}（分片键派生，槽位可反解分区路由；P0 未分片也保持同构）；</li>
 *   <li>序列 6 位：单机 AtomicLong 环形序列，同一秒同槽位内 100 万单不重复；
 *       {@code uk_order_no} 唯一索引为最终防线（裁决 C13：主键/单号禁止自增）。</li>
 * </ul>
 */
@Component
public class OrderNoGenerator {

    private static final DateTimeFormatter TIME_PART = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();

    @Autowired
    public OrderNoGenerator(@Value("${app.worker-id:1}") long workerId) {
        // Asia/Shanghai 服务端时区；workerId 预留给 P1 多实例序列分段（当前序列本地自增，槽位已含分片信息）
        this(Clock.system(ZoneId.of("Asia/Shanghai")));
    }

    /** 测试用构造（固定时钟）；两个构造并存时必须显式 @Autowired 指定注入入口。 */
    OrderNoGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next(Long userId) {
        String time = LocalDateTime.ofInstant(Instant.now(clock), clock.getZone()).format(TIME_PART);
        long slot = Math.floorMod(userId == null ? 0L : userId, 10_000L);
        long seq = sequence.getAndIncrement() % 1_000_000L;
        return String.format("%s%04d%06d", time, slot, seq);
    }
}
