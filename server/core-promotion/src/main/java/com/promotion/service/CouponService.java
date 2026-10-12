package com.promotion.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.promotion.domain.CouponRecord;
import com.promotion.domain.CouponTemplate;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.web.dto.CouponRecordResponse;
import com.promotion.web.dto.CouponTemplateRequest;
import com.promotion.web.dto.CouponTemplateResponse;
import com.promotion.web.dto.PreviewResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * 优惠券核心服务：领取（防超领/限领）、试算、核销（防重复）、退回、过期翻转、模板管理。
 *
 * <p>并发控制口径：所有会改变"券资源"的写操作均为<b>条件更新（SQL CAS）</b>——
 * 领取 {@code received < limit} 才自增、核销 {@code status=0→1} 才绑定订单，
 * 多实例部署下同样安全（不依赖进程内锁/@Version 重试）。</p>
 */
@Service
public class CouponService {

    private final CouponTemplateRepository templateRepo;
    private final CouponRecordRepository recordRepo;
    private final SnowflakeIdGenerator idGenerator;

    public CouponService(CouponTemplateRepository templateRepo,
                         CouponRecordRepository recordRepo,
                         SnowflakeIdGenerator idGenerator) {
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.idGenerator = idGenerator;
    }

    // ---------- 用户侧 ----------

