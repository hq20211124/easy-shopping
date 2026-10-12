package com.order.client;

import com.easyshopping.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * core-payment REST 实现（真实退款通道）。
 *
 * <p>契约（core-payment {@code PaymentController#refund}）：
 * {@code POST /api/v1/payment/refund} body={orderId, reason}，
 * 幂等（paid → refunding → refunded，重复调用返回同一终态），退款金额为支付单全额。</p>
 *
 * <p><b>失败语义</b>：退款失败返回 {@code false} 并打 ERROR 告警，<b>不抛异常打断关单流程</b>
 * （订单已关是不可逆的资金事实，退款由对账兜底补偿：admin-backend 13.2.2）。
 * 调用方据此决定是否发布 {@code order.refund.applied} 事件。</p>
 */
@Component
public class RestPaymentClient implements PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(RestPaymentClient.class);

    /** core-payment 退款路径（注意：单数 {@code /payment/}，非旧注释里的 {@code /payments/}）。 */
    static final String REFUND_URI = "/api/v1/payment/refund";

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> REFUND_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    @Autowired
    public RestPaymentClient(@Value("${service.urls.core-payment}") String baseUrl) {
        this(ClientSupport.client(baseUrl));
    }

    /** 测试接缝：注入 mock RestClient 以断言真实退款的 URI 与请求体。 */
    public RestPaymentClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public boolean refund(Long orderId, BigDecimal amount, String reason) {
        // 请求体不含 amount：退款金额由支付单全额决定（core-payment P0 不支持部分退款）
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("orderId", orderId);
        request.put("reason", reason);
        try {
            ApiResponse<Map<String, Object>> response = restClient.post()
                    .uri(REFUND_URI)
                    .body(request)
                    .retrieve()
                    .body(REFUND_TYPE);
            if (response == null || response.code() != 0) {
                log.error("[退款告警] core-payment 拒绝退款，退款待对账补偿: orderId={} amount={} reason={} resp={}",
                        orderId, amount, reason, response == null ? "null" : response.code() + "/" + response.msg());
                return false;
            }
            log.info("退款已被支付域受理: orderId={} amount={} reason={} resp={}", orderId, amount, reason, response.data());
            return true;
        } catch (Exception e) {
            log.error("[退款告警] 退款调用失败，退款待对账补偿: orderId={} amount={} reason={}",
                    orderId, amount, reason, e);
            return false;
        }
    }
}