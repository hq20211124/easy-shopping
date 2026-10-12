package com.admin.web;

import com.easyshopping.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工作台/待办聚合（plans/admin-backend.md 17.4；web-admin src/api/system.ts 契约 WorkbenchData/TodoItem）。
 *
 * <p>P0 返回固定 stub（无真实数据源）。TODO(P1)：admin-data 服务消费 MQ（order.paid/payment.success/
 * member.registered 等）落自己库后聚合，禁止直连业务库（plans/admin-backend.md 1.1.2）；
 * 数据权限联动：无权限指标后端不返回（前端 3.1），按 DataScopeFilter 过滤。</p>
 */
@RestController
public class AdminDataController {

    /** GET /api/admin/data/workbench —— 今日 GMV/订单/新会员/待办数 + 7 日趋势（P0 stub）。 */
    @GetMapping("/api/admin/data/workbench")
    public ApiResponse<Map<String, Object>> workbench() {
        return ApiResponse.ok(Map.of(
                "gmv", 0L,
                "orderCount", 0,
                "newMember", 0,
                "pendingCount", 0,
                "trend", List.of()));
    }

    /** GET /api/admin/data/todo —— 待办聚合（审核/售后/库存告警，P0 stub）。 */
    @GetMapping("/api/admin/data/todo")
    public ApiResponse<Map<String, Object>> todo() {
        return ApiResponse.ok(Map.of("items", List.of()));
    }
}
