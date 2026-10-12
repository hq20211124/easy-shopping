package com.order;

import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import com.easyshopping.common.event.DomainEventNames;
import com.order.crypto.ReceiverMobileCipher;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.AdminOrderVO;
import com.order.dto.OrderDtos.CreateOrderResponse;
import com.order.dto.OrderDtos.InternalOrderSnapshot;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.dto.OrderDtos.ReceiverMobileView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收货人手机号落库加密集成测试（审计 P1 登记项「落库仍明文（P1 改 AES-256-GCM）」）。
 *
 * <p>覆盖读写路径全清单：</p>
 * <ul>
 *   <li><b>写入</b>：下单（父单）落库即密文，列内不含明文；同一手机号两次下单密文不同；</li>
 *   <li><b>读取</b>：买家详情 / 管理端列表 / 管理端详情 → 仍脱敏（plans 12.5/12.6 口径不变）；
 *       管理端明文专用接口（带权限 + 留痕）→ 返回明文且写留痕；</li>
 *   <li><b>服务间快照</b>：internal 快照字段集不含任何 receiver* 字段；</li>
 *   <li><b>日志/事件</b>：{@code order.created} 事件载荷既无 receiver 键也无手机号值；</li>
 *   <li><b>兼容</b>：存量明文行读取正常；</li>
 *   <li><b>失败语义</b>：密文被篡改 / keyId 缺失 / 段数非法 → 明确业务异常，不返回垃圾串。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:core-order-mobile-crypto-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.order.timeout-scan-ms=3600000",
        "app.order.timeout-scan-initial-ms=3600000",
        "app.crypto.receiver-mobile.enabled=true",
        "app.crypto.receiver-mobile.keyring=k1=ZGV2LW9yZGVyLW1vYmlsZS1rZXktMzJieXRlLTAwMDE="
})
class ReceiverMobileCryptoTest extends AbstractOrderTest {

    private static final String MOBILE = "13800000000"; // AbstractOrderTest.request() 的收货人手机号
    private static final String MASKED = "138****0000";
    private static final long ADMIN_ID = 9001L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private CreateOrderResponse createOne() {
        return createOne(20001L);
    }

    private CreateOrderResponse createOne(long skuId) {
        stubCatalog(
                Map.of(skuId, sku(skuId, 10001L, 10001L)),
                Map.of(skuId, new BigDecimal("100.00")));
        return createService.create(10086L, request(List.of(new OrderItemRequest(skuId, 1)), null));
    }

    /** 直接读列里的原始值（绕过 Converter，断言「落库」这件事本身）。 */
    private String rawMobile(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT receiver_mobile FROM order_main WHERE order_no = ?", String.class, orderNo);
    }

    private void forceRawMobile(String orderNo, String value) {
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ? WHERE order_no = ?", value, orderNo);
    }

    // ------------------------------------------------------------------ 写入路径

