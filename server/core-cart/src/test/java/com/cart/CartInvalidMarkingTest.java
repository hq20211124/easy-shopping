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
 * 失效标记（测试要求⑥）：core-product 下架 / 删品 → 购物车行 {@code invalid=true}。
 *
 * <p>契约（plans/android-backend.md 16.4、plans/ios-backend.md 4.2.1）：
 * <ul>
 *   <li>失效行<b>仍返回</b>（用户看得见、可删除），只是不计入勾选件数与金额；</li>
 *   <li>{@code totalQuantity} 是「车内总件数」，含失效行；{@code selectedQuantity} 不含；</li>
 *   <li>标记在列表链路回写落库（下次直读），商品恢复上架后<b>可恢复</b>；</li>
 *   <li>加购时下架 → 3003 拒绝；商品不存在 → 3001。</li>
 * </ul></p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-cart-invalid-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class CartInvalidMarkingTest extends AbstractCartTest {

    private static final long USER = 10086L;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 商品下架_列表标记失效且回写_不计入金额与勾选() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        onShelf(20002L, 10001L, "10.00", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk());
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20002,\"quantity\":3}"))
                .andExpect(status().isOk());

        // 正常：全部计入
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(5))
                .andExpect(jsonPath("$.data.selectedQuantity").value(5))
                .andExpect(jsonPath("$.data.items[0].invalid").value(false));

        // 20002 下架
        offShelf(20002L);
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalQuantity").value(5))       // 车内总件数含失效行
                .andExpect(jsonPath("$.data.selectedQuantity").value(2))    // 失效行不计入勾选
                .andExpect(jsonPath("$.data.items[0].invalid").value(false))
                .andExpect(jsonPath("$.data.items[1].invalid").value(true));

        // 金额：只剩 59.90*2 = 119.80
        String body = mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("119.80");

        // 回写落库：invalid=true（下次列表直读，不重复判定）
        assertThat(rowsOf(USER).stream().filter(i -> i.getSkuId() == 20002L)
                .findFirst().orElseThrow().isInvalid()).isTrue();
    }

    @Test
    void 商品从目录消失_同样标记失效() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":1}"))
                .andExpect(status().isOk());

        removeSku(20001L); // core-product 查不到 → 3001 语义

        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].invalid").value(true))
                .andExpect(jsonPath("$.data.items[0].title").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.totalQuantity").value(1))
                .andExpect(jsonPath("$.data.selectedQuantity").value(0));
        assertThat(rowsOf(USER).get(0).isInvalid()).isTrue();
    }

    @Test
    void 商品恢复上架_失效标记自动恢复() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001,\"quantity\":2}"))
                .andExpect(status().isOk());

        offShelf(20001L);
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(jsonPath("$.data.items[0].invalid").value(true));
        assertThat(rowsOf(USER).get(0).isInvalid()).isTrue();

        onShelf(20001L, 10001L, "59.90", 100); // 恢复上架
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].invalid").value(false))
                .andExpect(jsonPath("$.data.selectedQuantity").value(2));
        assertThat(rowsOf(USER).get(0).isInvalid()).isFalse();
    }

    @Test
    void 加购时商品不存在或已下架_拒绝3001_3003() throws Exception {
        // 不存在
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":99999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3001));
        assertThat(rowCount(USER)).isZero();

        // 已下架
        onShelf(20001L, 10001L, "59.90", 100);
        offShelf(20001L);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k2", "{\"skuId\":20001}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(3003));
        assertThat(rowCount(USER)).isZero();
    }

    @Test
    void 失效行仍可删除_不阻塞清理() throws Exception {
        onShelf(20001L, 10001L, "59.90", 100);
        mockMvc.perform(postJson("/api/v1/cart/add", USER, "k1", "{\"skuId\":20001}"))
                .andExpect(status().isOk());
        long cartItemId = rowsOf(USER).get(0).getCartItemId();

        removeSku(20001L);
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(jsonPath("$.data.items[0].invalid").value(true));

        mockMvc.perform(deleteJson("/api/v1/cart/" + cartItemId, USER, "k2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cartTotalQuantity").value(0));
        assertThat(rowCount(USER)).isZero();
    }

    @Test
    void 空购物车_返回空列表与零汇总() throws Exception {
        mockMvc.perform(getJson("/api/v1/cart/list", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalQuantity").value(0))
                .andExpect(jsonPath("$.data.selectedQuantity").value(0))
                .andExpect(jsonPath("$.data.totalAmount").value(0));
    }
}
