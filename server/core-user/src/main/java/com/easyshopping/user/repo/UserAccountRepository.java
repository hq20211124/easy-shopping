package com.easyshopping.user.repo;

import com.easyshopping.user.domain.UserAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByUsernameAndDeletedFalse(String username);

    boolean existsByUsername(String username);

    Optional<UserAccount> findByIdAndDeletedFalse(Long id);

    @Query("select u from UserAccount u where u.deleted = false "
            + "and (:role is null or concat(',', u.roles, ',') like concat('%,', :role, ',%')) "
            + "and (:keyword is null or u.username like concat('%', :keyword, '%') "
            + "     or u.nickname like concat('%', :keyword, '%') "
            + "     or u.mobile like concat('%', :keyword, '%'))")
    Page<UserAccount> search(@Param("role") String role, @Param("keyword") String keyword, Pageable pageable);

    long countByRolesContainingAndDeletedFalse(String role);
}
