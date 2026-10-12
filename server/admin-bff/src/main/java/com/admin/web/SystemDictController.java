package com.admin.web;

import com.admin.security.OperationLog;
import com.admin.security.RequirePerm;
import com.admin.system.domain.AdminDict;
import com.admin.system.dto.SystemDtos.DictItemVO;
import com.admin.system.dto.SystemDtos.DictReq;
import com.admin.system.dto.SystemDtos.DictTypeVO;
import com.admin.system.repo.AdminDictRepository;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.idempotent.Idempotent;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 系统管理-数据字典（plans/admin-backend.md 2.5.2；web-admin src/api/system.ts 契约）。
 *
 * <p>订单状态/支付方式等枚举统一从此下发，前端禁止硬编码。
 * P0 单表按 dict_type 聚合出「类型列表」；TODO(P1) 拆 admin_dict_type 表。</p>
 */
@RestController
public class SystemDictController {

    private final AdminDictRepository dictRepo;

    public SystemDictController(AdminDictRepository dictRepo) {
        this.dictRepo = dictRepo;
    }

    /** 字典类型列表（P0 单表聚合；name 暂用 type 编码，TODO(P1) 拆表后取真实名称）。 */
    @GetMapping("/api/admin/system/dict")
    @RequirePerm("system:dict")
    public ApiResponse<PageResult<DictTypeVO>> types(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        Page<Object[]> page = dictRepo.findDictTypes(
                PageRequest.of(Math.max(pageNum - 1, 0), pageSize, Sort.by(Sort.Direction.ASC, "id")));
        List<DictTypeVO> list = page.getContent().stream()
                .filter(row -> keyword == null || keyword.isBlank()
                        || String.valueOf(row[1]).contains(keyword))
                .map(row -> new DictTypeVO((Long) row[0], (String) row[1], (String) row[1], null, 1))
                .toList();
        return ApiResponse.ok(PageResult.of(list, page.getTotalElements(), pageNum, pageSize));
    }

    /** 字典项列表（listDictItems）。 */
    @GetMapping("/api/admin/system/dict/items")
    @RequirePerm("system:dict")
    public ApiResponse<List<DictItemVO>> items(@RequestParam String type) {
        return ApiResponse.ok(dictRepo.findByDictTypeOrderBySortAsc(type).stream()
                .map(d -> new DictItemVO(d.getId(), d.getDictValue(), d.getDictLabel(), d.getSort(), d.getStatus()))
                .toList());
    }

    @PostMapping("/api/admin/system/dict")
    @RequirePerm("system:dict")
    @Idempotent
    @OperationLog(module = "system", action = "创建字典项")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DictReq request) {
        if (dictRepo.existsByDictTypeAndDictValue(request.dictType(), request.dictValue())) {
            throw new BizException(BizErrorCode.PARAM_ERROR,
                    "字典项已存在: " + request.dictType() + "/" + request.dictValue());
        }
        AdminDict dict = new AdminDict(request.dictType(), request.dictValue(),
                request.dictLabel(), request.sort(), request.remark());
        if (request.status() != null) {
            dict.setStatus(request.status());
        }
        AdminDict saved = dictRepo.save(dict);
        return ApiResponse.ok(Map.of("id", saved.getId(), "success", true));
    }

    @PutMapping("/api/admin/system/dict/{id}")
    @RequirePerm("system:dict")
    @OperationLog(module = "system", action = "编辑字典项")
    public ApiResponse<Map<String, Object>> update(@PathVariable Long id,
                                                   @Valid @RequestBody DictReq request) {
        AdminDict dict = dictRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "字典项不存在: " + id));
        if (!dict.getDictType().equals(request.dictType()) || !dict.getDictValue().equals(request.dictValue())) {
            if (dictRepo.existsByDictTypeAndDictValue(request.dictType(), request.dictValue())) {
                throw new BizException(BizErrorCode.PARAM_ERROR,
                        "字典项已存在: " + request.dictType() + "/" + request.dictValue());
            }
        }
        dict.setDictType(request.dictType());
        dict.setDictValue(request.dictValue());
        dict.setDictLabel(request.dictLabel());
        dict.setSort(request.sort() == null ? dict.getSort() : request.sort());
        dict.setRemark(request.remark());
        if (request.status() != null) {
            dict.setStatus(request.status());
        }
        dictRepo.save(dict);
        return ApiResponse.ok(Map.of("success", true));
    }

    @DeleteMapping("/api/admin/system/dict/{id}")
    @RequirePerm("system:dict")
    @OperationLog(module = "system", action = "删除字典项")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        AdminDict dict = dictRepo.findById(id)
                .orElseThrow(() -> new BizException(BizErrorCode.NOT_FOUND, "字典项不存在: " + id));
        dictRepo.delete(dict);
        return ApiResponse.ok(Map.of("success", true));
    }
}
