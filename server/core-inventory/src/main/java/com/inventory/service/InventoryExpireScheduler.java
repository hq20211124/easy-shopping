package com.inventory.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 预占超时释放调度（admin-backend 4.4.4：库存被幽灵锁死是 v1.0 的硬伤，必须有释放路径）。
 *
 * <p>P0：进程内定时扫（默认每 30s），扫 {@code status=ACTIVE AND expire_at < now} 的预占单逐条释放；
 * 单条失败不阻塞整轮。P1：改用 RabbitMQ 延迟队列（TTL + DLX），deliveryTimestamp 直取
 * 预占单 {@code expire_at}（= 订单 pay_deadline，唯一事实来源），本扫描仅作丢消息兜底，
 * 频率放宽到每 5 分钟。</p>
 */
@Component
public class InventoryExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryExpireScheduler.class);

    private final InventoryService inventoryService;

    public InventoryExpireScheduler(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** 到期扫描（P0 兜底主路径）。 */
    @Scheduled(fixedDelayString = "${app.inventory.expire-scan-ms:30000}",
            initialDelayString = "${app.inventory.expire-scan-initial-ms:30000}")
    public void sweepExpiredReservations() {
        try {
            int released = inventoryService.sweepExpiredReservations();
            if (released > 0) {
                log.info("预占超时释放完成: count={}", released);
            }
        } catch (Exception e) {
            log.error("预占超时扫描异常，下轮重试", e);
        }
    }
}
