package com.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写接口幂等键去重（测试要求⑤：Idempotency-Key 防重复提交）。
 *
 * <p>core-cart 写接口全部挂 {@code @Idempotent}（common 的 {@code IdempotentInterceptor}，
 * P0 内存占位）：同键重复 → 第二次 1005/HTTP 409，不产生第二次写；缺键 → 1001。
 * 与 {@code uk_user_sku} 合并语义叠加：不同键同 SKU 也只有一行（数量累加）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-cart-idem-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class CartIdempotencyTest extends AbstractCartTest {

    private static final long USER = 10086L;

    @Autowired
    private MockMvc mockMvc;

    private static final String ADD_BODY = "{\"skuId\":20001,\"quantity\":2}";

    @Test
    void 相同幂等键重复加购_只执行一次() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);

        mockMvc.perform(postJson("/api/v1/cart/add", USER, "idem-cart-001", ADD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(2));
        assertThat(rowCount(USER)).isEqualTo(1);

        // 同键重放：1005 幂等冲突（HTTP 409），不重复加购
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "idem-cart-001", ADD_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1005));
        assertThat(rowCount(USER)).isEqualTo(1);
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(2);

        // 换键：放行并合并数量（不同幂等键 = 新意图）
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "idem-cart-002", ADD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(rowCount(USER)).isEqualTo(1);
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(4);
    }

    @Test
    void 写接口缺失幂等键_拒绝加购() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);

        mockMvc.perform(postJson("/api/v1/cart/add", USER, null, ADD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(rowCount(USER)).isZero();

        // DELETE 动词同样受拦截
        mockMvc.perform(deleteJson("/api/v1/cart/123456789", USER, null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void 幂等键作用域_按方法加URI隔离_不跨别名撞键() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        onShelf(20002L, 10001L, "10.00", 100);

        // 同一键走别名 URI（POST /api/v1/cart）：scopedKey=METHOD:URI:key 不同 → 放行
        mockMvc.perform(postJson("/api/v1/cart", USER, "idem-cart-scope", ADD_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 同一键再走 /api/v1/cart/items 别名：同样放行（URI 不同）
        mockMvc.perform(postJson("/api/v1/cart/items", USER, "idem-cart-scope",
                        "{\"skuId\":20002,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(rowCount(USER)).isEqualTo(2);
    }

    @Test
    void 修改与删除接口_同样受幂等键保护() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k-setup", ADD_BODY))
                .andExpect(status().isOk());
        long cartItemId = rowsOf(USER).get(0).getCartItemId();

        // update：同键重放 → 409，数量保持首次结果
        mockMvc.perform(postJson("/api/v1/cart/update", USER, "idem-upd",
                        "{\"cartItemId\":" + cartItemId + ",\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(postJson("/api/v1/cart/update", USER, "idem-upd",
                        "{\"cartItemId\":" + cartItemId + ",\"quantity\":9}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1005));
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(5);

        // remove：同键重放 → 409；行只删一次（第二次本来也不会再删到）
        mockMvc.perform(postJson("/api/v1/cart/remove", USER, "idem-del",
                        "{\"cartItemIds\":[" + cartItemId + "]}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/remove", USER, "idem-del",
                        "{\"cartItemIds\":[" + cartItemId + "]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(1005));
        assertThat(rowCount(USER)).isZero();
    }
}
