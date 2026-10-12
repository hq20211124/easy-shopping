package com.admin.rbac;

import com.admin.system.domain.AdminDept;
import com.admin.system.domain.AdminPermission;
import com.admin.system.domain.AdminRole;
import com.admin.system.domain.AdminRolePermission;
import com.admin.system.domain.AdminUserExt;
import com.admin.system.domain.AdminUserRole;
import com.admin.system.repo.AdminDeptRepository;
import com.admin.system.repo.AdminPermissionRepository;
import com.admin.system.repo.AdminRolePermissionRepository;
import com.admin.system.repo.AdminRoleRepository;
import com.admin.system.repo.AdminUserExtRepository;
import com.admin.system.repo.AdminUserRoleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * RBAC 读模型：角色/权限点/菜单树/数据范围（plans/admin-backend.md 2.2）。
 *
 * <p>权限缓存：P0 每请求查库（单机 H2 亚毫秒）；TODO(P1) Redis 缓存 + permVer 失效
 * （plans 2.1.4 权限实时比对），网关/服务侧可选 JWT 携带 permissions（2.1.3）。</p>
 */
@Service
public class RbacService {

    /** 超级管理员角色码（内置，绕过所有权限点校验，plans 2.2.2） */
    public static final String SUPER_ADMIN = "super_admin";

    private static final String TYPE_MENU = "menu";
    private static final String TYPE_PERMISSION = "permission";

    private final AdminRoleRepository roleRepo;
    private final AdminPermissionRepository permissionRepo;
    private final AdminUserRoleRepository userRoleRepo;
    private final AdminRolePermissionRepository rolePermissionRepo;
    private final AdminUserExtRepository userExtRepo;
    private final AdminDeptRepository deptRepo;

    public RbacService(AdminRoleRepository roleRepo,
                       AdminPermissionRepository permissionRepo,
                       AdminUserRoleRepository userRoleRepo,
                       AdminRolePermissionRepository rolePermissionRepo,
                       AdminUserExtRepository userExtRepo,
                       AdminDeptRepository deptRepo) {
        this.roleRepo = roleRepo;
        this.permissionRepo = permissionRepo;
        this.userRoleRepo = userRoleRepo;
        this.rolePermissionRepo = rolePermissionRepo;
        this.userExtRepo = userExtRepo;
        this.deptRepo = deptRepo;
    }

    /* -------------------- 角色 / 权限 -------------------- */

