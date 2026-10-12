package com.payment.client;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * 订单服务 RestClient 实现（静态地址，P0 无 Nacos；超时 连接1s/读3s，见 DEV.md 3）。
 *
 * <p><b>金额字段口径</b>：定额一律取 {@code payAmount}（应付 = 商品总额 - 优惠 + 运费），
 * {@code totalAmount}（优惠前）仅作为参考字段透传，<b>禁止</b>回退到它——
 * 回退等于按商品总额收款，有券订单会多收一个券面额（P0-1）。</p>
 */
@Component
public class RestOrderClient implements OrderClient {

    private static final Logger log = LoggerFactory.getLogger(RestOrderClient.class);

    private final RestClient restClient;

    public RestOrderClient(@Value("${service.urls.core-order}") String baseUrl,
                           @Value("${service.rest.connect-timeout-ms:1000}") int connectTimeoutMs,
                           @Value("${service.rest.read-timeout-ms:3000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public OrderSnapshot getOrder(Long orderId) {
        // internal 端点：服务间调用不带 X-User-Id，买家端 /api/v1/orders/{id} 会按 1002 拦下（DEV.md 4.5）
        JsonNode body = get("/api/v1/internal/orders/{orderId}", orderId);
        JsonNode data = expectOk(body, orderId, "反查订单");
        return toSnapshot(data);
    }

    @Override
    public void markPaid(Long orderId, Long paymentId, BigDecimal amount) {
        // TODO(core-order 契约)：若 core-order 状态机由 order.paid MQ 事件驱动（plans/admin-backend.md 4.4.1），
        // P1 删除本 HTTP 通道改发事件；P0 先以 HTTP 同步通知 + 对账任务兜底。
        try {
            restClient.post()
                    .uri("/api/v1/internal/orders/{orderId}/paid", orderId)
                    .body(new MarkPaidRequest(orderId, paymentId, amount))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            log.error("markPaid HTTP 失败 orderId={} paymentId={} status={} body={}",
                    orderId, paymentId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务支付通知失败 orderId=" + orderId);
        }
    }

    private JsonNode get(String path, Object... uriVars) {
        try {
            return restClient.get().uri(path, uriVars).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在 orderId=" + uriVars[0]);
            }
            log.error("订单服务不可用 path={} status={} body={}", path, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务不可用");
        }
    }

    /** 校验信封 code==0 并取 data；4001 → 订单不存在。 */
    private JsonNode expectOk(JsonNode body, Long orderId, String action) {
        if (body == null) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务无响应（" + action + "）");
        }
        int code = body.path("code").asInt(-1);
        if (code == 0) {
            return body.path("data");
        }
        if (code == BizErrorCode.ORDER_NOT_FOUND.getCode()) {
            throw new BizException(BizErrorCode.ORDER_NOT_FOUND, "订单不存在 orderId=" + orderId);
        }
        throw new BizException(BizErrorCode.SYSTEM_ERROR,
                "订单服务返回错误（" + action + "）code=" + code + " msg=" + body.path("msg").asText());
    }

    /** 字段解析：金额<b>只认 payAmount（应付）</b>，缺失即报错而非回退到 totalAmount（防多收费）。 */
    private OrderSnapshot toSnapshot(JsonNode data) {
        Long orderId = firstLong(data, "orderId", "id");
        Long userId = firstLong(data, "userId");
        BigDecimal totalAmount = firstDecimal(data, "totalAmount"); // 仅参考，不参与定额
        BigDecimal payAmount = firstDecimal(data, "payAmount", "amount");
        Integer status = data.hasNonNull("status") ? data.get("status").asInt() : null;
        Instant payDeadline = firstInstant(data, "payDeadline");
        if (orderId == null || payAmount == null) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR, "订单服务响应缺少必要字段 orderId/payAmount");
        }
        return new OrderSnapshot(orderId, userId, totalAmount, payAmount, status, payDeadline);
    }

    private Long firstLong(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                return node.get(f).asLong();
            }
        }
        return null;
    }

    private BigDecimal firstDecimal(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                return new BigDecimal(node.get(f).asText());
            }
        }
        return null;
    }

    private Instant firstInstant(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                JsonNode v = node.get(f);
                if (v.isNumber()) {
                    return Instant.ofEpochMilli(v.asLong());
                }
                try {
                    return Instant.parse(v.asText());
                } catch (Exception ignore) {
                    return null; // 格式未知不阻断主流程（payDeadline 仅用于预支付过期时间兜底）
                }
            }
        }
        return null;
    }

    /** POST /api/v1/orders/{orderId}/paid 请求体（字段以 core-order 定稿为准，P0 先传核心三元组）。 */
    private record MarkPaidRequest(Long orderId, Long paymentId, BigDecimal amount) {
    }
}
