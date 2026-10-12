package com.easyshopping.common.event;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件注册表自检：30 个事件、无重复、命名符合点分小写规范。
 * 依据 architecture/04-messaging.md §7.1（唯一权威事件注册表）。
 */
class DomainEventNamesTest {

    @Test
    void exactlyThirtyUniqueEvents() {
        List<String> all = DomainEventNames.all();
        assertEquals(30, all.size(), "事件总数必须与 04-messaging §7.1 一致（30 个）");
        Set<String> distinct = all.stream().collect(Collectors.toSet());
        assertEquals(30, distinct.size(), "事件名不允许重复");
        assertTrue(all.stream().allMatch(n -> n.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")),
                "事件名必须为点分小写格式（允许下划线）");
    }

    @Test
    void inMemoryPublisherDispatchesToSubscriber() {
        InMemoryDomainEventPublisher publisher = new InMemoryDomainEventPublisher(
                new com.easyshopping.common.id.SnowflakeIdGenerator(1));
        publisher.subscribe(DomainEventNames.ORDER_CREATED,
                event -> publisher.publish(DomainEventNames.INVENTORY_CHANGE, "skuId=1"));
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        publisher.subscribe(DomainEventNames.INVENTORY_CHANGE, event -> received.add(event.eventName()));
        publisher.publish(DomainEventNames.ORDER_CREATED, "orderNo=TEST");
        assertEquals(List.of(DomainEventNames.INVENTORY_CHANGE), received);
    }
}