    /** 启用状态的角色码列表。 */
    public List<String> roleCodes(Long userId) {
        List<Long> roleIds = userRoleRepo.findByUserId(userId).stream().map(AdminUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleRepo.findAllById(roleIds).stream()
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .map(AdminRole::getCode)
                .toList();
    }

    /** 权限点集合：super_admin → 全量注册表；否则角色权限并集。 */
    public Set<String> permCodes(Long userId) {
        List<AdminUserRole> urs = userRoleRepo.findByUserId(userId);
        if (urs.isEmpty()) {
            return Set.of();
        }
        List<Long> roleIds = urs.stream().map(AdminUserRole::getRoleId).toList();
        boolean superAdmin = roleRepo.findAllById(roleIds).stream()
                .anyMatch(r -> SUPER_ADMIN.equals(r.getCode()) && (r.getStatus() == null || r.getStatus() == 1));
        if (superAdmin) {
            return new HashSet<>(allPermCodes());
        }
        return rolePermissionRepo.findByRoleIdIn(roleIds).stream()
                .map(AdminRolePermission::getPermissionCode)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    public List<String> allPermCodes() {
        return permissionRepo.findAll().stream().map(AdminPermission::getCode).toList();
    }

    public boolean hasPerm(Long userId, String permCode) {
        if (permCode == null || permCode.isBlank()) {
            return true;
        }
        return permCodes(userId).contains(permCode);
    }

    /** 数据范围：取用户角色中最宽一档（数值最小）。无角色 → 仅本人（最保守）。 */
    public DataScope dataScope(Long userId) {
        List<Long> roleIds = userRoleRepo.findByUserId(userId).stream().map(AdminUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return DataScope.SELF;
        }
        return roleRepo.findAllById(roleIds).stream()
                .filter(r -> r.getStatus() != null && r.getStatus() == 1)
                .map(AdminRole::getDataScope)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(DataScope::of)
                .orElse(DataScope.SELF);
    }

    public DataScopeFilter dataScopeFilter(Long userId) {
        AdminUserExt ext = userExtRepo.findById(userId).orElse(null);
        return DataScopeFilter.of(dataScope(userId), ext == null ? null : ext.getDeptId(), userId);
    }

    /* -------------------- 用户扩展（dept / permVer） -------------------- */

    @Transactional
    public AdminUserExt ensureUserExt(Long userId, Long deptId) {
        return userExtRepo.findById(userId)
                .map(ext -> {
                    if (deptId != null && !deptId.equals(ext.getDeptId())) {
                        ext.setDeptId(deptId);
                    }
                    return userExtRepo.save(ext);
                })
                .orElseGet(() -> userExtRepo.save(new AdminUserExt(userId, deptId)));
    }

    /**
     * 管理员角色引导（集成缺口修复）：core-user 只有 ADMIN/USER 账号角色，admin-bff 的 RBAC
     * 角色表按 userId 绑定，首次登录若未绑定任何角色则所有 @RequirePerm 接口一律 403。
     *
     * <p>P0 口径：具备 core-user ADMIN 角色即视为超级管理员，首次登录自动绑定内置
     * {@code super_admin}（已绑定角色的用户不做任何改动，管理员可在「角色权限」页自行调整）。</p>
     */
    @Transactional
    public void ensureBootstrapRole(Long userId) {
        if (!userRoleRepo.findByUserId(userId).isEmpty()) {
            return;
        }
        AdminRole superAdmin = roleRepo.findByCode(SUPER_ADMIN);
        if (superAdmin != null) {
            userRoleRepo.save(new AdminUserRole(userId, superAdmin.getId()));
        }
    }

    public long permVer(Long userId) {
        return userExtRepo.findById(userId).map(AdminUserExt::getPermVer).orElse(0L);
    }

    public Long deptId(Long userId) {
        return userExtRepo.findById(userId).map(AdminUserExt::getDeptId).orElse(null);
    }

    public String deptName(Long deptId) {
        if (deptId == null) {
            return "";
        }
        return deptRepo.findById(deptId).map(AdminDept::getName).orElse("");
    }

    /** 角色授权变更 → 该角色所有用户 permVer+1（plans 2.1.4；前端比对后强刷/重登）。 */
    @Transactional
    public int bumpPermVerForRole(Long roleId) {
        int count = 0;
        for (AdminUserRole ur : userRoleRepo.findByRoleId(roleId)) {
            AdminUserExt ext = userExtRepo.findById(ur.getUserId()).orElse(null);
            if (ext != null) {
                ext.bumpPermVer();
                userExtRepo.save(ext);
                count++;
            }
        }
        return count;
    }

    @Transactional
    public void bumpPermVerForUser(Long userId) {
        AdminUserExt ext = userExtRepo.findById(userId).orElse(null);
        if (ext != null) {
            ext.bumpPermVer();
            userExtRepo.save(ext);
        }
    }

    /* -------------------- 菜单树 / 权限树 -------------------- */

    /** 菜单节点（对齐 web-admin utils/permission.ts 的 PermissionMenuItem 结构）。 */
    public record MenuNode(String key, String label, String path, String permission, List<MenuNode> children) {
    }

    /** 权限树节点（对齐 web-admin api/user.ts 的 PermissionTreeNode：type=menu|permission）。 */
    public record PermTreeNode(String key, String title, String type, List<PermTreeNode> children) {
    }

    /** 按用户权限过滤的菜单树（super_admin 全量；空权限父菜单无可见子项则隐藏）。 */
    public List<MenuNode> menus(Long userId) {
        Set<String> owned = permCodes(userId);
        boolean superAdmin = roleCodes(userId).contains(SUPER_ADMIN);
        Map<Long, List<AdminPermission>> byParent = groupByParent();
        List<AdminPermission> roots = byParent.getOrDefault(null, byParent.getOrDefault(0L, List.of()));
        List<MenuNode> result = new ArrayList<>();
        for (AdminPermission root : sort(roots)) {
            MenuNode node = toMenuNode(root, byParent, owned, superAdmin);
            if (node != null) {
                result.add(node);
            }
        }
        return result;
    }

    private MenuNode toMenuNode(AdminPermission p, Map<Long, List<AdminPermission>> byParent,
                                Set<String> owned, boolean superAdmin) {
        // 权限点（type=permission）只做按钮/接口鉴权，不进动态路由菜单树
        if (TYPE_PERMISSION.equals(p.getType())) {
            return null;
        }
        boolean visible = superAdmin
                || p.getRequiredPerm() == null || p.getRequiredPerm().isBlank()
                || owned.contains(p.getRequiredPerm());
        List<MenuNode> children = new ArrayList<>();
        for (AdminPermission child : sort(byParent.getOrDefault(p.getId(), List.of()))) {
            MenuNode node = toMenuNode(child, byParent, owned, superAdmin);
            if (node != null) {
                children.add(node);
            }
        }
        if (!visible && children.isEmpty()) {
            return null;
        }
        return new MenuNode(p.getCode(), p.getTitle(), p.getPath(),
                p.getRequiredPerm(), children.isEmpty() ? null : children);
    }

    /** 全量权限树（角色授权 TreeTransfer 数据源）。 */
    public List<PermTreeNode> permissionTree() {
        Map<Long, List<AdminPermission>> byParent = groupByParent();
        List<PermTreeNode> result = new ArrayList<>();
        for (AdminPermission root : sort(byParent.getOrDefault(null, byParent.getOrDefault(0L, List.of())))) {
            result.add(toPermTreeNode(root, byParent));
        }
        return result;
    }

    private PermTreeNode toPermTreeNode(AdminPermission p, Map<Long, List<AdminPermission>> byParent) {
        List<PermTreeNode> children = sort(byParent.getOrDefault(p.getId(), List.of())).stream()
                .map(c -> toPermTreeNode(c, byParent))
                .toList();
        return new PermTreeNode(p.getCode(), p.getTitle(),
                TYPE_MENU.equals(p.getType()) ? TYPE_MENU : TYPE_PERMISSION,
                children.isEmpty() ? null : children);
    }

    private Map<Long, List<AdminPermission>> groupByParent() {
        Map<Long, List<AdminPermission>> byParent = new LinkedHashMap<>();
        for (AdminPermission p : permissionRepo.findAll()) {
            Long parent = p.getParentId() == null ? null : p.getParentId();
            byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(p);
        }
        return byParent;
    }

    private List<AdminPermission> sort(List<AdminPermission> list) {
        return list.stream()
                .sorted(Comparator.comparing(AdminPermission::getOrderNum, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