    @Test
    void 下单落库为密文_列内不含明文() {
        CreateOrderResponse order = createOne();

        String stored = rawMobile(order.orderNo());

        assertThat(stored).startsWith("v1:k1:").doesNotContain(MOBILE);
        // 实体内存里是明文（Converter 在持久化边界完成加解密），脱敏/明文接口链路不需要任何改动
        assertThat(orderMainRepository.findById(order.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo(MOBILE);
    }

    @Test
    void 同一手机号两次下单_落库密文不同_随机IV() {
        CreateOrderResponse first = createOne(20001L);
        CreateOrderResponse second = createOne(20002L);

        String firstStored = rawMobile(first.orderNo());
        String secondStored = rawMobile(second.orderNo());

        assertThat(firstStored).startsWith("v1:k1:").isNotEqualTo(secondStored);
        // 两单手机号相同但密文不同 → 不能用「密文相等」反推「手机号相等」（GCM 随机 IV 的核心收益）
        assertThat(firstStored.split(":")[2]).isNotEqualTo(secondStored.split(":")[2]);
        assertThat(orderMainRepository.findById(first.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo(orderMainRepository.findById(second.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo(MOBILE);
    }

    // ------------------------------------------------------------------ 读取路径（脱敏口径不变）

    @Test
    void 买家端与管理端列表详情_落库密文但出参仍脱敏() {
        CreateOrderResponse order = createOne();
        assertThat(rawMobile(order.orderNo())).startsWith("v1:");

        assertThat(queryService.buyerDetail(order.orderId(), 10086L).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo(MASKED);

        PageResult<AdminOrderVO> page = queryService.adminList(null, null, null, null, false, 1, 10);
        assertThat(page.list()).allSatisfy(vo -> assertThat(vo.receiverMobile()).isEqualTo(MASKED));
    }

    @Test
    void 管理端明文专用接口_解密后返回明文并留痕() {
        CreateOrderResponse order = createOne();

        ReceiverMobileView view = queryService.receiverMobileForShip(order.orderNo(), ADMIN_ID);

        assertThat(view.orderNo()).isEqualTo(order.orderNo());
        assertThat(view.receiverMobile()).isEqualTo(MOBILE);
        List<OrderStatusLog> traces = logsOf(order.orderId()).stream()
                .filter(log -> OrderStatusLog.REASON_VIEW_RECEIVER_MOBILE.equals(log.getReason()))
                .toList();
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getOperatorId()).isEqualTo(ADMIN_ID);
    }

    @Test
    void 服务间快照_字段集不含任何receiver字段() {
        CreateOrderResponse order = createOne();

        InternalOrderSnapshot snapshot = queryService.internalOrder(order.orderId());

        assertThat(Arrays.stream(InternalOrderSnapshot.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("orderId", "userId", "totalAmount", "payAmount", "status", "payDeadline");
        assertThat(snapshot.toString()).doesNotContain("receiver").doesNotContain(MOBILE);
    }

    // ------------------------------------------------------------------ 日志 / 事件载荷

    @Test
    void 下单事件载荷_不含receiver键也不含明文手机号() {
        List<Object> events = captureEvents(DomainEventNames.ORDER_CREATED);

        createOne();

        assertThat(events).hasSize(1);
        String payload = String.valueOf(events.get(0));
        assertThat(payload).doesNotContain("receiver").doesNotContain(MOBILE);
    }

    // ------------------------------------------------------------------ 存量明文兼容

    @Test
    void 存量明文行_读取正常_出参仍按口径脱敏() {
        CreateOrderResponse order = createOne();
        forceRawMobile(order.orderNo(), "18612345678"); // 模拟加密上线前写入的历史明文行

        assertThat(orderMainRepository.findById(order.orderId()).orElseThrow().getReceiverMobile())
                .isEqualTo("18612345678");
        assertThat(queryService.buyerDetail(order.orderId(), 10086L).receiverMobile()).isEqualTo("186****5678");
        assertThat(queryService.adminDetail(order.orderId()).receiverMobile()).isEqualTo("186****5678");
        assertThat(queryService.receiverMobileForShip(order.orderNo(), ADMIN_ID).receiverMobile())
                .isEqualTo("18612345678");
    }

    @Test
    void 明文与密文行可在同一列共存_互不影响() {
        CreateOrderResponse cipherRow = createOne(20001L);
        CreateOrderResponse plainRow = createOne(20002L);
        forceRawMobile(plainRow.orderNo(), "18612345678");

        assertThat(rawMobile(cipherRow.orderNo())).startsWith("v1:");
        assertThat(rawMobile(plainRow.orderNo())).isEqualTo("18612345678");
        assertThat(queryService.adminDetail(cipherRow.orderId()).receiverMobile()).isEqualTo(MASKED);
        assertThat(queryService.adminDetail(plainRow.orderId()).receiverMobile()).isEqualTo("186****5678");
    }

    // ------------------------------------------------------------------ 失败语义（不静默返回垃圾）

    @Test
    void 密文被篡改_读取抛9999业务异常_消息不含密文() {
        CreateOrderResponse order = createOne();
        String tampered = tamper(rawMobile(order.orderNo()));
        forceRawMobile(order.orderNo(), tampered);

        assertThatThrownBy(() -> queryService.adminDetail(order.orderId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR))
                .hasMessageContaining("校验失败")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(tampered));
        assertThatThrownBy(() -> queryService.buyerDetail(order.orderId(), 10086L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("校验失败");
        // 管理端明文专用接口同样 fail-closed：不返回 v1: 垃圾串、更不返回空串
        assertThatThrownBy(() -> queryService.receiverMobileForShip(order.orderNo(), ADMIN_ID))
                .isInstanceOf(BizException.class);
    }

    @Test
    void JPA与SpringData双层包装_服务层已还原为带原因的9999() {
        CreateOrderResponse order = createOne();
        forceRawMobile(order.orderNo(), tamper(rawMobile(order.orderNo())));

        // 仓储层：JPA 规范要求把 Converter 异常包进 PersistenceException，Spring Data 再翻一层
        // JpaSystemException —— 两层包装里都还能找到原始业务异常（排障时不会被翻译层吃掉）
        Throwable raw = org.assertj.core.api.Assertions.catchThrowable(
                () -> orderMainRepository.findById(order.orderId()).orElseThrow());
        assertThat(raw).isNotNull();
        assertThat(ReceiverMobileCipher.unwrapPiiFailure(raw)).isNotNull()
                .hasMessageContaining("收货人手机号密文");
        // 服务层：还原为带原因的 9999 业务异常
        assertThatThrownBy(() -> queryService.adminDetail(order.orderId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR))
                .hasMessageContaining("收货人手机号密文");
    }

    @Test
    void 密文keyId缺失_读取抛9999并提示keyId() {
        CreateOrderResponse order = createOne();
        String[] parts = rawMobile(order.orderNo()).split(":", -1);
        forceRawMobile(order.orderNo(), "v1:k-old:" + parts[2] + ":" + parts[3]);

        assertThatThrownBy(() -> queryService.adminDetail(order.orderId()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("k-old");
    }

    @Test
    void 密文段数非法_读取抛9999而非当明文直出() {
        CreateOrderResponse order = createOne();
        forceRawMobile(order.orderNo(), "v1:k1:broken");

        assertThatThrownBy(() -> queryService.buyerDetail(order.orderId(), 10086L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("格式非法");
    }

    @Test
    void 密文段base64非法_读取抛9999() {
        CreateOrderResponse order = createOne();
        forceRawMobile(order.orderNo(), "v1:k1:@@@:@@@");

        assertThatThrownBy(() -> queryService.adminDetail(order.orderId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(BizErrorCode.SYSTEM_ERROR));
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 收尾：把本类用例故意写坏的行（篡改/缺密钥/格式非法）清空，否则 {@code deleteAll()}
     * 加载实体时解密失败，会把后续用例的 resetState 一起带崩（解密是 fail-closed 的，
     * 一行坏数据会拖垮整个读取链路——这也是必须 fail-closed 的代价，真实库靠告警修数据）。
     */
    @org.junit.jupiter.api.AfterEach
    void neutraliseBrokenRows() {
        jdbcTemplate.update("UPDATE order_main SET receiver_mobile = ''");
    }

    /** 翻转密文首字符（GCM 认证标签必然校验失败）。 */
    private String tamper(String cipherText) {
        String[] parts = cipherText.split(":", -1);
        char head = parts[3].charAt(0);
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                + (head == 'A' ? 'B' : 'A') + parts[3].substring(1);
    }

    @Test
    void 密文长度落在列宽内_IV为96位() {
        CreateOrderResponse order = createOne();

        String stored = rawMobile(order.orderNo());

        assertThat(stored.length()).isLessThanOrEqualTo(255); // varchar(255)：H2/MySQL 通用
        assertThat(Base64.getDecoder().decode(stored.split(":")[2])).hasSize(12);
    }
}