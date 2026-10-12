package com.inventory.support;

import com.easyshopping.common.event.DomainEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 领域事件「本地事务提交后」发布工具（DEV.md 4.4：P0 无本地消息表，必须在提交后发）。
 *
 * <p>当前线程有活动事务时注册 afterCommit 回调；否则（定时任务/单测直接调用）立即发布。</p>
 */
public final class AfterCommitEvents {

    private AfterCommitEvents() {
    }

    public static void publish(DomainEventPublisher publisher, String eventName, Object payload) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publisher.publish(eventName, payload);
                }
            });
        } else {
            publisher.publish(eventName, payload);
        }
    }
}
