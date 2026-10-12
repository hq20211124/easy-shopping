package com.admin.system;

import com.admin.system.domain.AdminPermission;
import com.admin.system.domain.AdminRole;
import com.admin.system.domain.AdminRolePermission;
import com.admin.system.repo.AdminPermissionRepository;
import com.admin.system.repo.AdminRolePermissionRepository;
import com.admin.system.repo.AdminRoleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * RBAC 种子数据（首次启动落库；已有数据则跳过）。
 *
 * <p>菜单/权限码与 web-admin src/config/menus.ts 静态菜单一一对应（前端 P0 静态菜单 + 权限码校验，
 * 后端就绪后切 GET /api/admin/auth/menus 动态路由）。权限码命名「模块:资源」，
 * 商品域统一 product:*（禁 goods:*，plans/admin-backend.md 2.2.3）。</p>
 */
@Component
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final AdminPermissionRepository permissionRepo;
    private final AdminRoleRepository roleRepo;
    private final AdminRolePermissionRepository rolePermissionRepo;

    public DataInitializer(AdminPermissionRepository permissionRepo,
                           AdminRoleRepository roleRepo,
                           AdminRolePermissionRepository rolePermissionRepo) {
        this.permissionRepo = permissionRepo;
        this.roleRepo = roleRepo;
        this.rolePermissionRepo = rolePermissionRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // 幂等：已有完整注册表则跳过（老库补种 system:user/system:role 两个遗漏权限点）
        if (permissionRepo.count() > 0) {
            if (permissionRepo.findAll().stream().noneMatch(p -> "system:user".equals(p.getCode()))) {
                AdminPermission sys = permissionRepo.findAll().stream()
                        .filter(p -> "system".equals(p.getCode())).findFirst().orElse(null);
                if (sys != null) {
                    save("system:user", "管理员管理", "permission", sys.getId(), null, null, 14);
                    save("system:role", "角色管理", "permission", sys.getId(), null, null, 15);
                }
            }
            return;
        }
        // ---- 菜单（type=menu，父在前）----
        AdminPermission dashboard = save("dashboard", "工作台", "menu", null, "/dashboard", null, 1);
        AdminPermission product = save("product", "商品运营", "menu", null, "/product/list", "product:list", 2);
        save("product:list", "商品列表", "menu", product.getId(), "/product/list", "product:list", 1);
        AdminPermission order = save("order", "订单管理", "menu", null, "/order/list", "order:list", 3);
        save("order:list", "订单列表", "menu", order.getId(), "/order/list", "order:list", 1);
        save("order:refund", "退款/售后", "menu", order.getId(), "/order/refund", "refund:list", 2);
        AdminPermission system = save("system", "系统管理", "menu", null, "/system", "system:user", 4);
        save("user:list", "用户列表", "menu", system.getId(), "/user/list", "system:user", 1);
        save("user:roles", "角色权限", "menu", system.getId(), "/user/roles", "system:role", 2);
        save("system:dict", "数据字典", "menu", system.getId(), "/system/dict", "system:dict", 3);
        save("system:log", "操作日志", "menu", system.getId(), "/system/log", "system:log", 4);

        // ---- 按钮/接口权限点（type=permission；dashboard 仅占位，保持树形完整）----
        save("product:create", "新增商品", "permission", product.getId(), null, null, 11);
        save("product:edit", "编辑商品", "permission", product.getId(), null, null, 12);
        save("product:audit", "商品审核", "permission", product.getId(), null, null, 13);
        save("order:detail", "订单详情", "permission", order.getId(), null, null, 11);
        save("order:ship", "订单发货", "permission", order.getId(), null, null, 12);
        save("order:close", "订单关单", "permission", order.getId(), null, null, 13);
        save("refund:list", "售后列表", "permission", order.getId(), null, null, 14);
        // 明文手机号专用权限：列表/详情一律脱敏，仅发货/联系快递场景按需查看（core-order 侧同事务留痕）
        save("order:mobile:read", "查看收货人明文手机号", "permission", order.getId(), null, null, 15);
        save("system:permission", "权限点管理", "permission", system.getId(), null, null, 11);
        save("system:dept", "部门管理", "permission", system.getId(), null, null, 12);
        save("system:config", "系统配置", "permission", system.getId(), null, null, 13);
        // 系统管理两个核心权限点（web-admin menus.ts 的 system:user / system:role 权限码）
        save("system:user", "管理员管理", "permission", system.getId(), null, null, 14);
        save("system:role", "角色管理", "permission", system.getId(), null, null, 15);
        save("cost:read", "成本价查看", "permission", dashboard.getId(), null, null, 21);
        save("report:view", "报表查看", "permission", dashboard.getId(), null, null, 22);

        // ---- 内置超管角色：全量权限 ----
        AdminRole superAdmin = roleRepo.save(new AdminRole(
                "super_admin", "超级管理员", 1, true, "系统内置，不可编辑/删除（plans 2.2.2）"));
        for (AdminPermission p : permissionRepo.findAll()) {
            rolePermissionRepo.save(new AdminRolePermission(superAdmin.getId(), p.getCode()));
        }
        log.info("[data-init] RBAC seed created: {} permissions, role super_admin", permissionRepo.count());
    }

    private AdminPermission save(String code, String title, String type, Long parentId,
                                 String path, String requiredPerm, int orderNum) {
        return permissionRepo.save(new AdminPermission(code, title, type, parentId, path, requiredPerm, orderNum));
    }

    /** 内置角色码（供其他组件引用）。 */
    public static final List<String> BUILTIN_ROLE_CODES = List.of("super_admin");
}
