package com.admin.web;

import com.admin.client.ClientDtos.InternalUserDto;
import com.admin.client.CoreOrderClient;
import com.admin.client.CoreProductClient;
import com.admin.client.CoreUserClient;
import com.admin.rbac.RbacService;
import com.admin.system.domain.AdminRole;
import com.admin.system.domain.AdminUserRole;
import com.admin.system.repo.AdminRoleRepository;
import com.admin.system.repo.AdminUserRoleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC/权限测试（mem H2）：
 * ③ 权限点缺失 → HTTP 403 + 1004；super_admin 绕过
 * ⑥ 菜单树结构（key/label/children）+ auth/info AuthProfile 结构
 * 另：角色授权写入 + permVer 刷新、商品代理透传。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-bff-rbac-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class AdminRbacTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminRoleRepository roleRepo;

    @Autowired
    private AdminUserRoleRepository userRoleRepo;

    @MockBean
    private CoreUserClient coreUserClient;

    @MockBean
    private CoreProductClient coreProductClient;

    @MockBean
    private CoreOrderClient coreOrderClient;

    /** 跨测试方法共享的 userId 计数器（每个测试方法都是新实例，DB 是共享的 context）。 */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_USER_ID =
            new java.util.concurrent.atomic.AtomicLong(910000L);

    private long nextUserId() {
        return NEXT_USER_ID.incrementAndGet();
    }

    /** 建一个启用角色并绑定用户，返回 userId。 */
    private long bindRole(String roleCode, Long roleId) {
        long userId = nextUserId();
        bindExistingUser(userId, roleCode, roleId);
        return userId;
    }

    /** 把指定 userId 绑到角色（角色 ID 或角色码定位）。 */
    private void bindExistingUser(long userId, String roleCode, Long roleId) {
        AdminRole role = roleId == null
                ? roleRepo.findByCode(roleCode)
                : roleRepo.findById(roleId).orElseThrow();
        userRoleRepo.save(new AdminUserRole(userId, role.getId()));
    }

    /* -------------------- ③ 权限点 enforcement -------------------- */

    @Test
    void adminWithoutRoleGets403And1004() throws Exception {
        long userId = nextUserId(); // 有 ADMIN 角色头但无 RBAC 绑定 → 无任何权限点

        mockMvc.perform(get("/api/admin/system/role")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));
    }

    @Test
    void superAdminBypassesPermCheck() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);

        mockMvc.perform(get("/api/admin/system/role")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.list[0].code").value("super_admin"))
                .andExpect(jsonPath("$.data.list[0].builtin").value(true));
    }

    @Test
    void buyerTokenDeniedOnAdminApis() throws Exception {
        mockMvc.perform(get("/api/admin/system/role")
                        .header("X-User-Id", 920000L)
                        .header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(1004));
    }

    /* -------------------- 角色 CRUD 守卫（内置角色防提权） -------------------- */

    @Test
    void builtinRoleCannotBeDeleted() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);
        AdminRole builtin = roleRepo.findByCode(RbacService.SUPER_ADMIN);

        mockMvc.perform(delete("/api/admin/system/role/" + builtin.getId())
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void roleWithBoundUsersCannotBeDeleted() throws Exception {
        long owner = bindRole(RbacService.SUPER_ADMIN, null);
        AdminRole role = roleRepo.save(new AdminRole("cs-" + UUID.randomUUID().toString().substring(0, 8),
                "客服", 2, false, null));
        userRoleRepo.save(new AdminUserRole(930000L, role.getId()));

        mockMvc.perform(delete("/api/admin/system/role/" + role.getId())
                        .header("X-User-Id", owner)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    /* -------------------- 角色授权 + permVer 刷新 -------------------- */

    @Test
    void grantPermissionsRefreshesUserPermVer() throws Exception {
        long ownerId = bindRole(RbacService.SUPER_ADMIN, null);
        AdminRole role = roleRepo.save(new AdminRole("ops-" + UUID.randomUUID().toString().substring(0, 8),
                "运营", 2, false, null));
        long memberId = bindRole(role.getCode(), role.getId());

        // 授权 product:list + order:list
        mockMvc.perform(put("/api/admin/system/role/" + role.getId() + "/permissions")
                        .header("X-User-Id", ownerId)
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"product:list\",\"order:list\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true));

        // 授权查询回读
        mockMvc.perform(get("/api/admin/system/role/" + role.getId() + "/permissions")
                        .header("X-User-Id", ownerId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.permissions.length()").value(2));

        // 授权后该用户已有 product:list → 商品列表放行
        mockMvc.perform(get("/api/admin/product/list")
                        .header("X-User-Id", memberId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk());

        // 授予不存在的权限点 → 1001
        mockMvc.perform(put("/api/admin/system/role/" + role.getId() + "/permissions")
                        .header("X-User-Id", ownerId)
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"not-exist:perm\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void createRoleRequiresIdempotencyKey() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);
        String body = "{\"code\":\"tmp-no-key\",\"name\":\"临时\",\"dataScope\":1}";

        // 缺幂等键 → 1001（plans/admin-backend.md 10.1.1 所有写接口必须携带）
        mockMvc.perform(post("/api/admin/system/role")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        // 携带幂等键 → 成功；同键重复 → 1005 冲突
        String key = "role-" + UUID.randomUUID();
        mockMvc.perform(post("/api/admin/system/role")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"tmp-" + key.substring(0, 8) + "\",\"name\":\"临时角色\",\"dataScope\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(post("/api/admin/system/role")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"tmp-dup\",\"name\":\"临时角色\",\"dataScope\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1005));
    }

    /* -------------------- ⑥ 菜单树 + AuthProfile 结构 -------------------- */

    @Test
    void menusTreeMatchesFrontendStructure() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);

        mockMvc.perform(get("/api/admin/auth/menus")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].key").value("dashboard"))
                .andExpect(jsonPath("$.data[0].label").value("工作台"))
                .andExpect(jsonPath("$.data[1].key").value("product"))
                .andExpect(jsonPath("$.data[1].children[0].key").value("product:list"))
                .andExpect(jsonPath("$.data[1].children[0].path").value("/product/list"))
                .andExpect(jsonPath("$.data[2].key").value("order"))
                .andExpect(jsonPath("$.data[2].children.length()").value(2))
                .andExpect(jsonPath("$.data[3].key").value("system"))
                .andExpect(jsonPath("$.data[3].children.length()").value(4));
    }

    @Test
    void authInfoMatchesAuthProfileContract() throws Exception {
        long userId = 940001L;
        when(coreUserClient.getUser(anyLong())).thenReturn(new InternalUserDto(
                userId, "admin-profile", "档案管理员", null, null, null, null, null,
                1, List.of("ADMIN"), 0, null, null));
        bindExistingUser(userId, RbacService.SUPER_ADMIN, null);

        mockMvc.perform(get("/api/admin/auth/info")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.username").value("admin-profile"))
                .andExpect(jsonPath("$.data.nickname").value("档案管理员"))
                .andExpect(jsonPath("$.data.roles[0]").value("super_admin"))
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.dataScope").value(1))
                .andExpect(jsonPath("$.data.permVer").isNumber())
                .andExpect(jsonPath("$.data.deptName").isString());
    }

    /* -------------------- 商品/订单代理透传 -------------------- */

    @Test
    void productProxyPassesThroughData() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);
        when(coreProductClient.productList(anyMap()))
                .thenReturn(Map.of("total", 1, "pageNum", 1, "pageSize", 20, "list", List.of(Map.of("id", 1))));

        mockMvc.perform(get("/api/admin/product/list").queryParam("keyword", "phone")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list[0].id").value(1));
    }

    @Test
    void orderShipRequiresIdempotencyKeyAndPerm() throws Exception {
        long userId = bindRole(RbacService.SUPER_ADMIN, null);
        when(coreOrderClient.ship(anyLong(), anyMap())).thenReturn(Map.of("success", true));

        mockMvc.perform(post("/api/admin/order/12/ship")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNo\":\"SF123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        mockMvc.perform(post("/api/admin/order/12/ship")
                        .header("X-User-Id", userId)
                        .header("X-User-Roles", "ADMIN")
                        .header("Idempotency-Key", "ship-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingNo\":\"SF123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.success").value(true));
    }
}
