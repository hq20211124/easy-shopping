package com.admin.web;

import com.admin.client.CoreProductClient;
import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.idempotent.Idempotent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 商品管理/审核代理（plans/admin-backend.md 3.2.1 / 3.4）。
 *
 * <p>P0 由 core-product 承载 admin 商品接口，本 BFF 只做权限点校验 + 透传
 * （TODO(P1) 拆 admin-product / admin-audit 细分服务后改为本地聚合 + 数据权限改写）。
 * 依赖集成：core-product 侧对应路径未就绪时返回 9999「core-product 暂不可用」。</p>
 */
@RestController
public class AdminProductController {

    private final CoreProductClient coreProductClient;

    public AdminProductController(CoreProductClient coreProductClient) {
        this.coreProductClient = coreProductClient;
    }

    @GetMapping("/api/admin/product/list")
    @RequirePerm("product:list")
    public ApiResponse<Object> list(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(coreProductClient.productList(params));
    }

    @GetMapping("/api/admin/product/{id}")
    @RequirePerm("product:list")
    public ApiResponse<Object> detail(@PathVariable long id) {
        return ApiResponse.ok(coreProductClient.productDetail(id));
    }

    @GetMapping("/api/admin/audit/task/list")
    @RequirePerm("product:audit")
    public ApiResponse<Object> auditTaskList(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(coreProductClient.auditTaskList(params));
    }

    @PostMapping("/api/admin/audit/task/{id}/approve")
    @RequirePerm("product:audit")
    @Idempotent
    @OperationLog(module = "product", action = "审核通过")
    public ApiResponse<Object> auditApprove(@PathVariable long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(coreProductClient.auditApprove(id, body));
    }

    @PostMapping("/api/admin/audit/task/{id}/reject")
    @RequirePerm("product:audit")
    @Idempotent
    @OperationLog(module = "product", action = "审核驳回")
    public ApiResponse<Object> auditReject(@PathVariable long id,
                                           @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(coreProductClient.auditReject(id, body));
    }
}
