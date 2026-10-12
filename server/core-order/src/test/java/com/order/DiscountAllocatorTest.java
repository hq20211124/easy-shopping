package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.order.support.DiscountAllocator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 优惠分摊器测试（审计 P0-C：尾差必须归集到<b>金额最大</b>的子单）。
 *
 * <p>plans/admin-backend.md 4.4.2 原文：「子单优惠 = 父单优惠 ×（子单商品金额 / 父单商品金额），
 * 尾差归入金额最大的子单」。修复前实现取「首单（店铺 ID 升序）」，
 * 分摊基数极不均衡时尾差会落到不该承担的子单上（相对误差被放大 N 倍）。</p>
 */
class DiscountAllocatorTest {

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void 尾差归集到金额最大的子单_最大单不是首单() {
        // 修复前：尾差 +0.01 会落到 index0（0.01 元的店铺），该店被尾差抹平、最大单反而少 1 分
        List<BigDecimal> bases = List.of(new BigDecimal("0.01"), new BigDecimal("100.00"), new BigDecimal("0.01"));

        List<BigDecimal> shares = DiscountAllocator.allocate(bases, new BigDecimal("50.00"));

        assertThat(shares.get(0)).isEqualByComparingTo("0.00");
        assertThat(shares.get(1)).isEqualByComparingTo("50.00"); // 尾差落在金额最大的子单
        assertThat(shares.get(2)).isEqualByComparingTo("0.00");
        assertThat(sum(shares)).isEqualByComparingTo("50.00"); // Σ分摊 == 总优惠 不变量不变
    }

    @Test
    void 尾差归集到金额最大的子单_最大单恰好是首单() {
        List<BigDecimal> bases = List.of(new BigDecimal("100.00"), new BigDecimal("0.01"), new BigDecimal("0.01"));

        List<BigDecimal> shares = DiscountAllocator.allocate(bases, new BigDecimal("50.00"));

        assertThat(shares.get(0)).isEqualByComparingTo("50.00");
        assertThat(sum(shares)).isEqualByComparingTo("50.00");
    }

    @Test
    void 负尾差同样归集到金额最大的子单() {
        // 200/401、100/401、101/401 分摊 100 元 → 49.88 + 24.94 + 25.19 = 100.01，尾差 -0.01
        List<BigDecimal> bases = List.of(new BigDecimal("200.00"), new BigDecimal("100.00"), new BigDecimal("101.00"));

        List<BigDecimal> shares = DiscountAllocator.allocate(bases, new BigDecimal("100.00"));

        assertThat(shares).containsExactly(new BigDecimal("49.87"), new BigDecimal("24.94"), new BigDecimal("25.19"));
        assertThat(sum(shares)).isEqualByComparingTo("100.00");
    }

    @Test
    void 无尾差时按比例分摊_Σ恒等于总优惠() {
        List<BigDecimal> shares = DiscountAllocator.allocate(
                List.of(new BigDecimal("100.00"), new BigDecimal("100.00")), new BigDecimal("20.00"));

        assertThat(shares).containsExactly(new BigDecimal("10.00"), new BigDecimal("10.00"));
        assertThat(sum(shares)).isEqualByComparingTo("20.00");
    }

    @Test
    void 并列最大时取原顺序首个_保证确定性() {
        List<BigDecimal> bases = Arrays.asList(new BigDecimal("50.00"), new BigDecimal("50.00"));

        List<BigDecimal> first = DiscountAllocator.allocate(bases, new BigDecimal("33.33"));
        List<BigDecimal> second = DiscountAllocator.allocate(bases, new BigDecimal("33.33"));

        assertThat(first).isEqualTo(second);
        assertThat(first.get(0)).isEqualByComparingTo("16.66"); // 16.67+16.67=33.34 → -0.01 归到首个并列最大
        assertThat(first.get(1)).isEqualByComparingTo("16.67");
        assertThat(sum(first)).isEqualByComparingTo("33.33");
    }

    @Test
    void 空基数且无优惠_返回空列表不抛异常() {
        assertThat(DiscountAllocator.allocate(Collections.emptyList(), BigDecimal.ZERO)).isEmpty();
        assertThat(DiscountAllocator.allocate(Collections.emptyList(), null)).isEmpty();
    }

    @Test
    void 空基数却要分摊优惠_按参数错误拒绝而非IndexOutOfBounds() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(Collections.emptyList(), new BigDecimal("10.00")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.PARAM_ERROR));
    }

    @Test
    void 负数基数_按参数错误拒绝() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(
                List.of(new BigDecimal("-1.00"), new BigDecimal("10.00")), new BigDecimal("5.00")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.PARAM_ERROR));
    }

    @Test
    void null基数_按参数错误拒绝() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(
                Arrays.asList(new BigDecimal("1.00"), null), new BigDecimal("5.00")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.PARAM_ERROR));
    }

    @Test
    void 负数总优惠_按参数错误拒绝() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(
                List.of(new BigDecimal("1.00"), new BigDecimal("1.00")), new BigDecimal("-0.01")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.PARAM_ERROR));
    }

    @Test
    void 基数合计为零却要分摊优惠_按参数错误拒绝() {
        assertThatThrownBy(() -> DiscountAllocator.allocate(
                List.of(BigDecimal.ZERO, BigDecimal.ZERO), new BigDecimal("5.00")))
                .isInstanceOfSatisfying(BizException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(BizErrorCode.PARAM_ERROR));
    }

    @Test
    void 零优惠与零基数_全部分摊零且不抛异常() {
        assertThat(DiscountAllocator.allocate(List.of(new BigDecimal("10.00")), BigDecimal.ZERO))
                .containsExactly(new BigDecimal("0.00"));
        assertThat(DiscountAllocator.allocate(List.of(BigDecimal.ZERO, BigDecimal.ZERO), BigDecimal.ZERO))
                .containsExactly(new BigDecimal("0.00"), new BigDecimal("0.00"));
    }
}