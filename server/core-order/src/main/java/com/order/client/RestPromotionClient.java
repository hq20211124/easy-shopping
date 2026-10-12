package com.order.client;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>关单退券：③ {@code POST /api/v1/coupons/release {couponId, orderId}}（used → available，幂等）。
 * 订单取消 / 超时关单 / 后台关闭时由 {@code OrderLifecycleService} 调用（此前三条关单路径只释放库存、
 * 从不退券，用户未付款被关单即永久损失一张券）。</p>
 *
 * <p><b>TODO(P1)</b>：下单核销成功后若订单持久化失败，券停留在已核销状态——P1 补 Saga/本地消息表
 * 补偿（见 core-order README §3）。关单退券已按「失败只告警、不回滚关单」口径落地。</p>
 */
@Component
public class RestPromotionClient implements PromotionClient {

    private static final Logger log = LoggerFactory.getLogger(RestPromotionClient.class);

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> PREVIEW_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    @Autowired
    public RestPromotionClient(@Value("${service.urls.core-promotion}") String baseUrl) {
        this(ClientSupport.client(baseUrl));
    }

    /** 测试接缝：注入 mock RestClient 以断言真实接口的路径与请求体。 */
    public RestPromotionClient(RestClient restClient) {
        this.restClient = restClient;
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

    /**
     * 关单退券（used → available，幂等）。失败返回 false，由调用方告警 + 对账兜底，不得回滚关单。
     *
     * <p>走买家端 {@code /api/v1/coupons/release} 并转发 {@code X-User-Id}（与本类 {@link #deductCoupon} 同一口径）：
     * core-promotion 强制校验券主归属，转发订单用户即可通过。core-promotion 另有服务间端点
     * {@code POST /api/v1/internal/coupons/{id}/release}（需 {@code X-Internal-Token} 共享密钥，
     * P0 默认留空 = fail-closed），待其密钥配置落地后再切换（P1）。</p>
     */
    @Override
    public boolean releaseCoupon(Long couponId, Long userId, Long orderId) {
        try {
            ApiResponse<Object> response = restClient.post()
                    .uri("/api/v1/coupons/release")
                    .headers(headers -> forwardUser(headers, userId))
                    .body(Map.of("couponId", couponId, "orderId", orderId))
                    .retrieve()
                    .body(new ParameterizedTypeReference<ApiResponse<Object>>() {
                    });
            if (response == null || response.code() != 0) {
                log.error("[退券告警] core-promotion 拒绝退券，券待对账补偿: couponId={} orderId={} resp={}",
                        couponId, orderId, response == null ? "null" : response.code() + "/" + response.msg());
                return false;
            }
            log.info("优惠券已退回: couponId={} orderId={}", couponId, orderId);
            return true;
        } catch (Exception e) {
            log.error("[退券告警] 退券调用失败，券待对账补偿: couponId={} orderId={}", couponId, orderId, e);
            return false;
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
