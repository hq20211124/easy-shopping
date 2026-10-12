package com.cart;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 购物车 CRUD 主流程 + 双契约路径 + 归属防护（plans/android-frontend.md 1.6.x、plans/ios-backend.md 4.2.1）。
 *
 * <p>{@code app.cart.max-items=3} 便于验证服务端容量上限（ios-frontend 7.2：上限由服务端下发）。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-cart-crud-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.cart.max-items=3"
})
@AutoConfigureMockMvc
class CartCrudTest extends AbstractCartTest {

    private static final long USER = 10086L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 加购_同SKU合并数量_不产生重复行() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);

        // 同 SKU 加两次（不同幂等键）→ uk_user_sku 合并为一行
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(2));
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20001,\"quantity\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(5));

        assertThat(rowCount(USER)).isEqualTo(1);
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(5);
        // 默认勾选
        assertThat(rowsOf(USER).get(0).isSelected()).isTrue();
    }

    @Test
    void 列表_三源聚合与金额汇总() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        onShelf(20002L, 10001L, "10.00", 0); // 0 库存：展示无货，但仍在车内

        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20002,\"quantity\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.totalQuantity").value(3))
                .andExpect(jsonPath("$.data.selectedQuantity").value(3))
                .andExpect(jsonPath("$.data.maxItems").value(3))
                .andExpect(jsonPath("$.data.items[0].title").value("SKU-20001"))
                .andExpect(jsonPath("$.data.items[0].price").value(59.90))
                .andExpect(jsonPath("$.data.items[1].inStock").value(false))
                // P0 促销未接入：优惠恒 0
                .andExpect(jsonPath("$.data.discountAmount").value(0));

        // 金额 59.90*2 + 10.00*1 = 129.80（BigDecimal 精确断言，不用 JSON 浮点比较）
        String body = mockMvc.perform(getJson("/api/v1/cart", USER)) // RESTful 别名同数据
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("129.80");
    }

    @Test
    void 修改数量_POST动作式与PUT路径式都以cartItemId定位() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk());
        long cartItemId = rowsOf(USER).get(0).getCartItemId();

        // POST /cart/update（body 带 cartItemId）
        mockMvc.perform(postJson("/api/v1/cart/update", USER, "k2",
                        "{\"cartItemId\":" + cartItemId + ",\"quantity\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(7));
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(7);

        // PUT /cart/{cartItemId}（路径定位）——只改勾选
        mockMvc.perform(putJson("/api/v1/cart/" + cartItemId, USER, "k3", "{\"selected\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(7))
                .andExpect(jsonPath("$.data.selectedQuantity").value(0));
        assertThat(rowsOf(USER).get(0).isSelected()).isFalse();

        // PUT /cart/items/{cartItemId} 别名——改数量越上限 → 1001
        mockMvc.perform(putJson("/api/v1/cart/items/" + cartItemId, USER, "k4",
                        "{\"quantity\":9999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(7);
    }

    @Test
    void 勾选_指定行与全选全不选() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        onShelf(20002L, 10001L, "10.00", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20002}"))
                .andExpect(status().isOk());
        long first = rowsOf(USER).get(0).getCartItemId();

        // 取消全选（cartItemIds 缺省 + selected=false）
        mockMvc.perform(postJson("/api/v1/cart/select", USER, "k3", "{\"selected\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedQuantity").value(0));

        // 只勾一行
        mockMvc.perform(postJson("/api/v1/cart/select", USER, "k4",
                        "{\"cartItemIds\":[" + first + "],\"selected\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.selectedQuantity").value(1));

        // 全选（cartItemIds 缺省 + selected=true 或不传 selected）
        mockMvc.perform(postJson("/api/v1/cart/select", USER, "k5", "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(2))
                .andExpect(jsonPath("$.data.selectedQuantity").value(2));
    }

    @Test
    void 删除_路径式DELETE与批量remove() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        onShelf(20002L, 10001L, "10.00", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20002}"))
                .andExpect(status().isOk());
        long first = rowsOf(USER).get(0).getCartItemId();
        long second = rowsOf(USER).get(1).getCartItemId();

        // DELETE /cart/{cartItemId}
        mockMvc.perform(deleteJson("/api/v1/cart/" + first, USER, "k3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(1));
        assertThat(rowCount(USER)).isEqualTo(1);

        // POST /cart/remove 批量（已删的再删一次 → 3004）
        mockMvc.perform(postJson("/api/v1/cart/remove", USER, "k4",
                        "{\"cartItemIds\":[" + second + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(0));
        assertThat(rowCount(USER)).isZero();

        mockMvc.perform(postJson("/api/v1/cart/remove", USER, "k5",
                        "{\"cartItemIds\":[" + second + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));
    }

    @Test
    void 归属防护_他人购物车项一律3004() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001}"))
                .andExpect(status().isOk());
        long cartItemId = rowsOf(USER).get(0).getCartItemId();

        long intruder = 99999L;
        // 改他人行
        mockMvc.perform(postJson("/api/v1/cart/update", intruder, "k2",
                        "{\"cartItemId\":" + cartItemId + ",\"quantity\":9}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));
        // 删他人行
        mockMvc.perform(deleteJson("/api/v1/cart/" + cartItemId, intruder, "k3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));
        // 勾选他人行
        mockMvc.perform(postJson("/api/v1/cart/select", intruder, "k4",
                        "{\"cartItemIds\":[" + cartItemId + "],\"selected\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3004));

        // 本人数据未被破坏
        assertThat(rowCount(USER)).isEqualTo(1);
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(1);
        assertThat(rowsOf(USER).get(0).isSelected()).isTrue();
    }

    @Test
    void 容量上限_超过maxItems拒绝加购() throws Exception {
        onShelf(20001L, 10001L, "1.00", 10);
        onShelf(20002L, 10001L, "1.00", 10);
        onShelf(20003L, 10001L, "1.00", 10);
        onShelf(20004L, 10001L, "1.00", 10);

        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20002}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k3", "{\"skuId\":20003}"))
                .andExpect(status().isOk());

        // 第 4 个不同 SKU：超上限（max-items=3）→ 1001
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k4", "{\"skuId\":20004}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(rowCount(USER)).isEqualTo(3);

        // 已在车内的 SKU 合并不受 maxItems 限制（只是行数上限）
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k5", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(rowCount(USER)).isEqualTo(3);
    }

    @Test
    void 单行数量上限_加购与修改都校验() throws Exception {
        onShelf(20001L, 10001L, "59.90", 9999);

        // 加购超单行上限（max-quantity 默认 999）→ 1001
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1",
                        "{\"skuId\":20001,\"quantity\":1000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(rowCount(USER)).isZero();

        // 0 件 / 负数同样拒绝
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2",
                        "{\"skuId\":20001,\"quantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));

        // 正常加购后，累加到上限封顶裁决：再加到超限 → 1001（不是静默截断）
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k3",
                        "{\"skuId\":20001,\"quantity\":999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k4",
                        "{\"skuId\":20001,\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1001));
        assertThat(rowsOf(USER).get(0).getQuantity()).isEqualTo(999);
    }

    @Test
    void 未登录_无XUserId头返回1002() throws Exception {
        mockMvc.perform(get("/api/v1/cart/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(1002));
    }
}
