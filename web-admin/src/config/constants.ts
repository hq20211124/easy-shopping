/**
 * 全局枚举与常量
 *
 * 注意（plans/admin-frontend.md 1.7 / 12.4、admin-backend 2.5.2）：
 * 订单状态、支付方式等枚举的最终来源是**数据字典接口** /api/admin/system/dict；
 * 字典接口覆盖前先用本文件的枚举，各端禁止在页面内散落硬编码。
 * TODO(联调)：接入 /admin/system/dict 后由字典下发替换。
 *
 * 枚举取值已对齐 server 实际实现：
 *  - 审核状态 = core-product AuditStatus（0-draft 1-auditing 2-on_shelf 3-rejected）
 *  - 上下架   = core-product ShelfStatus（0-下架 1-上架），接口入参/出参都是数字，不是字符串
 */

/** 订单 8 值状态（admin-backend 4.3 / core-order OrderStatus，全系统唯一定义）：0待付款…7已关闭 */
export const ORDER_STATUS_OPTIONS = [
  { value: 0, label: '待付款', color: 'default' },
  { value: 1, label: '待发货', color: 'processing' },
  { value: 2, label: '待收货', color: 'blue' },
  { value: 3, label: '已完成', color: 'success' },
  { value: 4, label: '退款中', color: 'warning' },
  { value: 5, label: '已退款', color: 'purple' },
  { value: 6, label: '已取消', color: 'default' },
  { value: 7, label: '已关闭', color: 'error' },
] as const;

export type OrderStatusCode = (typeof ORDER_STATUS_OPTIONS)[number]['value'];

/** 商品审核状态（core-product AuditStatus：0-draft 1-auditing 2-on_shelf 3-rejected，禁止自造状态值） */
export const AUDIT_STATUS_OPTIONS = [
  { value: 0, label: '待提交', color: 'default' },
  { value: 1, label: '审核中', color: 'processing' },
  { value: 2, label: '已上架', color: 'success' },
  { value: 3, label: '审核拒绝', color: 'error' },
] as const;

/** 上下架状态（core-product ShelfStatus：0-下架 1-上架；上架前置 = auditStatus=2） */
export const SHELF_STATUS_OPTIONS = [
  { value: 1, label: '已上架', color: 'success' },
  { value: 0, label: '已下架', color: 'default' },
] as const;

/** 支付方式（admin-backend 4.2.1 筛选参数说明 / 2.5.2 字典 pay_type 须含 4-余额） */
export const PAY_TYPE_OPTIONS = [
  { value: 1, label: '支付宝' },
  { value: 2, label: '微信' },
  { value: 3, label: '银行卡(银联)' },
  { value: 4, label: '余额' },
] as const;

/** 售后单状态（admin-backend 4.2.2） */
export const AFTER_SALE_STATUS_OPTIONS = [
  { value: 0, label: '待审核', color: 'processing' },
  { value: 1, label: '已同意', color: 'success' },
  { value: 2, label: '已拒绝', color: 'error' },
  { value: 3, label: '已完成', color: 'default' },
] as const;

/** 数据范围档位（admin-backend 2.3.1；前端仅控制默认筛选项与入口可见性，隔离由后端 SQL 拦截保证） */
export const DATA_SCOPE_OPTIONS = [
  { value: 1, label: '全部数据' },
  { value: 2, label: '本部门' },
  { value: 3, label: '本部门及以下' },
  { value: 4, label: '仅本人' },
  { value: 5, label: '自定义部门' },
  { value: 6, label: '本店铺（商家）' },
] as const;

/** 批量操作单次上限（admin-backend 3.3.3 / 前端 4.1） */
export const BATCH_MAX = 500;
