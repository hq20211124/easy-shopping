package com.product;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.event.DomainEventNames;
import com.easyshopping.common.event.InMemoryDomainEventPublisher;
import com.product.domain.AuditStatus;
import com.product.domain.ShelfStatus;
import com.product.domain.Spu;
import com.product.repo.SpuRepository;
import com.product.service.CategoryService;
import com.product.service.ProductService;
import com.product.web.ProductDtos.CategoryCreateInput;
import com.product.web.ProductDtos.ProductInput;
import com.product.web.ProductDtos.SkuInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审核状态机流转测试（mem H2 —— DEV.md 5 测试必须用 jdbc:h2:mem:）。
 * 覆盖 plans/admin-backend.md 3.3.2 流转规则：draft→auditing→on_shelf/rejected、
 * 驳回→重提、退回→重提、非法流转拒绝、上架前置条件。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-product-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.product.price-client.enabled=false" // 测试不起 core-price，关闭跨服务调用
})
class ProductAuditStateMachineTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private CategoryService categoryService;

    @Autowired
    private SpuRepository spuRepo;

    @Autowired
    private InMemoryDomainEventPublisher eventPublisher;

    private Long categoryId;
    private final List<String> firedEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        firedEvents.clear();
        eventPublisher.subscribe(DomainEventNames.PRODUCT_UPDATED, e -> firedEvents.add(e.eventName()));
        eventPublisher.subscribe(DomainEventNames.AUDIT_SUBMITTED, e -> firedEvents.add(e.eventName()));
        eventPublisher.subscribe(DomainEventNames.AUDIT_COMPLETED, e -> firedEvents.add(e.eventName()));
        categoryId = categoryService.create(new CategoryCreateInput("测试类目", 0L, 0)).getId();
    }

    @Test
    void draftSubmitApprove_autoOnShelf() {
        Spu spu = createDraft();
        assertThat(spu.getAuditStatus()).isEqualTo(AuditStatus.DRAFT.getValue());
        assertThat(spu.getShelfStatus()).isEqualTo(ShelfStatus.OFF_SHELF.getValue());

        productService.submitAudit(spu.getId());
        assertThat(state(spu)).isEqualTo(AuditStatus.AUDITING.getValue());

        Spu approved = productService.approve(spu.getId());
        assertThat(approved.getAuditStatus()).isEqualTo(AuditStatus.ON_SHELF.getValue());
        // 3.3.2 规则 3：审核通过自动上架
        assertThat(approved.getShelfStatus()).isEqualTo(ShelfStatus.ON_SHELF.getValue());
        // 通过后再提交审核属非法流转（on_shelf 为审核终态）
        assertThatThrownBy(() -> productService.submitAudit(spu.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许");
        assertThat(firedEvents).contains(DomainEventNames.AUDIT_SUBMITTED, DomainEventNames.PRODUCT_UPDATED);
    }

    @Test
    void rejectRequiresReason_andRejectedCanResubmit() {
        Spu spu = createDraft();
        productService.submitAudit(spu.getId());
        // 驳回原因必填（3.3.2 规则 4）
        assertThatThrownBy(() -> productService.reject(spu.getId(), "  "))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode())
                        .isEqualTo(BizErrorCode.PARAM_ERROR.getCode()));
        Spu rejected = productService.reject(spu.getId(), "主图不清");
        assertThat(rejected.getAuditStatus()).isEqualTo(AuditStatus.REJECTED.getValue());
        assertThat(rejected.getAuditRemark()).isEqualTo("主图不清");
        // rejected → auditing（修改后重新提交）
        assertThat(productService.submitAudit(spu.getId()).getAuditStatus())
                .isEqualTo(AuditStatus.AUDITING.getValue());
    }

    @Test
    void returnToDraft_andShelfRequiresApproved() {
        Spu spu = createDraft();
        // 未审核通过禁止上架（3.3.2 状态映射表：上架前置条件 = audit_status=2）
        assertThatThrownBy(() -> productService.onShelf(spu.getId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未通过审核");
        productService.submitAudit(spu.getId());
        Spu returned = productService.returnToDraft(spu.getId(), "价格信息缺失");
        assertThat(returned.getAuditStatus()).isEqualTo(AuditStatus.DRAFT.getValue());
        assertThat(returned.getAuditRemark()).isEqualTo("价格信息缺失");
        // 下架不回退审核状态：先通过再下架，auditStatus 保持 2
        productService.submitAudit(spu.getId());
        productService.approve(spu.getId());
        Spu off = productService.offShelf(spu.getId());
        assertThat(off.getShelfStatus()).isEqualTo(ShelfStatus.OFF_SHELF.getValue());
        assertThat(off.getAuditStatus()).isEqualTo(AuditStatus.ON_SHELF.getValue());
        // 再次上架合法
        assertThat(productService.onShelf(spu.getId()).getShelfStatus())
                .isEqualTo(ShelfStatus.ON_SHELF.getValue());
    }

    @Test
    void draftToOnShelfDirectly_isIllegal() {
        Spu spu = createDraft();
        // draft → on_shelf 非法流转必须被状态机拦截（跳过审核）
        assertThatThrownBy(() -> productService.onShelf(spu.getId()))
                .isInstanceOf(BizException.class);
        assertThat(spuRepo.findById(spu.getId()).orElseThrow().getAuditStatus())
                .isEqualTo(AuditStatus.DRAFT.getValue());
    }

    private Spu createDraft() {
        return productService.create(new ProductInput(
                "测试商品", "副标题", categoryId, "https://cdn.example.com/main.jpg",
                "https://cdn.example.com/1.jpg", null, "<p>detail</p>",
                List.of(new SkuInput(null, null, Map.of("颜色", "黑"), null,
                        new BigDecimal("19.90"), new BigDecimal("29.90"), new BigDecimal("10.00")))));
    }

    private Integer state(Spu spu) {
        return spuRepo.findById(spu.getId()).orElseThrow().getAuditStatus();
    }
}
