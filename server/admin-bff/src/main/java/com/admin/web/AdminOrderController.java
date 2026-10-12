package com.admin.web;

import com.admin.client.CoreOrderClient;
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
 * 订单管理代理（plans/admin-backend.md 4.2.1；订单状态机 0~7 见 4.3）。
 *
 * <p>P0 由 core-order 承载 admin 订单接口，本 BFF 只做权限点校验 + 透传。
 * 数据权限（2.3 档位过滤）TODO(P1)：core-order 支持 storeId/deptId 过滤参数后在此注入。
 * 依赖集成：core-order 侧对应路径未就绪时返回 9999「core-order 暂不可用」。</p>
 */
@RestController
public class AdminOrderController {

    private final CoreOrderClient coreOrderClient;

    public AdminOrderController(CoreOrderClient coreOrderClient) {
        this.coreOrderClient = coreOrderClient;
    }

    @GetMapping("/api/admin/order/list")
    @RequirePerm("order:list")
    public ApiResponse<Object> list(@RequestParam Map<String, String> params) {
        return ApiResponse.ok(coreOrderClient.orderList(params));
    }

    @GetMapping("/api/admin/order/{id}")
    @RequirePerm("order:detail")
    public ApiResponse<Object> detail(@PathVariable long id) {
        return ApiResponse.ok(coreOrderClient.orderDetail(id));
    }

    @PostMapping("/api/admin/order/{id}/ship")
    @RequirePerm("order:ship")
    @Idempotent
    @OperationLog(module = "order", action = "订单发货")
    public ApiResponse<Object> ship(@PathVariable long id,
                                    @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(coreOrderClient.ship(id, body));
    }

    @PostMapping("/api/admin/order/{id}/close")
    @RequirePerm("order:close")
    @Idempotent
    @OperationLog(module = "order", action = "订单关闭")
    public ApiResponse<Object> close(@PathVariable long id,
                                     @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(coreOrderClient.close(id, body));
    }
}
