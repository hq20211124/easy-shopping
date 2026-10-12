package com.order.support;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 优惠金额分摊器（admin-backend 4.4.2：优惠按金额比例分摊，尾差归集，保证 Σ分摊 = 总优惠）。
 *
 * <p>子单优惠 = 总优惠 ×（子单商品金额 / 父单商品金额），2 位小数 HALF_UP；
 * 四舍五入产生的<b>尾差归集到金额最大的那一单</b>（并列时取<b>原顺序首个</b>，保证确定性）。
 * 归到最大单的理由：分摊基数越大，同样 1 分钱尾差造成的<b>相对误差</b>越小；
 * 若归到金额极小的子单（如 0.01 元的店铺），该单优惠会被尾差抹平、结算偏差被放大 N 倍。
 * 禁止把父单整额优惠重复扣在每个子单上（v1.0 每单都减全额，N 个店铺会多减 N-1 次）。</p>
 *
 * <p><b>不变式</b>：返回值与入参等长、全部非负、Σ结果 == {@code totalDiscount}（2 位 HALF_UP）。
 * 任何可能破坏不变式的入参（空基数却带非零优惠、负数/空基数）都在此直接拒绝，不留到调用方。</p>
 */
public final class DiscountAllocator {

    private static final int SCALE = 2;
    /** 比例中间精度（12 位），避免整除截断误差放大。 */
    private static final int RATIO_SCALE = 12;

    private DiscountAllocator() {
    }

    /**
     * 按 {@code bases} 比例分摊 {@code totalDiscount}，尾差归入<b>基数最大的条目</b>。
     *
     * @param bases         各单/行的商品金额（同为 0 且无优惠时全部分摊 0；不允许负数与 null）
     * @param totalDiscount 需分摊的总优惠（非负）
     * @return 与 bases 等长的分摊结果，Σ结果 == totalDiscount
     * @throws BizException 1001 PARAM_ERROR：bases 含 null/负数、totalDiscount 为负、
     *         或 bases 为空/合计为 0 却带非零优惠（此时无法保证 Σ分摊 = 总优惠，
     *         宁可按参数错误拒绝，也不产出静默错账的分摊结果）
     */
    public static List<BigDecimal> allocate(List<BigDecimal> bases, BigDecimal totalDiscount) {
        if (bases == null || bases.isEmpty()) {
            if (totalDiscount != null && totalDiscount.signum() != 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "分摊基数为空但总优惠非零，无法保证 Σ分摊 = 总优惠: totalDiscount=" + totalDiscount);
            }
            return new ArrayList<>();
        }
        for (int i = 0; i < bases.size(); i++) {
            BigDecimal base = bases.get(i);
            if (base == null) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "分摊基数不能为 null: index=" + i);
            }
            if (base.signum() < 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "分摊基数不能为负数: index=" + i + " base=" + base);
            }
        }
        if (totalDiscount != null && totalDiscount.signum() < 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "总优惠不能为负数: totalDiscount=" + totalDiscount);
        }

        BigDecimal baseSum = bases.stream().map(BigDecimal.class::cast)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (baseSum.signum() == 0 && totalDiscount != null && totalDiscount.signum() != 0) {
            // 基数全为 0 却要分摊非零优惠：无论归到哪一条都会破坏 Σ分摊 = 总优惠，直接拒绝
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "分摊基数合计为 0 但总优惠非零，无法保证 Σ分摊 = 总优惠: totalDiscount=" + totalDiscount);
        }
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
        // 尾差归集到基数最大的条目（admin-backend 4.4.2「尾差归入金额最大的子单」），
        // 保证 Σ分摊 == 总优惠（金额校验规则 5）。并列时取原顺序首个 → 多次重试结果稳定。
        BigDecimal diff = totalDiscount.setScale(SCALE, RoundingMode.HALF_UP).subtract(allocated);
        if (diff.signum() != 0) {
            int tailIndex = indexOfMaxBase(bases);
            result.set(tailIndex, result.get(tailIndex).add(diff));
        }
        assertNonNegative(result, totalDiscount);
        return result;
    }

    /** 基数最大的下标；并列取首个（严格大于才替换 → 确定性与原顺序一致）。 */
    private static int indexOfMaxBase(List<BigDecimal> bases) {
        int index = 0;
        for (int i = 1; i < bases.size(); i++) {
            if (bases.get(i).compareTo(bases.get(index)) > 0) {
                index = i;
            }
        }
        return index;
    }

    /** 分摊结果不得为负（否则意味着优惠超过商品额，调用方会算出负实付）。 */
    private static void assertNonNegative(List<BigDecimal> result, BigDecimal totalDiscount) {
        for (BigDecimal share : result) {
            if (share.signum() < 0) {
                throw new BizException(BizErrorCode.SYSTEM_ERROR,
                        "优惠分摊结果为负，请核对入参: totalDiscount=" + totalDiscount + " shares=" + result);
            }
        }
    }
}
