package com.product.domain;

/**
 * 商品审核状态（4 值，对外唯一真相）。
 *
 * <p>出处：plans/admin-backend.md 3.3.2「三张表的状态映射」——
 * {@code admin_product_spu.audit_status}：0-draft、1-auditing、2-on_shelf、3-rejected，
 * 与前端 4.3 严格一致，前端/其他服务禁止自造状态。</p>
 */
public enum AuditStatus {

    /** 0 草稿（含「退回修改」后回到可编辑状态） */
    DRAFT(0),
    /** 1 审核中（复审只是 auditing 的内部环节，不产生新状态） */
    AUDITING(1),
    /** 2 审核通过（= 已上架审核态；是否实际在架另见 shelfStatus） */
    ON_SHELF(2),
    /** 3 审核拒绝（audit_remark 必填驳回原因） */
    REJECTED(3);

    private final int value;

    AuditStatus(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    /** 由入参 int 转枚举，未知值抛 IllegalArgumentException（全局异常兜底 1001/9999 前在 Controller 校验）。 */
    public static AuditStatus of(int value) {
        for (AuditStatus s : values()) {
            if (s.value == value) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知审核状态: " + value);
    }
}
