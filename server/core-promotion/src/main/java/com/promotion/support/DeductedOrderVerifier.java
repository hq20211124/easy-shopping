package com.promotion.support;

/**
 * 核销订单存活校验（P0-G 兜底回收用）。
 *
 * <p>券核销（下单）与订单落库/支付成功是跨服务两步，订单最终失败/取消/关闭而 core-order 未退券时，
 * 券会永久停留 used。回收前必须回查订单确认真伪，否则会把已支付订单真正消费掉的券也退给用户。</p>
 *
 * <p>实现见 {@link RestDeductedOrderVerifier}（HTTP 反查 core-order internal 快照端点）。
 * 本接口是函数式接口，便于测试注入。</p>
 */
@FunctionalInterface
public interface DeductedOrderVerifier {

    /** 校验结论。 */
    enum Outcome {
        /** 订单仍有效（已支付/待付款等一切未取消未关闭状态）——券确实被消费，不得回收。 */
        ORDER_ALIVE,
        /** 订单不存在或已取消/已关闭——核销未落地，可回收。 */
        ORDER_GONE,
        /** 查不到结论（core-order 不可达/响应异常）——保守不回收，宁可不退不误退。 */
        UNKNOWN
    }

    /**
     * @param orderId 券核销时绑定的订单 ID
     * @return 订单存活结论；实现<b>不应抛异常</b>，异常一律按 {@link Outcome#UNKNOWN} 处理
     */
    Outcome verify(Long orderId);
}
