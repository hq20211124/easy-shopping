package com.order.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * core-payment REST 实现（P0 占位）。
 *
 * <p><b>TODO(core-payment)</b>：支付域退款契约未定稿，P0 仅记录结构化日志（含 orderId/金额/原因），
 * 真实退款由 core-payment 的 {@code POST /api/v1/payments/refund} 承接；
 * 调用失败必须告警并由对账任务补偿（admin-backend 13.2.2），禁止手工改库存/订单表。</p>
 */
@Component
public class RestPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(RestPaymentClient.class);

    private final RestClient restClient;

    public RestPaymentClient(@Value("${service.urls.core-payment}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public void refund(Long orderId, BigDecimal amount, String reason) {
        // TODO(core-payment)：契约就绪后放开真实调用：
        // restClient.post().uri("/api/v1/payments/refund")
        //         .body(Map.of("orderId", orderId, "amount", amount, "reason", reason))
        //         .retrieve().body(...);
        log.warn("[payment-refund-todo] 需原路退款（core-payment 契约未定稿，P0 仅登记）: orderId={} amount={} reason={}",
                orderId, amount, reason);
    }
}
