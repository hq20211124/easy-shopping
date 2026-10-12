package com.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.product.domain.Sku;
import com.product.repo.SkuRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 商品编辑的 SKU 合并语义回归网（AUDIT P1-2「⚠️ SKU 全量替换会清空 costPrice」的修复）。
 *
 * <p>背景：{@code PUT /api/admin/product/{id}} 原实现是「先 deleteBySpuId 再全量插入」，导致：</p>
 * <ol>
 *   <li>管理端详情对无 {@code cost:read} 角色的请求把 {@code costPrice} 脱敏为 null，
 *       这类角色每次编辑都会把<b>成本价抹成空</b>（本类第 1 个用例是这条的核心回归网）；</li>
 *   <li>SKU 每次编辑都换新雪花 ID → core-price / core-inventory 的既有绑定全部错位；</li>
 *   <li>价格同步靠「行序号 + skuCode 猜测」配对，错配时把 A 的价写到 B 的 skuId 上。</li>
 * </ol>
 *
 * <p>新语义：带 skuId → 按 id 更新既有行（id 稳定）；不带 skuId → 新增；
 * costPrice/title/skuCode/images 未携带即保留库中原值；请求未携带的既有 SKU 软删；
 * skuId 不属于本商品或同请求内重复 → 1001 明确拒绝。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-product-sku-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.product.price-client.enabled=false"
})
@AutoConfigureMockMvc
class ProductSkuUpdateSemanticsTest {

    private static final String ADMIN = "ROLE_ADMIN";
    private static final String ADMIN_WITH_COST = "ROLE_ADMIN,cost:read";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SkuRepository skuRepo;

    private long categoryId;

    @BeforeEach
    void setUp() throws Exception {
        categoryId = createCategory("SKU合并-" + UUID.randomUUID());
    }

    // ---------- ① 不带 costPrice 更新 → 库中原 costPrice 保持不变（核心回归网） ----------

