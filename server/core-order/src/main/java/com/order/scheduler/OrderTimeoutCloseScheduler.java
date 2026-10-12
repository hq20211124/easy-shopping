package com.order.scheduler;

import com.order.domain.OrderMain;
import com.order.domain.OrderStatus;
import com.order.repo.OrderMainRepository;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderLifecycleService.CloseOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 超时关单调度（admin-backend 4.4.4）。
 *
 * <p>P0：进程内定时扫（默认每 10s）{@code order_status=待付款 AND pay_deadline < now} 的<b>父单</b>，
 * 逐单 CAS 关单（子单随父单级联）→ 释放锁定库存 → 发 {@code order.cancelled}；
 * 与支付回调并发时以订单 version CAS 裁决，先到者胜（输的一方静默跳过/触发退款）。</p>
 *
 * <p>P1：主路径改 RabbitMQ 延迟队列（TTL + DLX，deliveryTimestamp 直取 pay_deadline，下单/支付成功时投递），
 * 本扫描降级为丢消息兜底（频率放宽到每 5 分钟）；{@code pay_deadline} 始终是可查询的唯一事实来源。</p>
 */
@Component
public class OrderTimeoutCloseScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCloseScheduler.class);

    private final OrderMainRepository orderMainRepository;
    private final OrderLifecycleService lifecycleService;

    public OrderTimeoutCloseScheduler(OrderMainRepository orderMainRepository,
                                      OrderLifecycleService lifecycleService) {
        this.orderMainRepository = orderMainRepository;
        this.lifecycleService = lifecycleService;
    }

    @Scheduled(fixedDelayString = "${app.order.timeout-scan-ms:10000}",
            initialDelayString = "${app.order.timeout-scan-initial-ms:10000}")
    public void scanExpiredOrders() {
        try {
            scanOnce();
        } catch (Exception e) {
            log.error("超时关单扫描异常，下轮重试", e);
        }
    }

    /** 单轮扫描（公开以便单测与手工补偿触发）。 */
    public int scanOnce() {
        List<OrderMain> expired = orderMainRepository.findByOrderStatusAndPayDeadlineBeforeAndParentOrderNoIsNull(
                OrderStatus.UNPAID.getValue(), Instant.now());
        int closed = 0;
        for (OrderMain order : expired) {
            if (lifecycleService.closeTimedOut(order.getId()) == CloseOutcome.CLOSED) {
                closed++;
            }
        }
        if (closed > 0) {
            log.info("超时关单完成: scanned={} closed={}", expired.size(), closed);
        }
        return closed;
    }
}
