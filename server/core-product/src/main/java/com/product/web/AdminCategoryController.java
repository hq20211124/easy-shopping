package com.product.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.idempotent.Idempotent;
import com.product.domain.Category;
import com.product.service.CategoryService;
import com.product.service.CategoryService.CategoryNode;
import com.product.web.ProductDtos.CategoryCreateInput;
import com.product.web.ProductDtos.CategorySortItem;
import com.product.web.ProductDtos.CategoryUpdateInput;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端类目接口 —— 路径与 plans/admin-backend.md 3.2.2 类目接口表逐条一致。
 * 管理端鉴权：网关注入 X-User-Id/X-User-Roles，本服务判 ROLE_ADMIN（DEV.md 4.5）。
 */
@RestController
public class AdminCategoryController {

    private final CategoryService categoryService;

    public AdminCategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /** GET /api/admin/category/tree —— 类目树 */
    @GetMapping("/api/admin/category/tree")
    public ApiResponse<List<CategoryNode>> tree() {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(categoryService.tree());
    }

    /** GET /api/admin/category/list —— 分页查询类目 */
    @GetMapping("/api/admin/category/list")
    public ApiResponse<PageResult<Category>> list(@RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int pageSize) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(categoryService.page(page, pageSize));
    }

    /** POST /api/admin/category —— 创建类目 */
    @PostMapping("/api/admin/category")
    @Idempotent
    public ApiResponse<Category> create(@Valid @RequestBody CategoryCreateInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(categoryService.create(in));
    }

    /** PUT /api/admin/category/{id} —— 更新类目 */
    @PutMapping("/api/admin/category/{id}")
    @Idempotent
    public ApiResponse<Category> update(@PathVariable Long id, @Valid @RequestBody CategoryUpdateInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(categoryService.update(id, in));
    }

    /** DELETE /api/admin/category/{id} —— 删除类目（软删；有子类目/商品引用时拒绝） */
    @DeleteMapping("/api/admin/category/{id}")
    @Idempotent
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ProductDtos.requireAdmin();
        categoryService.delete(id);
        return ApiResponse.ok();
    }

    /** PUT /api/admin/category/sort —— 类目排序（批量） */
    @PutMapping("/api/admin/category/sort")
    @Idempotent
    public ApiResponse<Void> sort(@Valid @RequestBody List<CategorySortItem> items) {
        ProductDtos.requireAdmin();
        categoryService.sort(items);
        return ApiResponse.ok();
    }

    /** PUT /api/admin/category/{id}/status —— 启用/禁用 */
    @PutMapping("/api/admin/category/{id}/status")
    @Idempotent
    public ApiResponse<Category> changeStatus(@PathVariable Long id,
                                              @Valid @RequestBody ProductDtos.CategoryStatusInput in) {
        ProductDtos.requireAdmin();
        return ApiResponse.ok(categoryService.changeStatus(id, in.status()));
    }
}
