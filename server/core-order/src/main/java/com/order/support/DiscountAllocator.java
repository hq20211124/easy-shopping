package com.order.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 优惠金额分摊器（admin-backend 4.4.2：优惠按金额比例分摊，尾差归集，保证 Σ分摊 = 总优惠）。
 *
 * <p>子单优惠 = 总优惠 ×（子单商品金额 / 父单商品金额），2 位小数 HALF_UP；
 * 四舍五入产生的<b>尾差归集到首单</b>（本实现按店铺 ID 升序取第一单；plans 原文为「金额最大的子单」，
 * 本 P0 按任务口径取首单，差异见模块 README）。禁止把父单整额优惠重复扣在每个子单上
 * （v1.0 每单都减全额，N 个店铺会多减 N-1 次）。</p>
 */
public final class DiscountAllocator {

    private static final int SCALE = 2;
    /** 比例中间精度（12 位），避免整除截断误差放大。 */
    private static final int RATIO_SCALE = 12;

    private DiscountAllocator() {
    }

    /**
     * 按 {@code bases} 比例分摊 {@code totalDiscount}，尾差归入第一条。
     *
     * @param bases         各单/行的商品金额（同为 0 时全部分摊 0）
     * @param totalDiscount 需分摊的总优惠（非负）
     * @return 与 bases 等长的分摊结果，Σ结果 == totalDiscount
     */
    public static List<BigDecimal> allocate(List<BigDecimal> bases, BigDecimal totalDiscount) {
        BigDecimal baseSum = bases.stream().map(BigDecimal.class::cast)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> result = new ArrayList<>(bases.size());
        if (totalDiscount == null || totalDiscount.signum() == 0 || baseSum.signum() == 0) {
            for (int i = 0; i < bases.size(); i++) {
                result.add(BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP));
            }
            return result;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (BigDecimal base : bases) {
            BigDecimal share = base.divide(baseSum, RATIO_SCALE, RoundingMode.HALF_UP)
                    .multiply(totalDiscount)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            result.add(share);
            allocated = allocated.add(share);
        }
        // 尾差归集到首条，保证 Σ分摊 == 总优惠（金额校验规则 5）
        BigDecimal diff = totalDiscount.setScale(SCALE, RoundingMode.HALF_UP).subtract(allocated);
        if (diff.signum() != 0) {
            result.set(0, result.get(0).add(diff));
        }
        return result;
    }
}
