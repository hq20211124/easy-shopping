package com.price.client;

import com.easyshopping.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 优惠券试算客户端（调用 core-promotion 预览接口，<b>只调不查库</b>）。
 *
 * <p><b>契约以 core-promotion 实际实现为准</b>（集成核对 G3 修正）：
 * {@code POST {base}/api/v1/coupons/preview} body={couponId, amount:[金额列表]}
 * → ApiResponse&lt;{discount, finalAmount, eligible, ...}&gt;（PreviewResponse，取 discount）；
 * 超时 1s/3s（DEV.md 3），失败/未配置降级 discount=0。</p>
 *
 * <p>core-promotion 的 preview 是登录态接口（UserContext.requireUserId），服务间直连不经过网关，
 * 因此转发网关注入的 {@code X-User-Id}；缺失时对端返 1002 → 降级 discount=0（不影响主链路试算）。</p>
 *
 * <p>启用配置：{@code app.price.coupon-preview-url=/api/v1/coupons/preview}（application.yml）。</p>
 */
@Component
public class CouponPreviewClient {

    private static final Logger log = LoggerFactory.getLogger(CouponPreviewClient.class);

    private static final ParameterizedTypeReference<ApiResponse<Map<String, Object>>> PREVIEW_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;
    private final String previewPath;

    public CouponPreviewClient(@Value("${service.urls.core-promotion}") String baseUrl,
                               @Value("${app.price.coupon-preview-url:}") String previewPath) {
        this.previewPath = previewPath;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 是否已配置促销服务试算地址（未配置则 P0 本地不算优惠）。 */
    public boolean enabled() {
        return previewPath != null && !previewPath.isBlank();
    }

    /**
     * 优惠券预览：返回可抵扣金额；不可用/失败/未配置均返回 0（容错降级，不影响主链路试算）。
     *
     * @param couponId 用户券记录 ID（preview/deduct 语义，非券模板 ID）
     * @param amounts  参与试算的 {skuId: 商品小计}，映射为对端的 amount 金额列表（按 skuId 升序）
     */
    public BigDecimal previewCouponDiscount(Long couponId, Map<Long, BigDecimal> amounts) {
        if (!enabled() || couponId == null) {
            return BigDecimal.ZERO.setScale(2);
        }
        try {
            List<BigDecimal> amountList = amounts == null ? List.of() : amounts.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
            Map<String, Object> body = Map.of("couponId", couponId, "amount", amountList);
            Long userId = com.easyshopping.common.api.UserContext.userId();
            ApiResponse<Map<String, Object>> resp = restClient.post()
                    .uri(previewPath.startsWith("/") ? previewPath : "/" + previewPath)
                    .headers(headers -> {
                        if (userId != null) {
                            headers.set(com.easyshopping.common.api.UserContext.HEADER_USER_ID,
                                    String.valueOf(userId));
                        }
                    })
                    .body(body)
                    .retrieve()
                    .body(PREVIEW_TYPE);
            if (resp == null || resp.data() == null || resp.data().get("discount") == null) {
                return BigDecimal.ZERO.setScale(2);
            }
            return new BigDecimal(resp.data().get("discount").toString()).max(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("[coupon-preview] 优惠券试算失败（降级 discount=0）: couponId={} err={}", couponId, e.getMessage());
            return BigDecimal.ZERO.setScale(2);
        }
    }

    /** 未配置时给响应 note 提示（P0 说明文案）。 */
    public String notConfiguredNote() {
        return "P0 未配置 core-promotion 试算地址（app.price.coupon-preview-url），优惠券暂不生效";
    }
}