    /**
     * 这是本次修复要守住的主线：管理端详情对无 cost:read 的角色返回 costPrice=null，
     * 原「全量替换」实现会让该角色的每次编辑都把成本价清空。
     */
    @Test
    @DisplayName("不带 costPrice 更新：库中原成本价不被清空，且 skuId 稳定")
    void updateWithoutCostPriceKeepsExistingCostPrice() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色 256G", "7999.00", "8999.00", "7000.00"),
                skuRow(null, "SKU-WHITE", "白色 512G", "8999.00", null, "8000.00"));
        List<Long> skuIds = activeSkuIds(spuId);
        assertThat(skuIds).hasSize(2);

        // 无 cost:read 角色的详情视图：costPrice 被脱敏（序列化时 null 字段整体省略）
        JsonNode masked = adminDetail(spuId, ADMIN); // 注意：adminDetail 已剥掉信封，根就是 data
        assertThat(masked.at("/skus").size()).isEqualTo(2);
        assertThat(absentOrNull(masked.at("/skus/0/costPrice"))).isTrue();
        assertThat(absentOrNull(masked.at("/skus/1/costPrice"))).isTrue();

        // 前端「详情 → 编辑 → 整体回传」的真实形状：costPrice 不在 payload 里
        List<Map<String, Object>> payload = new ArrayList<>();
        for (JsonNode node : masked.at("/skus")) {
            payload.add(skuRow(node.get("skuId").asLong(), node.get("skuCode").asText(),
                    node.get("title").asText(), priceOf(node, "7999.00"), null, null));
        }
        update(spuId, "手机", payload).andExpect(jsonPath("$.code").value(0));

        // 断言：成本价仍在、skuId 未变（下游价格/库存绑定不漂移）
        assertThat(costOf(skuIds.get(0))).isEqualByComparingTo("7000.00");
        assertThat(costOf(skuIds.get(1))).isEqualByComparingTo("8000.00");
        assertThat(activeSkuIds(spuId)).containsExactlyInAnyOrderElementsOf(skuIds);
        // 从接口侧再确认一次：有 cost:read 的角色仍能看到原成本价（7000.00 / 8000.00）
        JsonNode visible = adminDetail(spuId, ADMIN_WITH_COST);
        assertThat(costPricesOf(visible)).containsExactlyInAnyOrder(7000.00, 8000.00);
    }

    // ---------- ② 带 costPrice 更新 → 按传入更新 ----------

    @Test
    @DisplayName("带 costPrice 更新：按传入值更新")
    void updateWithCostPriceAppliesValue() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"));
        long skuId = activeSkuIds(spuId).get(0);

        update(spuId, "手机", List.of(skuRow(skuId, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7123.45")))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(costOf(skuId)).isEqualByComparingTo("7123.45");
    }

    // ---------- ③ 新增 SKU 行（不带 skuId） ----------

    @Test
    @DisplayName("不带 skuId 的行视为新增：既有行 id 不变、新行落库")
    void updateAddsNewSkuRowWithoutSkuId() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"));
        long oldSkuId = activeSkuIds(spuId).get(0);

        update(spuId, "手机", List.of(
                skuRow(oldSkuId, "SKU-BLACK", "黑色", "7999.00", "8999.00", null), // 既有行：costPrice 缺省 → 保留
                skuRow(null, "SKU-GOLD", "金色 1T", "10999.00", "11999.00", "9500.00"))) // 新增行
                .andExpect(jsonPath("$.code").value(0));

        List<Long> skuIds = activeSkuIds(spuId);
        assertThat(skuIds).hasSize(2).contains(oldSkuId);
        assertThat(costOf(oldSkuId)).isEqualByComparingTo("7000.00");
        long newSkuId = skuIds.stream().filter(id -> id != oldSkuId).findFirst().orElseThrow();
        assertThat(costOf(newSkuId)).isEqualByComparingTo("9500.00");
        assertThat(titleOf(newSkuId)).isEqualTo("金色 1T");
    }

    // ---------- ④ 同一次请求内 skuId 重复 → 明确错误码 ----------

    @Test
    @DisplayName("同一次请求内 skuId 重复：1001 明确拒绝且不落库")
    void duplicateSkuIdRejected() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"));
        long skuId = activeSkuIds(spuId).get(0);

        update(spuId, "手机", List.of(
                skuRow(skuId, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"),
                skuRow(skuId, "SKU-DUP", "重复行", "9999.00", "10999.00", "9000.00")))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("skuId 重复")));

        // 事务回滚：既有行未被第二行覆盖
        assertThat(titleOf(skuId)).isEqualTo("黑色");
    }

    // ---------- ⑤ 禁止按行序号/跨商品静默覆盖别人的 SKU ----------

    @Test
    @DisplayName("skuId 属于别的商品：1001 拒绝，两个商品的 SKU 均不受影响")
    void foreignSkuIdRejected() throws Exception {
        long spuA = createProduct("A商品", skuRow(null, "A-1", "A 规格", "10.00", "20.00", "5.00"));
        long spuB = createProduct("B商品", skuRow(null, "B-1", "B 规格", "30.00", "40.00", "15.00"));
        long skuA = activeSkuIds(spuA).get(0);
        long skuB = activeSkuIds(spuB).get(0);

        // 拿 B 的 skuId 去改 A → 必须拒绝（按行序号匹配的实现会静默覆盖 B 的行）
        update(spuA, "A商品", List.of(skuRow(skuB, "B-1", "B 规格", "30.00", "40.00", "15.00")))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("不属于该商品")));

        assertThat(titleOf(skuB)).isEqualTo("B 规格");
        assertThat(costOf(skuB)).isEqualByComparingTo("15.00");
        assertThat(titleOf(skuA)).isEqualTo("A 规格");

        // 数据库里根本不存在的 skuId 同样拒绝（不能当作新增行默默插进去）
        update(spuA, "A商品", List.of(skuRow(999999999L, "GHOST", "幽灵行", "10.00", "20.00", "5.00")))
                .andExpect(jsonPath("$.code").value(1001));
    }

    /**
     * 行序打乱后仍按 skuId 精确定位：这是「不要按行序号匹配」的直白断言。
     * 原实现（先删后插 + 行序号配对）下，两行会互换/串位。
     */
    @Test
    @DisplayName("入参行序打乱：按 skuId 定位，每行数据落回自己的 SKU")
    void updateMatchesBySkuIdNotRowOrder() throws Exception {
        long spuId = createProduct("手机",
                skuRow(null, "SKU-A", "规格A", "10.00", "20.00", "5.00"),
                skuRow(null, "SKU-B", "规格B", "30.00", "40.00", "15.00"));
        List<Long> skuIds = activeSkuIds(spuId);
        long skuAId = skuOfCode(spuId, "SKU-A");
        long skuBId = skuOfCode(spuId, "SKU-B");

        // 倒序提交，且刻意不带 skuCode（前端可能没回传）→ 只能靠 skuId 定位
        update(spuId, "手机", List.of(
                skuRow(skuBId, null, "规格B改", "31.00", "41.00", null),
                skuRow(skuAId, null, "规格A改", "11.00", "21.00", null)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(titleOf(skuAId)).isEqualTo("规格A改");
        assertThat(titleOf(skuBId)).isEqualTo("规格B改");
        assertThat(costOf(skuAId)).isEqualByComparingTo("5.00");
        assertThat(costOf(skuBId)).isEqualByComparingTo("15.00");
        // skuCode 未携带 → 保留库中原值，不会被重新生成
        assertThat(codeOf(skuAId)).isEqualTo("SKU-A");
        assertThat(codeOf(skuBId)).isEqualTo("SKU-B");
        assertThat(activeSkuIds(spuId)).containsExactlyInAnyOrderElementsOf(skuIds);
    }

    // ---------- ⑥ 一致性校验（价格非负 / marketPrice ≥ price） ----------

    @Test
    @DisplayName("marketPrice 低于 price：1001 拒绝（与 core-price 同一口径/同一错误码）")
    void marketPriceBelowPriceRejected() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"));
        long skuId = activeSkuIds(spuId).get(0);

        update(spuId, "手机", List.of(skuRow(skuId, "SKU-BLACK", "黑色", "7999.00", "1000.00", null)))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("marketPrice 不能低于 price")));

        assertThat(costOf(skuId)).isEqualByComparingTo("7000.00");
    }

    @Test
    @DisplayName("costPrice 为负：1001 拒绝（价格字段非负）")
    void negativeCostPriceRejected() throws Exception {
        long spuId = createProduct("手机", skuRow(null, "SKU-BLACK", "黑色", "7999.00", "8999.00", "7000.00"));
        long skuId = activeSkuIds(spuId).get(0);

        update(spuId, "手机", List.of(skuRow(skuId, "SKU-BLACK", "黑色", "7999.00", "8999.00", "-0.01")))
                .andExpect(jsonPath("$.code").value(1001))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("costPrice 不能为负数")));
    }

    // ---------- ⑦ 请求未携带的既有 SKU：软删除（可追溯、不物理删） ----------

    @Test
    @DisplayName("请求未携带的既有 SKU 软删除：从详情消失但库中留痕")
    void omittedSkuIsSoftDeleted() throws Exception {
        long spuId = createProduct("手机",
                skuRow(null, "SKU-KEEP", "保留", "10.00", "20.00", "5.00"),
                skuRow(null, "SKU-DROP", "移除", "30.00", "40.00", "15.00"));
        long keepId = skuOfCode(spuId, "SKU-KEEP");
        long dropId = skuOfCode(spuId, "SKU-DROP");

        update(spuId, "手机", List.of(skuRow(keepId, "SKU-KEEP", "保留", "10.00", "20.00", null)))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(activeSkuIds(spuId)).containsExactly(keepId);
        // 软删而非物理删：行仍在库中（deleted=true），供对账/售后追溯，不留悬空引用
        Sku dropped = skuRepo.findById(dropId).orElseThrow();
        assertThat(dropped.isDeleted()).isTrue();
        assertThat(skuRepo.findById(keepId).orElseThrow().isDeleted()).isFalse();
    }

    // ---------- helpers ----------

    private Map<String, Object> skuRow(Long skuId, String skuCode, String title,
                                      String price, String marketPrice, String costPrice) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (skuId != null) {
            row.put("skuId", skuId);
        }
        if (skuCode != null) {
            row.put("skuCode", skuCode);
        }
        row.put("title", title);
        row.put("spec", Map.of("标题", title));
        row.put("price", price);
        if (marketPrice != null) {
            row.put("marketPrice", marketPrice);
        }
        if (costPrice != null) {
            row.put("costPrice", costPrice);
        }
        return row;
    }

    private long createCategory(String name) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/admin/category")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Roles", ADMIN)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return data(r).get("id").asLong();
    }

    @SafeVarargs
    private final long createProduct(String name, Map<String, Object>... skus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("categoryId", categoryId);
        body.put("mainImage", "https://cdn.example.com/main.jpg");
        body.put("skus", List.of(skus));
        MvcResult r = mockMvc.perform(post("/api/admin/product")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Id", "1").header("X-User-Roles", ADMIN)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return data(r).get("id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions update(
            long spuId, String name, List<Map<String, Object>> skus) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("categoryId", categoryId);
        body.put("mainImage", "https://cdn.example.com/main.jpg");
        body.put("skus", skus);
        return mockMvc.perform(put("/api/admin/product/" + spuId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", "1").header("X-User-Roles", ADMIN)
                .header("Idempotency-Key", UUID.randomUUID())
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode adminDetail(long spuId, String roles) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/admin/product/" + spuId)
                        .header("X-User-Id", "1").header("X-User-Roles", roles))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return data(r);
    }

    private List<Long> activeSkuIds(long spuId) {
        return skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spuId).stream().map(Sku::getId).toList();
    }

    private long skuOfCode(long spuId, String skuCode) {
        return skuRepo.findBySpuIdAndDeletedFalseOrderBySkuCodeAsc(spuId).stream()
                .filter(s -> skuCode.equals(s.getSkuCode()))
                .findFirst().orElseThrow().getId();
    }

    private java.math.BigDecimal costOf(long skuId) {
        return skuRepo.findById(skuId).orElseThrow().getCostPrice();
    }

    private String titleOf(long skuId) {
        return skuRepo.findById(skuId).orElseThrow().getTitle();
    }

    private String codeOf(long skuId) {
        return skuRepo.findById(skuId).orElseThrow().getSkuCode();
    }

    /** 管理端详情里 SKU 不带 price（价格归 core-price），此处取兜底价只为构造合法入参。 */
    private static String priceOf(JsonNode skuNode, String fallback) {
        return skuNode.hasNonNull("price") ? skuNode.get("price").asText() : fallback;
    }

    /** null 字段在本项目响应里整体省略（脱敏），因此「不可见」需同时兼容 Missing 与 Null 两种形态。 */
    private static boolean absentOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static List<Double> costPricesOf(JsonNode detail) {
        List<Double> out = new ArrayList<>();
        for (JsonNode sku : detail.at("/skus")) {
            out.add(sku.at("/costPrice").asDouble());
        }
        return out;
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .path("data");
    }
}