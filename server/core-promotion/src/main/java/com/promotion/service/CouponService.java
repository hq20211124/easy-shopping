package com.promotion.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.promotion.domain.CouponRecord;
import com.promotion.domain.CouponTemplate;
import com.promotion.repo.CouponRecordRepository;
import com.promotion.repo.CouponTemplateRepository;
import com.promotion.support.DeductedOrderVerifier;
import com.promotion.web.dto.CouponRecordResponse;
import com.promotion.web.dto.CouponTemplateRequest;
import com.promotion.web.dto.CouponTemplateResponse;
import com.promotion.web.dto.PreviewResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 优惠券核心服务：领取（防超领/限领）、试算、核销（防重复）、退回、过期翻转、模板管理。
 *
 * <p>并发控制口径：所有会改变"券资源"的写操作均为<b>条件更新（SQL CAS）</b>——
 * 领取：总量走条件更新（{@code received < total} 才自增），每人限领走
 * {@code SELECT ... FOR UPDATE} 领取临界区 + {@code READ_COMMITTED} 下重查计数；
 * 核销 {@code status=0→1} 才绑定订单。
 * 多实例部署下同样安全（不依赖进程内锁/@Version 重试）。服务层的"先查后写"只用于
 * <b>快速失败与错误码细分</b>，不作为并发正确性的依据。</p>
 *
 * <p><b>鉴权口径（P0-F）</b>：买家端 {@code deduct/release} 必须携带券主身份（{@code X-User-Id}），
 * 缺身份或非券主一律 1004；服务端间调用走 {@code /api/v1/internal/coupons/{id}/*}
 * （共享密钥 + 内网），对应 {@link #deductInternal}/{@link #releaseInternal}。
 * 两套鉴权在 HTTP 层分离，服务层用不同方法名区分，避免任一入口漏校验。</p>
 *
 * <p><b>跨服务兜底（P0-G）</b>：核销与订单落库是两步，订单最终失败/关单而 core-order 未退券时，
 * {@link #reclaimUnconfirmedDeducted()} 按「核销超时 + 回查订单确认不存在」把券退回 available。</p>
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    private final CouponTemplateRepository templateRepo;
    private final CouponRecordRepository recordRepo;
    private final SnowflakeIdGenerator idGenerator;
    private final DeductedOrderVerifier orderVerifier;
    private final long deductTimeoutMinutes;

    public CouponService(CouponTemplateRepository templateRepo,
                         CouponRecordRepository recordRepo,
                         SnowflakeIdGenerator idGenerator,
                         DeductedOrderVerifier orderVerifier,
                         @Value("${app.promotion.deduct-timeout-minutes:60}") long deductTimeoutMinutes) {
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.idGenerator = idGenerator;
        this.orderVerifier = orderVerifier;
        this.deductTimeoutMinutes = deductTimeoutMinutes;
    }

    // ---------- 用户侧 ----------

    /**
     * 领券（防超领 + 防限领超发）：
     * ① 加锁读 {@code SELECT ... FOR UPDATE} 开临界区（同一模板的并发领取在此串行）；
     * ② 临界区内判每人限领；③ 库存预占条件更新（received &lt; total 才自增，并发只成功 N 次）；
     * ④ 落领取记录（同事务，任一步失败整体回滚，不会泄漏预占数）。
     *
     * <p><b>原 P1 缺陷（先查后写）</b>：服务层「先 SELECT 计数、再 INSERT」之间没有任何原子性保证——
     * 同一用户并发 N 次领取时 N 个请求都读到 {@code owned=0 < perLimit}，全部通过预检，只要模板总量够
     * 就全部成功，限领 1 的规则被完全突破（薅羊毛经典手法）。修复后「判限领」被移进数据库互斥临界区，
     * 并发正确性的唯一依据是 {@code findByIdForReceive} 持有的模板行锁 + {@code READ_COMMITTED} 隔离级别
     * （RR 下事务快照停留在加锁之前，拿到锁后仍会读到旧计数）。</p>
     *
     * <p><b>被否决的替代方案</b>：{@code UPDATE ... WHERE (SELECT COUNT(*) FROM coupon_record ...) < perLimit}
     * 单语句看似原子，实测不成立——子查询按<b>语句快照</b>求值（求值发生在等待行锁之前），并发请求
     * 一起读到 {@code COUNT=0} 全部放行（H2 实测 16 并发有 10 个同时超发）。详见
     * {@code CouponTemplateRepository#findByIdForReceive} 的类注释。</p>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CouponRecordResponse receive(Long templateId, long userId) {
        // ① 加锁读：领取临界区开始（同一模板的并发领取在此串行）
        CouponTemplate template = templateRepo.findByIdForReceive(templateId)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "优惠券不存在 couponId=" + templateId));
        Instant now = Instant.now();
        if (template.getStatus() == null || template.getStatus() != 1) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券已下线");
        }
        if (now.isBefore(template.getStartTime()) || now.isAfter(template.getEndTime())) {
            throw new BizException(BizErrorCode.ORDER_STATUS_ERROR, "优惠券活动未开始或已结束");
        }
        // ② 临界区内判限领：计数语句在拿到锁之后才开始，读到的必然是前序并发事务已提交后的真实持券数
        int perLimit = perLimitOf(template);
        long owned = recordRepo.countByCouponIdAndUserId(templateId, userId);
        if (owned >= perLimit) {
            throw perLimitExceeded(perLimit);
        }
        // 权威判定：库存 + 时间窗 + 生效状态在这条条件更新的 WHERE 里原子完成
        int reserved = templateRepo.reserveStock(templateId, now);
        if (reserved == 0) {
            throw new BizException(BizErrorCode.OUT_OF_STOCK, "优惠券已被领完");
        }
        CouponRecord record = new CouponRecord();
        record.setId(idGenerator.nextId());
        record.setCouponId(templateId);
        record.setUserId(userId);
        record.setStatus(CouponRecord.STATUS_AVAILABLE);
        record.setExpireAt(template.getEndTime()); // 有效期取模板结束时间快照（改期不追溯存量券）
        return toRecordResponse(recordRepo.save(record), template);
    }

    private BizException perLimitExceeded(int perLimit) {
        return new BizException(BizErrorCode.ORDER_STATUS_ERROR,
                "已达每人限领数量（每人限领 " + perLimit + " 张）");
    }

    /** 模板未显式配置限领时按 1 张兜底（与 apply() 写入默认值一致）。 */
    private static int perLimitOf(CouponTemplate template) {
        return template.getPerLimit() == null ? 1 : template.getPerLimit();
    }

    /**
     * 优惠试算：对用户持有的券按订单金额列表求和后计算优惠（满减/无门槛减面额；折扣按 value 折）。
     * 不满足门槛/状态不可用时返回 {@code eligible=false, discount=0}（不抛异常，便于前端展示原因）。
     *
     * <p><b>判定顺序</b>：归属 → 入参合法性 → 券状态/门槛。入参非法必须抛 1001 而不是回
     * {@code eligible=false}——否则客户端会把「请求畸形」误读成「券不可用」而静默按原价下单
     * （归属校验排在最前：越权请求不应泄露任何券信息）。</p>
     */
    public PreviewResponse preview(Long recordId, List<BigDecimal> amounts, long userId) {
        CouponRecord record = requireOwnedRecord(recordId, userId);
        BigDecimal total = sumAmounts(amounts);
        if (record.getStatus() != CouponRecord.STATUS_AVAILABLE) {
            return unavailable(record, null, null, null, "券不可用（" + record.statusText() + "）");
        }
        if (record.getExpireAt().isBefore(Instant.now())) {
            return unavailable(record, null, null, null, "券已过期");
        }
        CouponTemplate template = requireTemplate(record.getCouponId());
        if (total.compareTo(template.getMinAmount()) < 0) {
            return unavailable(record, template, total, BigDecimal.ZERO,
                    "未满足最低消费 " + template.getMinAmount());
        }
        BigDecimal discount = discountOf(template, total);
        return new PreviewResponse(recordId, template.getId(), template.getName(),
                total, discount, total.subtract(discount), true, null);
    }

    /** 金额列表求和；空/空元素/负数一律 1001（非法入参不得静默降级为「券不可用」）。 */
    private BigDecimal sumAmounts(List<BigDecimal> amounts) {
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
        return total;
    }

    /**
     * 核销（available → used + 绑定 orderId；条件更新防并发重复核销）。
     *
     * <p><b>归属校验强制（P0-F 修复）</b>：{@code userId} 必须非空且为券主本人，
     * 缺失身份或他人券一律 {@link BizErrorCode#NO_PERMISSION}（1004/403），
     * 不再存在「无 X-User-Id 即跳过校验」的水平越权口子。服务端间调用请走
     * {@code POST /api/v1/internal/coupons/{id}/deduct}（共享密钥 + 内网）
     * → {@link #deductInternal(Long, Long)}。</p>
     *
     * <p>幂等语义：同一订单重复核销返回成功（订单重试安全）；他订单占用/已过期 → 拒绝。</p>
     */
    @Transactional
    public CouponRecordResponse deduct(Long recordId, Long orderId, Long userId) {
        return doDeduct(recordId, orderId, requireOwnedRecordStrict(recordId, userId));
    }

    /**
     * 关单退回（used → available；幂等：已是可用直接成功）。
     *
     * <p>归属校验同 {@link #deduct}（P0-F）：必须携带券主身份。</p>
     */
    @Transactional
    public CouponRecordResponse release(Long recordId, Long orderId, Long userId) {
        return doRelease(recordId, orderId, requireOwnedRecordStrict(recordId, userId));
    }

    /**
     * internal 端点专用核销（core-order 下单/关单链路，{@code X-Internal-Token} + 内网可达）。
     *
     * <p><b>不校验券归属</b>——调用方是服务端而非买家，鉴权在 {@code InternalCouponController}
     * 的共享密钥上；买家端路径绝不调用本方法（否则等于把 P0-F 的越权口子留着）。
     * P1 换 mTLS 后删除共享密钥即可。</p>
     */
    @Transactional
    public CouponRecordResponse deductInternal(Long recordId, Long orderId) {
        return doDeduct(recordId, orderId, null);
    }

    /** internal 端点专用退券（关单/支付失败补偿），鉴权同上。 */
    @Transactional
    public CouponRecordResponse releaseInternal(Long recordId, Long orderId) {
        return doRelease(recordId, orderId, null);
    }

    /** 核销主体：{@code owner} 非空时已校验归属（买家端），为空表示 internal 信任调用。 */
    private CouponRecordResponse doDeduct(Long recordId, Long orderId, CouponRecord owner) {
        CouponRecord record = owner != null ? owner : requireRecord(recordId);
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

    /** 退券主体：{@code owner} 语义同 {@link #doDeduct}。 */
    private CouponRecordResponse doRelease(Long recordId, Long orderId, CouponRecord owner) {
        CouponRecord record = owner != null ? owner : requireRecord(recordId);
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

    // ---------- 核销兜底回收（P0-G） ----------

    /**
     * 核销未确认兜底回收（定时任务入口，配置超时 {@code app.promotion.deduct-timeout-minutes}）。
     *
     * @return 本次回收（available 回退）张数
     */
    public int reclaimUnconfirmedDeducted() {
        return reclaimUnconfirmedDeducted(Instant.now(), Duration.ofMinutes(deductTimeoutMinutes), orderVerifier);
    }

    /**
     * 核销未确认兜底回收：核销（{@code used_time} 即 deducted_at）超过 {@code timeout} 仍停留在 used、
     * 且回查订单确认「订单不存在 / 已取消 / 已关闭」的券，自动回退为 available。
     *
     * <p><b>为什么必须回查订单</b>：券核销（下单）与订单落库/支付成功是跨服务两步。若只按超时一刀切回收，
     * 会把<b>已支付订单真正消费掉的券</b>也退给用户（凭空造券）。因此判定条件是
     * 「超时 + 订单确实没了」，{@link DeductedOrderVerifier.Outcome#UNKNOWN}（core-order 不可达等）
     * 一律<b>不回收</b>——宁可不退（人工可查）也不误退（资损/薅羊毛）。</p>
     *
     * <p>幂等：回退走条件更新 {@code used → available}，并发/重复执行只有第一次生效，
     * 模板 {@code usedCount} 也只在首次回退时 -1（{@code usedCount > 0} 保护）。</p>
     *
     * @param verifier 订单存活校验器（测试可注入 lambda；生产为 REST 实现）
     */
    @Transactional
    public int reclaimUnconfirmedDeducted(Instant now, Duration timeout, DeductedOrderVerifier verifier) {
        Instant threshold = now.minus(timeout);
        List<CouponRecord> candidates = recordRepo.findByStatusAndUsedTimeLessThanEqual(
                CouponRecord.STATUS_USED, threshold);
        int reclaimed = 0;
        for (CouponRecord record : candidates) {
            if (record.getUsedOrderId() == null) {
                // 无订单绑定的异常数据：不能凭 used_time 判断订单是否存活 → 不动，但必须留痕
                // （否则这类数据在本方法里完全静默，运维无从发现）
                log.warn("[coupon-deduct-reclaim] 核销记录无订单绑定，跳过（不回收）: couponRecordId={} usedTime={}",
                        record.getId(), record.getUsedTime());
                continue;
            }
            DeductedOrderVerifier.Outcome outcome = verifyQuietly(verifier, record);
            if (outcome != DeductedOrderVerifier.Outcome.ORDER_GONE) {
                continue; // ALIVE（订单仍有效）/ UNKNOWN（查不到）→ 不动
            }
            int updated = recordRepo.casRelease(record.getId(),
                    CouponRecord.STATUS_USED, CouponRecord.STATUS_AVAILABLE);
            if (updated > 0) {
                templateRepo.decreaseUsedCount(record.getCouponId(), now);
                reclaimed++;
                log.warn("[coupon-deduct-reclaim] 核销未确认回收：券退回可用 couponRecordId={} couponId={} orderId={} usedTime={}",
                        record.getId(), record.getCouponId(), record.getUsedOrderId(), record.getUsedTime());
            }
        }
        return reclaimed;
    }

    private DeductedOrderVerifier.Outcome verifyQuietly(DeductedOrderVerifier verifier, CouponRecord record) {
        try {
            return verifier.verify(record.getUsedOrderId());
        } catch (RuntimeException e) {
            log.warn("[coupon-deduct-reclaim] 订单存活校验异常，按 UNKNOWN 处理（不回收）: orderId={}",
                    record.getUsedOrderId(), e);
            return DeductedOrderVerifier.Outcome.UNKNOWN;
        }
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

    /**
     * 买家端写操作的归属校验：身份缺失 <b>直接拒绝</b>（P0-F：原实现 {@code userId != null && ...}
     * 在无 X-User-Id 时整段跳过，任意调用方可核销/退回任意用户的券）。
     */
    private CouponRecord requireOwnedRecordStrict(Long recordId, Long userId) {
        if (userId == null) {
            throw new BizException(BizErrorCode.NO_PERMISSION, "缺少用户身份，无法操作优惠券");
        }
        return requireOwnedRecord(recordId, userId);
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
