package com.admin.system.repo;

import com.admin.system.domain.AdminDict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AdminDictRepository extends JpaRepository<AdminDict, Long> {

    List<AdminDict> findByDictTypeOrderBySortAsc(String dictType);

    boolean existsByDictTypeAndDictValue(String dictType, String dictValue);

    /**
     * 字典类型分页（P0 单表按 dict_type 聚合；TODO(P1) 拆 admin_dict_type 表后改直查）。
     * 返回 [MIN(id), dict_type, COUNT(*)]。
     */
    @Query("SELECT MIN(d.id), d.dictType, COUNT(d) FROM AdminDict d GROUP BY d.dictType")
    Page<Object[]> findDictTypes(Pageable pageable);
}
