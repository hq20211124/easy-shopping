package com.order.client;

import java.math.BigDecimal;

/**
 * core-promotion 客户端：优惠券试算 + 核销（下单优惠金额来源）+ 关单退券。
 *
 * <p><b>契约以 core-promotion 实际实现为准</b>（集成核对 G3 对齐）：
 * <ul>
 *   <li>{@code POST /api/v1/coupons/preview} body={couponId, amount:[金额列表]} → data{discount, ...}（试算）</li>
 *   <li>{@code POST /api/v1/coupons/deduct} body={couponId, orderId} → data{...}（核销并绑定订单）</li>
 *   <li>{@code POST /api/v1/coupons/release} body={couponId, orderId} → data{...}（关单退回 used → available，幂等）</li>
 * </ul>
 * 三接口均为登录态（core-promotion 读 X-User-Id），服务间直连不经网关，由实现类转发身份头。</p>
 */
public interface PromotionClient {

    /**
     * 试算 + 核销优惠券，返回本单优惠金额。
     *
     * @param couponId   用户券记录 ID（preview/deduct 语义，非券模板 ID）
     * @param userId     下单用户（用于转发 X-User-Id 身份头）
     * @param orderId    父订单 ID（核销时绑定，一券一单）
     * @param goodsAmount 商品总额（试算金额列表，P0 单元素列表）
     * @return 优惠金额；试算不可用 / 核销失败 / 服务不可达 → 0（P0 降级原价成交）
     */
    BigDecimal deductCoupon(Long couponId, Long userId, Long orderId, BigDecimal goodsAmount);

    /**
     * 关单退券（used → available，幂等）：订单取消 / 超时关单 / 后台关闭时把用户券退回。
     *
     * @param couponId 用户券记录 ID（与 {@link #deductCoupon} 同一口径）
     * @param userId   下单用户（转发 X-User-Id；promotion 侧校验归属）
     * @param orderId  父订单 ID（promotion 侧校验与核销绑定订单一致，防误退他单占用的券）
     * @return {@code true}=券已退回（或此前已是可用/非本单占用 → 幂等成功）；{@code false}=未退回（需对账补偿）
     */
    boolean releaseCoupon(Long couponId, Long userId, Long orderId);
}