    /**
     * 领券（防超领三连击）：
     * ① 每人限领预检（count &lt; perLimit）；② 库存预占条件更新（received &lt; total 才自增，并发只成功 N 次）；
     * ③ 落领取记录（同事务，任一步失败整体回滚，不会泄漏预占数）。
     */
    @Transactional
    public CouponRecordResponse receive(Long templateId, long userId) {
        CouponTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + templateId));
        Instant now = Instant.now();
        if (template.getStatus() == null || template.getStatus() != 1) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券已下线");
        }
        if (now.isBefore(template.getStartTime()) || now.isAfter(template.getEndTime())) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券活动未开始或已结束");
        }
        long owned = recordRepo.countByCouponIdAndUserId(templateId, userId);
        if (owned >= template.getPerLimit()) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "已达每人限领数量（每人限领 " + template.getPerLimit() + " 张）");
        }
        int reserved = templateRepo.reserveStock(templateId, now);
        if (reserved == 0) {
            throw new BizException(BizErrorCode.OUT_OF_STOCK, "优惠券已被领完");
        }
        CouponRecord record = new CouponRecord();
        record.setId(idGenerator.nextId());
        record.setCouponId(templateId);
        record.setUserId(userId);
        record.setStatus(CouponRecord.STATUS_AVAILABLE);
        record.setExpireAt(template.getEndTime()); // 有效期取模板结束时间快照
        return toRecordResponse(recordRepo.save(record), template);
    }

    /**
     * 优惠试算：对用户持有的券按订单金额列表求和后计算优惠（满减/无门槛减面额；折扣按 value 折）。
     * 不满足门槛/状态不可用时返回 {@code eligible=false, discount=0}（不抛异常，便于前端展示原因）。
     */
    public PreviewResponse preview(Long recordId, List<BigDecimal> amounts, long userId) {
        CouponRecord record = requireOwnedRecord(recordId, userId);
        if (record.getStatus() != CouponRecord.STATUS_AVAILABLE) {
            return unavailable(record, null, null, null, "券不可用（" + record.statusText() + "）");
        }
        if (record.getExpireAt().isBefore(Instant.now())) {
            return unavailable(record, null, null, null, "券已过期");
        }
        CouponTemplate template = requireTemplate(record.getCouponId());
        if (amounts == null || amounts.isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "amount 不能为空");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            if (amount == null || amount.signum() < 0) {
                throw new BizException(BizErrorCode.PARAM_ERROR, "amount 必须为非负数");
            }
            total = total.add(amount);
        }
        if (total.compareTo(template.getMinAmount()) < 0) {
            return unavailable(record, template, total, BigDecimal.ZERO,
                    "未满足最低消费 " + template.getMinAmount());
        }
        BigDecimal discount = discountOf(template, total);
        return new PreviewResponse(recordId, template.getId(), template.getName(),
                total, discount, total.subtract(discount), true, null);
    }

    /**
     * 核销（available → used + 绑定 orderId；条件更新防并发重复核销）。
     * <p>幂等语义：同一订单重复核销返回成功（订单重试安全）；他订单占用/已过期 → 拒绝。</p>
     */
    @Transactional
    public CouponRecordResponse deduct(Long recordId, Long orderId, Long userId) {
        CouponRecord record = requireRecord(recordId);
        if (userId != null && !record.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权操作他人优惠券");
        }
        Instant now = Instant.now();
        int updated = recordRepo.casUse(recordId, orderId, now,
                CouponRecord.STATUS_AVAILABLE, CouponRecord.STATUS_USED);
        if (updated == 0) {
            CouponRecord current = requireRecord(recordId);
            if (current.getStatus() == CouponRecord.STATUS_USED && orderId.equals(current.getUsedOrderId())) {
                return toRecordResponse(current, requireTemplate(current.getCouponId())); // 同订单重复核销：幂等成功
            }
            if (current.getStatus() == CouponRecord.STATUS_USED) {
                throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券已被其他订单占用");
            }
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券不可核销（" + current.statusText() + "）");
        }
        templateRepo.increaseUsedCount(record.getCouponId(), now);
        CouponRecord updated2 = requireRecord(recordId);
        return toRecordResponse(updated2, requireTemplate(updated2.getCouponId()));
    }

    /** 关单退回（used → available；幂等：已是可用直接成功）。 */
    @Transactional
    public CouponRecordResponse release(Long recordId, Long orderId, Long userId) {
        CouponRecord record = requireRecord(recordId);
        if (userId != null && !record.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权操作他人优惠券");
        }
        if (record.getStatus() == CouponRecord.STATUS_AVAILABLE) {
            return toRecordResponse(record, requireTemplate(record.getCouponId())); // 幂等：未核销无需退回
        }
        if (record.getStatus() == CouponRecord.STATUS_USED
                && orderId != null && !orderId.equals(record.getUsedOrderId())) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                    "券核销绑定的是订单 " + record.getUsedOrderId() + "，与退回订单 " + orderId + " 不一致");
        }
        int updated = recordRepo.casRelease(recordId,
                CouponRecord.STATUS_USED, CouponRecord.STATUS_AVAILABLE);
        if (updated > 0) {
            templateRepo.decreaseUsedCount(record.getCouponId(), Instant.now());
        }
        CouponRecord current = requireRecord(recordId);
        return toRecordResponse(current, requireTemplate(current.getCouponId()));
    }

    /** 我的券列表（status 可选过滤：0可用/1已使用/2已过期）。 */
    public List<CouponRecordResponse> my(long userId, Integer status) {
        List<CouponRecord> records = (status == null)
                ? recordRepo.findByUserIdOrderByReceivedAtDesc(userId)
                : recordRepo.findByUserIdAndStatusOrderByReceivedAtDesc(userId, status);
        return records.stream().map(r -> toRecordResponse(r, templateRepo.findById(r.getCouponId()).orElse(null))).toList();
    }

    /** 过期翻转（定时任务入口）。 */
    @Transactional
    public int expireDue() {
        return recordRepo.expireDue(Instant.now(), CouponRecord.STATUS_AVAILABLE, CouponRecord.STATUS_EXPIRED);
    }

    // ---------- 管理端 ----------

    @Transactional
    public CouponTemplateResponse createTemplate(CouponTemplateRequest request) {
        validate(request, null);
        CouponTemplate template = new CouponTemplate();
        template.setId(idGenerator.nextId());
        apply(template, request);
        templateRepo.save(template);
        return toTemplateResponse(template);
    }

    @Transactional
    public CouponTemplateResponse updateTemplate(Long id, CouponTemplateRequest request) {
        CouponTemplate template = templateRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + id));
        validate(request, template);
        apply(template, request);
        return toTemplateResponse(template); // 事务提交时按 @Version 乐观锁兜底并发覆盖
    }

    @Transactional
    public void deleteTemplate(Long id) {
        CouponTemplate template = templateRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + id));
        if (template.getReceivedCount() != null && template.getReceivedCount() > 0) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "已发放的券模板不可删除，请改为下线");
        }
        templateRepo.delete(template);
    }

    public CouponTemplateResponse getTemplate(Long id) {
        return toTemplateResponse(templateRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + id)));
    }

    public List<CouponTemplateResponse> listTemplates(int pageNum, int pageSize) {
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);
        return templateRepo.findAll(org.springframework.data.domain.PageRequest.of(safePageNum - 1, safePageSize))
                .map(this::toTemplateResponse).getContent();
    }

    public long countTemplates() {
        return templateRepo.count();
    }

    /** 指定用户发放（管理端定向发券；尊重总量，不占每人限领额度）。 */
    @Transactional
    public CouponRecordResponse distribute(Long templateId, long userId) {
        CouponTemplate template = templateRepo.findById(templateId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + templateId));
        Instant now = Instant.now();
        int reserved = templateRepo.reserveStock(templateId, now); // 复用条件更新防超发（含时间窗/状态校验）
        if (reserved == 0) {
            throw new BizException(BizErrorCode.OUT_OF_STOCK, "优惠券不可发放（已领完/未开始/已结束/已下线）");
        }
        CouponRecord record = new CouponRecord();
        record.setId(idGenerator.nextId());
        record.setCouponId(templateId);
        record.setUserId(userId);
        record.setStatus(CouponRecord.STATUS_AVAILABLE);
        record.setExpireAt(template.getEndTime());
        CouponRecord saved = recordRepo.save(record);
        return toRecordResponse(saved, template);
    }

    /** 领取记录（管理端审计）。 */
    public List<CouponRecordResponse> templateRecords(Long templateId) {
        return recordRepo.findByCouponId(templateId).stream()
                .map(r -> toRecordResponse(r, templateRepo.findById(r.getCouponId()).orElse(null)))
                .toList();
    }

    // ---------- 内部 ----------

    private CouponRecord requireRecord(Long recordId) {
        return recordRepo.findById(recordId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "用户券不存在 couponId=" + recordId));
    }

    private CouponRecord requireOwnedRecord(Long recordId, long userId) {
        CouponRecord record = requireRecord(recordId);
        if (!record.getUserId().equals(userId)) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "无权操作他人优惠券");
        }
        return record;
    }

    private CouponTemplate requireTemplate(Long templateId) {
        return templateRepo.findById(templateId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券模板不存在 templateId=" + templateId));
    }

    /** 计算优惠额：满减/无门槛减面额（封顶订单金额）；折扣按 value 折，HALF_UP 两位小数。 */
    private BigDecimal discountOf(CouponTemplate template, BigDecimal total) {
        BigDecimal discount;
        if (template.getType() == CouponTemplate.TYPE_DISCOUNT) {
            // value=8.5 → 优惠 = total × (10-8.5)/10
            discount = total.multiply(BigDecimal.TEN.subtract(template.getValue()))
                    .divide(BigDecimal.TEN, 2, RoundingMode.HALF_UP);
        } else {
            discount = template.getValue(); // 满减/无门槛：减面额
        }
        if (discount.compareTo(total) > 0) {
            discount = total; // 优惠不超过订单金额
        }
        return discount;
    }

    private PreviewResponse unavailable(CouponRecord record, CouponTemplate template,
                                        BigDecimal total, BigDecimal discount, String reason) {
        return new PreviewResponse(record.getId(),
                template == null ? record.getCouponId() : template.getId(),
                template == null ? null : template.getName(),
                total, discount == null ? BigDecimal.ZERO : discount, total, false, reason);
    }

    private void validate(CouponTemplateRequest request, CouponTemplate existing) {
        int type = request.type();
        if (type != CouponTemplate.TYPE_FULL_CUT && type != CouponTemplate.TYPE_DISCOUNT
                && type != CouponTemplate.TYPE_NO_THRESHOLD) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "type 必须为 1满减/2折扣/3无门槛");
        }
        if (type == CouponTemplate.TYPE_DISCOUNT
                && (request.value().compareTo(BigDecimal.TEN) >= 0 || request.value().signum() <= 0)) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "折扣券 value 必须在 (0,10) 之间（如 8.5 表示 85 折）");
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "endTime 必须晚于 startTime");
        }
        if (existing != null && existing.getReceivedCount() != null
                && request.totalCount() < existing.getReceivedCount()) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "totalCount 不能小于已领取数 " + existing.getReceivedCount());
        }
    }

    private void apply(CouponTemplate template, CouponTemplateRequest request) {
        template.setName(request.name());
        template.setType(request.type());
        template.setValue(request.value());
        template.setMinAmount(request.minAmount() == null ? BigDecimal.ZERO : request.minAmount());
        template.setTotalCount(request.totalCount());
        template.setPerLimit(request.perLimit() == null ? 1 : request.perLimit());
        template.setStartTime(request.startTime());
        template.setEndTime(request.endTime());
        template.setStatus(request.status() == null ? 1 : request.status());
    }

    private CouponTemplateResponse toTemplateResponse(CouponTemplate t) {
        return new CouponTemplateResponse(t.getId(), t.getName(), t.getType(), t.getValue(),
                t.getMinAmount(), t.getTotalCount(), t.getReceivedCount(), t.getUsedCount(),
                t.getPerLimit(), t.getStartTime(), t.getEndTime(), t.getStatus());
    }

    private CouponRecordResponse toRecordResponse(CouponRecord r, CouponTemplate t) {
        return new CouponRecordResponse(r.getId(), r.getCouponId(), t == null ? null : t.getName(),
                t == null ? null : t.getType(), r.statusText(), r.getExpireAt(), r.getReceivedAt(),
                r.getUsedOrderId(), r.getUsedTime());
    }
}
