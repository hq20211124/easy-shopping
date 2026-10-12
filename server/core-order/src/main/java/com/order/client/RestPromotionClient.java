package com.order.client;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * core-promotion REST 实现（契约以 core-promotion 实际实现为准，集成核对 G3 修正）。
 *
 * <p>流程：① {@code POST /api/v1/coupons/preview {couponId, amount[]}} 试算优惠金额 →
 * ② {@code POST /api/v1/coupons/deduct {couponId, orderId}} 核销并绑定订单。
 * 两步都要求优惠金额 &gt; 0 才核销；任一步失败按 P0 降级口径本单不折扣（WARN 日志），
 * 保证「优惠 = 核销」一致（不会享受了优惠却没核销）。</p>
 *
 * <p><b>TODO(P1)</b>：核销成功后若订单持久化失败，券停留在已核销状态——P1 需补
 * {@code /api/v1/coupons/release} 补偿（Saga/本地消息表），见 core-order README §3。</p>
 */
@Component
public class RestPromotionClient implements PromotionClient {

    private static final Logger log = LoggerFactory.getLogger(RestPromotionClient.class);

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> PREVIEW_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public RestPromotionClient(@Value("${service.urls.core-promotion}") String baseUrl) {
        this.restClient = ClientSupport.client(baseUrl);
    }

    @Override
    public BigDecimal deductCoupon(Long couponId, Long userId, Long orderId, BigDecimal goodsAmount) {
        BigDecimal discount = preview(couponId, userId, goodsAmount);
        if (discount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        try {
            restClient.post()
                    .uri("/api/v1/coupons/deduct")
                    .headers(headers -> forwardUser(headers, userId))
                    .body(Map.of("couponId", couponId, "orderId", orderId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Object>>() {
                    });
            return discount;
        } catch (Exception e) {
            // 核销失败 → 不折扣（避免享受优惠但券未核销），下单链路继续按原价成交
            log.warn("[promotion-degrade] 优惠券核销失败，本单不折扣: couponId={} orderId={} err={}",
                    couponId, orderId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /** 试算（G3 契约：amount = 本单商品金额列表；失败/不可达降级 discount=0）。 */
    private BigDecimal preview(Long couponId, Long userId, BigDecimal goodsAmount) {
        try {
            ApiResponse<Map<String, Object>> response = restClient.post()
                    .uri("/api/v1/coupons/preview")
                    .headers(headers -> forwardUser(headers, userId))
                    .body(Map.of("couponId", couponId,
                            "amount", List.of(goodsAmount == null ? BigDecimal.ZERO : goodsAmount)))
                    .retrieve()
                    .body(PREVIEW_TYPE);
            Map<String, Object> data = ClientSupport.unwrap(response, "core-promotion");
            Object discount = data == null ? null : data.get("discount");
            if (discount == null) {
                return BigDecimal.ZERO;
            }
            return new BigDecimal(discount.toString()).max(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("[promotion-degrade] 优惠券试算不可用，本单不折扣: couponId={} userId={} err={}",
                    couponId, userId, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /** 服务间直连不经网关，手动转发下单用户身份（core-promotion preview 为登录态接口）。 */
    private static void forwardUser(HttpHeaders headers, Long userId) {
        if (userId != null) {
            headers.set(UserContext.HEADER_USER_ID, String.valueOf(userId));
        }
    }
}
