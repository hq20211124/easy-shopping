package com.inventory.domain;

/**
 * 库存预占单状态。
 *
 * <pre>
 * ACTIVE（预占生效中）──release/超时释放──► RELEASED（已释放，库存回补）
 *        │
 *        └──deduct（确认收货出库）──► DEDUCTED（已出库，终态）
 * RELEASED/DEDUCTED/EXPIRED 均为终态，禁止再流转（释放比扣减更危险：重复释放 = 白送库存）。
 * </pre>
 */
public enum ReservationStatus {

    /** 预占生效中（锁定库存）。 */
    ACTIVE,
    /** 已释放（订单取消/超时关单/预占超时扫描）。 */
    RELEASED,
    /** 已出库（确认收货，locked 扣减）。 */
    DEDUCTED,
    /** 预占超时被扫描任务释放（P0 内存扫，P1 MQ 延迟消息）。 */
    EXPIRED
}
