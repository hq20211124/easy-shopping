package com.cart.repo;

import com.cart.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    Optional<CartItem> findByUserIdAndCartItemId(Long userId, Long cartItemId);

    Optional<CartItem> findByUserIdAndSkuId(Long userId, Long skuId);

    List<CartItem> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    /** 车内总件数（各接口响应的 cart_total_quantity；含失效行）。 */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM CartItem c WHERE c.userId = :userId")
    int sumQuantity(@Param("userId") Long userId);

    /** 已勾选且未失效件数（selected_quantity；失效行不计入，plans ios-backend 4.2.1 口径）。 */
    @Query("SELECT COALESCE(SUM(c.quantity), 0) FROM CartItem c "
            + "WHERE c.userId = :userId AND c.selected = true AND c.invalid = false")
    int sumSelectedQuantity(@Param("userId") Long userId);

    /**
     * 累加数量（重复加购合并；带上限条件保证并发下不超限——0 行 = 超限或行已删除）。
     *
     * @return 命中行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CartItem c SET c.quantity = c.quantity + :qty, c.updatedAt = :now "
            + "WHERE c.userId = :userId AND c.skuId = :skuId AND c.quantity + :qty <= :maxQuantity")
    int incrementQuantityCapped(@Param("userId") Long userId, @Param("skuId") Long skuId,
                                @Param("qty") int qty, @Param("maxQuantity") int maxQuantity,
                                @Param("now") Instant now);

    /** 绝对设数量（cartItemId 定位 + userId 归属防护；0 行 = 不存在或已并发删除）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CartItem c SET c.quantity = :quantity, c.updatedAt = :now "
            + "WHERE c.userId = :userId AND c.cartItemId = :cartItemId")
    int setQuantity(@Param("userId") Long userId, @Param("cartItemId") Long cartItemId,
                    @Param("quantity") int quantity, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CartItem c SET c.selected = :selected, c.updatedAt = :now WHERE c.userId = :userId "
            + "AND c.cartItemId IN :cartItemIds")
    int setSelected(@Param("userId") Long userId, @Param("cartItemIds") Collection<Long> cartItemIds,
                    @Param("selected") boolean selected, @Param("now") Instant now);

    /** 全选/取消全选（cartItemIds 缺省时按全部行处理）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE CartItem c SET c.selected = :selected, c.updatedAt = :now WHERE c.userId = :userId")
    int setSelectedAll(@Param("userId") Long userId, @Param("selected") boolean selected,
                       @Param("now") Instant now);

    long deleteByUserIdAndCartItemIdIn(Long userId, Collection<Long> cartItemIds);
}
