package com.promotion.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * {@link DeductedOrderVerifier} 的 REST 实现：反查 core-order internal 订单快照
 * {@code GET /api/v1/internal/orders/{orderId}}（服务间直连 :8086，不走网关）。
 *
 * <p>状态口径为 AGENTS.md 唯一事实来源的 0 基 8 值：6 已取消 / 7 已关闭 → {@link Outcome#ORDER_GONE}；
 * 其余（0 待付款 / 1 待发货 / …）视为订单有效；响应码 4001（订单不存在）→ {@code ORDER_GONE}。</p>
 *
 * <p><b>失败即 UNKNOWN</b>：core-order 不可达/超时/响应异常一律保守不回收——
 * 误回收 = 把已消费券凭空退给用户（资损 + 薅羊毛），漏回收 = 人工可查的对账项。</p>
 */
@Component
public class RestDeductedOrderVerifier implements DeductedOrderVerifier {

    private static final Logger log = LoggerFactory.getLogger(RestDeductedOrderVerifier.class);

    /** 订单状态：6 已取消（AGENTS.md 0 基 8 值）。 */
    private static final int ORDER_STATUS_CANCELLED = 6;
    /** 订单状态：7 已关闭。 */
    private static final int ORDER_STATUS_CLOSED = 7;
    /** core-order 业务码 4001：订单不存在。 */
    private static final int ORDER_NOT_FOUND_CODE = 4001;

    private final RestClient restClient;

    public RestDeductedOrderVerifier(@Value("${service.urls.core-order:http://localhost:8086}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300); // 对账任务不能被下游拖死
        factory.setReadTimeout(1000);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Outcome verify(Long orderId) {
        if (orderId == null) {
            return Outcome.UNKNOWN;
        }
        try {
            JsonNode body = restClient.get()
                    .uri("/api/v1/internal/orders/{orderId}", orderId)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                return Outcome.UNKNOWN;
            }
            int code = body.path("code").asInt(-1);
            if (code == ORDER_NOT_FOUND_CODE) {
                return Outcome.ORDER_GONE;
            }
            if (code != 0) {
                log.warn("[coupon-deduct-reclaim] 订单快照返回业务失败 code={} orderId={}", code, orderId);
                return Outcome.UNKNOWN;
            }
            JsonNode status = body.path("data").path("status");
            if (!status.isNumber()) {
                return Outcome.UNKNOWN; // 契约漂移（字段改名）→ 保守不回收
            }
            int value = status.asInt();
            return (value == ORDER_STATUS_CANCELLED || value == ORDER_STATUS_CLOSED)
                    ? Outcome.ORDER_GONE : Outcome.ORDER_ALIVE;
        } catch (Exception e) {
            log.warn("[coupon-deduct-reclaim] 订单存活校验失败，按 UNKNOWN 处理（不回收）: orderId={} err={}",
                    orderId, e.getMessage());
            return Outcome.UNKNOWN;
        }
    }
}
