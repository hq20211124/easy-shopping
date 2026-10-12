package com.easyshopping.common.event;

/**
 * 领域事件信封（对齐 plans/admin-backend.md 1.4.2 事件结构 messageId/timestamp/data，
 * 迁移 RocketMQ 后增加 Keys/Tag，见 architecture/04-messaging.md 7.2）。
 *
 * @param eventId   全局唯一事件 ID（雪花）
 * @param eventName 事件名（路由 Key / Topic 原文，见 {@link DomainEventNames}）
 * @param occurredAt 发生时间
 * @param data      事件载荷（各服务自定义 DTO，建议只带单号 + 必要字段）
 */
public record DomainEvent(long eventId, String eventName, java.time.Instant occurredAt, Object data) {
}
