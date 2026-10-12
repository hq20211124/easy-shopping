package com.admin.web;

import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.domain.AdminDept;
import com.admin.system.dto.SystemDtos.DeptReq;
import com.admin.system.dto.SystemDtos.DeptVO;
import com.admin.system.repo.AdminDeptRepository;
import com.admin.system.repo.AdminUserExtRepository;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.idempotent.Idempotent;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 系统管理-部门（plans/admin-backend.md 2.5.2；数据范围 2/3/5 的挂靠维度）。
 * 禁止删除有子节点/有用户的部门。
 */
@RestController
public class SystemDeptController {

    private final AdminDeptRepository deptRepo;
    private final AdminUserExtRepository userExtRepo;

    public SystemDeptController(AdminDeptRepository deptRepo,
                                AdminUserExtRepository userExtRepo) {
        this.deptRepo = deptRepo;
        this.userExtRepo = userExtRepo;
    }

    /** 部门树（扁平 VO，前端按 parentId 组树；userCount 供删除守卫提示）。 */
    @GetMapping("/api/admin/system/dept")
    @RequirePerm("system:dept")
    public ApiResponse<List<DeptVO>> tree() {
        List<DeptVO> list = deptRepo.findAll().stream()
                .sorted(Comparator.comparing(AdminDept::getId))
                .map(d -> new DeptVO(d.getId(), d.getName(), d.getParentId(),
                        userExtRepo.countByDeptId(d.getId())))
                .toList();
        return ApiResponse.ok(list);
    }

    @PostMapping("/api/admin/system/dept")
    @RequirePerm("system:dept")
    @Idempotent
    @OperationLog(module = "system", action = "创建部门")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DeptReq request) {
        if (deptRepo.existsByName(request.name())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "部门名称已存在: " + request.name());
        }
        if (request.parentId() != null && deptRepo.findById(request.parentId()).isEmpty()) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "父部门不存在: " + request.parentId());
        }
        AdminDept saved = deptRepo.save(new AdminDept(request.name(), request.parentId()));
        return ApiResponse.ok(Map.of("id", saved.getId(), "success", true));
    }

    @PutMapping("/api/admin/system/dept/{id}")
    @RequirePerm("system:dept")
    @OperationLog(module = "system", action = "编辑部门")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody DeptReq request) {
        AdminDept dept = requireDept(id);
        if (!dept.getName().equals(request.name()) && deptRepo.existsByName(request.name())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "部门名称已存在: " + request.name());
        }
        if (request.parentId() != null && (request.parentId().equals(id)
                || deptRepo.findById(request.parentId()).isEmpty())) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "父部门不合法");
        }
        dept.setName(request.name());
        dept.setParentId(request.parentId());
        deptRepo.save(dept);
        return ApiResponse.ok(Map.of("success", true));
    }

    @DeleteMapping("/api/admin/system/dept/{id}")
    @RequirePerm("system:dept")
    @Transactional
    @OperationLog(module = "system", action = "删除部门")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AdminDept dept = requireDept(id);
        if (deptRepo.countByParentId(id) > 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "存在子部门，无法删除");
        }
        long users = userExtRepo.countByDeptId(id);
        if (users > 0) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "部门下存在 " + users + " 个用户，无法删除");
        }
        deptRepo.delete(dept);
        return ApiResponse.ok(Map.of("success", true));
    }

    private AdminDept requireDept(Long id) {
        return deptRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "部门不存在: " + id));
    }
}
