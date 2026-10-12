package com.product.service;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.id.SnowflakeIdGenerator;
import com.product.domain.Category;
import com.product.repo.CategoryRepository;
import com.product.repo.SpuRepository;
import com.product.web.ProductDtos.CategoryCreateInput;
import com.product.web.ProductDtos.CategorySortItem;
import com.product.web.ProductDtos.CategoryUpdateInput;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类目树管理（管理端）——plans/admin-backend.md 3.2.2 类目接口 + 3.1「类目管理：树 CRUD、排序、启用/禁用」。
 *
 * <p>P0 简化：删除 = 软删；删除前置校验「无子类目且无商品引用」（防树断裂/悬挂引用）。</p>
 */
@Service
public class CategoryService {

    private final CategoryRepository categoryRepo;
    private final SpuRepository spuRepo;
    private final SnowflakeIdGenerator idGenerator;

    public CategoryService(CategoryRepository categoryRepo, SpuRepository spuRepo,
                           SnowflakeIdGenerator idGenerator) {
        this.categoryRepo = categoryRepo;
        this.spuRepo = spuRepo;
        this.idGenerator = idGenerator;
    }

    /** 类目树（GET /api/admin/category/tree）：启用+禁用全量返回，前端按 status 置灰。 */
    @Transactional(readOnly = true)
    public List<CategoryNode> tree() {
        List<Category> all = categoryRepo.findByDeletedFalseOrderBySortAscIdAsc();
        Map<Long, CategoryNode> nodes = new HashMap<>();
        for (Category c : all) {
            nodes.put(c.getId(), new CategoryNode(c.getId(), c.getParentId(), c.getName(),
                    c.getSort(), c.getStatus(), new ArrayList<>()));
        }
        List<CategoryNode> roots = new ArrayList<>();
        for (CategoryNode n : nodes.values()) {
            CategoryNode parent = n.parentId() == 0 ? null : nodes.get(n.parentId());
            if (parent == null) {
                roots.add(n);
            } else {
                parent.children().add(n);
            }
        }
        return roots;
    }

    /** 分页平铺列表（GET /api/admin/category/list）。 */
    @Transactional(readOnly = true)
    public PageResult<Category> page(int pageNum, int pageSize) {
        int size = clamp(pageSize);
        Page<Category> page = categoryRepo.findByDeletedFalse(
                PageRequest.of(Math.max(pageNum - 1, 0), size,
                        org.springframework.data.domain.Sort.by("sort", "id")));
        return PageResult.of(page.getContent(), page.getTotalElements(), pageNum, size);
    }

    @Transactional
    public Category create(CategoryCreateInput in) {
        if (in.name() == null || in.name().isBlank()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "类目名称必填");
        }
        Long parentId = in.parentId() == null ? 0L : in.parentId();
        if (parentId != 0L && !categoryRepo.findById(parentId)
                .map(c -> !c.isDeleted()).orElse(false)) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "父类目不存在: " + parentId);
        }
        Category c = new Category();
        c.setId(idGenerator.nextId());
        c.setParentId(parentId);
        c.setName(in.name().trim());
        c.setSort(in.sort() == null ? 0 : in.sort());
        return categoryRepo.save(c);
    }

    @Transactional
    public Category update(Long id, CategoryUpdateInput in) {
        Category c = mustGet(id);
        if (in.name() != null && !in.name().isBlank()) {
            c.setName(in.name().trim());
        }
        if (in.sort() != null) {
            c.setSort(in.sort());
        }
        return categoryRepo.save(c);
    }

    /** 删除：有子类目或有商品引用时拒绝（plans 3.1 类目管理的隐含约束，防悬挂引用）。 */
    @Transactional
    public void delete(Long id) {
        Category c = mustGet(id);
        if (categoryRepo.existsByParentIdAndDeletedFalse(c.getId())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "存在子类目，禁止删除");
        }
        if (spuRepo.existsByCategoryIdAndDeletedFalse(c.getId())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "类目下存在商品，禁止删除");
        }
        c.setDeleted(true);
        categoryRepo.save(c);
    }

    /** 批量排序（PUT /api/admin/category/sort）。 */
    @Transactional
    public void sort(List<CategorySortItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "排序列表不能为空");
        }
        for (CategorySortItem item : items) {
            Category c = mustGet(item.id());
            if (item.sort() != null) {
                c.setSort(item.sort());
                categoryRepo.save(c);
            }
        }
    }

    /** 启用/禁用（PUT /api/admin/category/{id}/status）：1-启用 0-禁用。 */
    @Transactional
    public Category changeStatus(Long id, Integer status) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "status 仅允许 0/1");
        }
        Category c = mustGet(id);
        c.setStatus(status);
        return categoryRepo.save(c);
    }

    private Category mustGet(Long id) {
        return categoryRepo.findById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "类目不存在: " + id));
    }

    private static int clamp(int pageSize) {
        // 分页口径：pageSize 默认 20、上限 200（plans/admin-backend.md 接口通用规范）
        return Math.min(Math.max(pageSize, 1), 200);
    }

    /** 树节点视图（children 可变以便构建）。 */
    public record CategoryNode(Long id, Long parentId, String name, Integer sort,
                               Integer status, List<CategoryNode> children) {
    }
}
