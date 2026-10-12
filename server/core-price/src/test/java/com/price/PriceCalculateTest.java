package com.price;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.price.service.PriceService;
import com.price.service.PriceService.CalculateResponse;
import com.price.web.PriceDtos.CalculateInput;
import com.price.web.PriceDtos.CalculateItemInput;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 下单试算测试（mem H2）：金额精度（BigDecimal 2 位 HALF_UP，禁 Double —— DEV.md 4.3）、
 * 优惠券 P0 降级（core-promotion 未就绪 discount=0）、异常路径（SKU 缺失 3001 / 数量非法 1001）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-price-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        // 契约对齐后主配置已启用优惠试算（/api/v1/coupons/preview）；本用例覆盖「未配置 → 降级 + note 提示」路径
        "app.price.coupon-preview-url="
})
class PriceCalculateTest {

    @Autowired
    private PriceService priceService;

    @Test
    void calculateMultipliesAndSumsWithTwoDecimalScale() {
        priceService.upsert(101L, new BigDecimal("19.90"), new BigDecimal("29.90"));
        priceService.upsert(102L, new BigDecimal("12.34"), null); // marketPrice 缺省 = price

        CalculateResponse resp = priceService.calculate(new CalculateInput(List.of(
                new CalculateItemInput(101L, 3),
                new CalculateItemInput(102L, 7)), null));

        // 3 × 19.90 = 59.70；7 × 12.34 = 86.38；合计 146.08
        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).subtotal()).isEqualByComparingTo("59.70");
        assertThat(resp.items().get(1).subtotal()).isEqualByComparingTo("86.38");
        assertThat(resp.totalQuantity()).isEqualTo(10);
        assertThat(resp.totalAmount()).isEqualByComparingTo("146.08");
        assertThat(resp.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(resp.payableAmount()).isEqualByComparingTo("146.08");
        // 精度口径：所有金额 scale=2（禁 Double）
        assertThat(resp.totalAmount().scale()).isEqualTo(2);
        assertThat(resp.payableAmount().scale()).isEqualTo(2);
        // marketPrice 缺省 = price
        assertThat(resp.items().get(1).marketPrice()).isEqualByComparingTo("12.34");
    }

    @Test
    void calculateWithCouponId_degradesToZeroDiscount_whenPromotionNotReady() {
        priceService.upsert(201L, new BigDecimal("100.00"), null);
        CalculateResponse resp = priceService.calculate(new CalculateInput(
                List.of(new CalculateItemInput(201L, 2)), 999L));
        // P0：core-promotion 未配置试算地址 → discount=0，note 说明，applied=false
        assertThat(resp.discountAmount()).isEqualByComparingTo("0.00");
        assertThat(resp.payableAmount()).isEqualByComparingTo("200.00");
        assertThat(resp.coupon().applied()).isFalse();
        assertThat(resp.coupon().note()).contains("core-promotion");
    }

    @Test
    void missingSkuAndIllegalQuantityThrowBizException() {
        assertThatThrownBy(() -> priceService.calculate(new CalculateInput(
                List.of(new CalculateItemInput(404L, 1)), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(BizErrorCode.PRODUCT_NOT_FOUND.getCode()));

        priceService.upsert(301L, new BigDecimal("5.00"), null);
        assertThatThrownBy(() -> priceService.calculate(new CalculateInput(
                List.of(new CalculateItemInput(301L, 0)), null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(BizErrorCode.PARAM_ERROR.getCode()));
        assertThatThrownBy(() -> priceService.upsert(302L, new BigDecimal("-1"), null))
                .isInstanceOf(BizException.class);
    }
}
