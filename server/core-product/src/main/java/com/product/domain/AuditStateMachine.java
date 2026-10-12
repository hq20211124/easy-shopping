package com.product.domain;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;

import java.util.Map;
import java.util.Set;

/**
 * 商品审核状态机（流转白名单唯一定义，出处 plans/admin-backend.md 3.3.2 流转规则）。
 *
 * <pre>
 * draft(0) ──提交审核──► auditing(1) ──审核通过──► on_shelf(2)（终态，无审核流出；
 *                                                    下架只改 shelfStatus，不回退审核态）
 *   ▲                        │  ├─驳回(reason 必填)─► rejected(3) ──修改后重新提交──► auditing(1)
 *   └────退回修改(带意见)──────┘
 * </pre>
 *
 * <p>所有审核流转必须经 {@link #validate}；非法流转抛 {@link BizException}(1001)。
 * 并发控制：调用方需配合 SPU 实体 {@code @Version} 乐观锁（3.3.2 规则 6）。</p>
 */
public final class AuditStateMachine {

    /** 流转白名单：当前状态 → 允许到达的状态集合（与 3.3.2 图一一对应）。 */
    private static final Map<Integer, Set<Integer>> TRANSITIONS = Map.of(
            AuditStatus.DRAFT.getValue(), Set.of(AuditStatus.AUDITING.getValue()),
            AuditStatus.AUDITING.getValue(), Set.of(AuditStatus.DRAFT.getValue(),
                    AuditStatus.ON_SHELF.getValue(), AuditStatus.REJECTED.getValue()),
            AuditStatus.REJECTED.getValue(), Set.of(AuditStatus.AUDITING.getValue()));
    // 注意：ON_SHELF(2) 不作为 key 出现 —— 审核通过即审核终态，禁止再流转（下架走 shelfStatus）

    private AuditStateMachine() {
    }

    /** 校验 from → to 是否合法，非法则抛 BizException(1001)。同值流转视为非法（幂等由 Service 层短路）。 */
    public static void validate(int from, int to) {
        if (from == to) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "商品已处于状态 " + AuditStatus.of(from) + "，无需重复流转");
        }
        Set<Integer> allowed = TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "审核状态不允许从 " + AuditStatus.of(from) + " 变更为 " + AuditStatus.of(to));
        }
    }
}
