package com.easyshopping.common.event;

/**
 * 领域事件发布接口。
 *
 * <p>P0 实现：日志 + 进程内同步分发（{@code InMemoryDomainEventPublisher}）。
 * P1 接 RabbitMQ：topic 交换机、事件名沿用 {@link DomainEventNames} 一个不改、
 * DLX 统一挂 admin.dlx.exchange、失败 1s/5s/30s 三次重试进 DLQ，
 * 支付成功/订单已付等资金事件禁止静默进 DLQ —— 见 architecture/04-messaging.md 第五章。</p>
 */
public interface DomainEventPublisher {

    /**
     * 发布领域事件。必须在本地事务提交后调用（P1 起改本地消息表/事务消息保证原子性）。
     *
     * @param eventName 事件名，必须取自 {@link DomainEventNames}，新增事件须先在 04-messaging §7.1 登记
     * @param data      事件载荷
     */
    void publish(String eventName, Object data);
}
