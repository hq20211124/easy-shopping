package com.easyshopping.user.repo;

import com.easyshopping.user.domain.UserAddress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    List<UserAddress> findByUserIdAndDeletedFalseOrderByDefaultValueDescCreatedAtDesc(Long userId);

    Optional<UserAddress> findByIdAndUserIdAndDeletedFalse(Long id, Long userId);

    List<UserAddress> findByUserIdAndDefaultValueTrueAndDeletedFalse(Long userId);

    long countByUserIdAndDeletedFalse(Long userId);
}
