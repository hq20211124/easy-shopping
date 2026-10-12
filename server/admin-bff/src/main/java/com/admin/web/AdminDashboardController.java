package com.admin.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端看板占位接口。
 *
 * <p>TODO（admin-bff 代理，plans/admin-backend.md 九、数据服务 / 十七.4）：
 * P0 返回固定 stub；真实看板由 admin-data 消费 MQ（order.paid/payment.success 等）落自己库后聚合，
 * 禁止直连业务库（plans/admin-backend.md 1.1.2 例外说明）。</p>
 */
@RestController
public class AdminDashboardController {

    /** GET /api/admin/dashboard/overview（需管理端登录）。 */
    @GetMapping("/api/admin/dashboard/overview")
    public ApiResponse<Map<String, Object>> overview() {
        Long adminId = UserContext.requireUserId();
        return ApiResponse.ok(Map.of(
                "adminId", adminId,
                "module", "admin-bff",
                "todo", "实时看板 stub（plans/admin-backend.md 9.x / 17.4）"));
    }
}
