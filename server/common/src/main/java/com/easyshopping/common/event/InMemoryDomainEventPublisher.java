package com.easyshopping.common.event;

import com.easyshopping.common.id.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * P0 领域事件实现：打日志 + 进程内同步分发给订阅者。
 *
 * <p>同 JVM 内的消费者可调用 {@link #subscribe(String, Consumer)} 注册；
 * 跨服务通信 P0 不支持（各服务直查对方 DB 也禁止），需要跨服务时等 P1 RabbitMQ。</p>
 */
public class InMemoryDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(InMemoryDomainEventPublisher.class);

    private final SnowflakeIdGenerator idGenerator;
    private final Map<String, List<Consumer<DomainEvent>>> subscribers = new ConcurrentHashMap<>();

    public InMemoryDomainEventPublisher(SnowflakeIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    public void publish(String eventName, Object data) {
        DomainEvent event = new DomainEvent(idGenerator.nextId(), eventName, Instant.now(), data);
        log.info("[domain-event] name={} eventId={} data={}", eventName, event.eventId(), data);
        for (Consumer<DomainEvent> subscriber : subscribers.getOrDefault(eventName, List.of())) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                // P0 同步分发失败只记录；P1 MQ 侧按重试/死信策略处理
                log.error("[domain-event] subscriber failed: name={} eventId={}", eventName, event.eventId(), e);
            }
        }
    }

    /** 进程内订阅（P0/P1 均可用作同 JVM 副作用入口）。 */
    public void subscribe(String eventName, Consumer<DomainEvent> subscriber) {
        subscribers.computeIfAbsent(eventName, k -> new java.util.concurrent.CopyOnWriteArrayList<>()).add(subscriber);
    }
}
