package com.order;

import com.easyshopping.common.event.InMemoryDomainEventPublisher;
import com.order.client.InventoryClient;
import com.order.client.PaymentClient;
import com.order.client.PriceClient;
import com.order.client.ProductClient;
import com.order.client.PromotionClient;
import com.order.client.dto.ClientDtos.PriceView;
import com.order.client.dto.ClientDtos.SkuView;
import com.order.domain.OrderStatusLog;
import com.order.dto.OrderDtos.CreateOrderRequest;
import com.order.dto.OrderDtos.OrderItemRequest;
import com.order.repo.OrderItemRepository;
import com.order.repo.OrderMainRepository;
import com.order.repo.OrderStatusLogRepository;
import com.order.scheduler.OrderTimeoutCloseScheduler;
import com.order.service.OrderCreateService;
import com.order.service.OrderLifecycleService;
import com.order.service.OrderQueryService;
import com.order.service.OrderStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * core-order 测试基类：mem H2（不污染 ./data 开发库）+ 下游客户端全部 Mock。
 *
 * <p>下游依赖：core-product / core-price / core-inventory / core-promotion / core-payment
 * （跨服务契约见模块 README；单测不依赖外部进程）。</p>
 */
abstract class AbstractOrderTest {

    @Autowired
    protected OrderMainRepository orderMainRepository;
    @Autowired
    protected OrderItemRepository orderItemRepository;
    @Autowired
    protected OrderStatusLogRepository orderStatusLogRepository;
    @Autowired
    protected OrderCreateService createService;
    @Autowired
    protected OrderLifecycleService lifecycleService;
    @Autowired
    protected OrderStateMachine stateMachine;
    @Autowired
    protected OrderQueryService queryService;
    @Autowired
    protected OrderTimeoutCloseScheduler timeoutScheduler;
    @Autowired
    protected InMemoryDomainEventPublisher eventPublisher;

    @MockBean
    protected ProductClient productClient;
    @MockBean
    protected PriceClient priceClient;
    @MockBean
    protected InventoryClient inventoryClient;
    @MockBean
    protected PromotionClient promotionClient;
    @MockBean
    protected PaymentClient paymentClient;

    @BeforeEach
    void resetState() {
        orderStatusLogRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderMainRepository.deleteAll();
        Mockito.reset(productClient, priceClient, inventoryClient, promotionClient, paymentClient);
        // 默认：无券不折扣（promotion 就绪后由具体用例覆盖）
        when(promotionClient.deductCoupon(any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        // 默认：退券被 coupon 侧受理成功（promotion release 幂等）
        when(promotionClient.releaseCoupon(any(), any(), any())).thenReturn(true);
        // 默认：退款被支付域受理成功。
        // P0-2 后 PaymentClient#refund 返回「是否受理」：mock 不打桩时默认 false，
        // 会让 triggerRefund 拒绝发 order.refund.applied（这正是修复后的真实语义），
        // 因此这里显式打桩为「受理成功」，拒绝路径由专门用例覆盖。
        when(paymentClient.refund(any(), any(), anyString())).thenReturn(true);
    }

    /** 打桩商品目录（core-product / core-price）。 */
    protected void stubCatalog(Map<Long, SkuView> skus, Map<Long, BigDecimal> prices) {
        when(productClient.skus(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().map(skus::get).filter(Objects::nonNull).collect(Collectors.toList());
        });
        when(priceClient.prices(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            return ids.stream().filter(prices::containsKey)
                    .map(id -> new PriceView(id, prices.get(id))).collect(Collectors.toList());
        });
    }

    protected static SkuView sku(long skuId, long spuId, long storeId) {
        return new SkuView(skuId, spuId, storeId, "SKU-" + skuId, "img-" + skuId + ".jpg", true);
    }

    protected CreateOrderRequest request(List<OrderItemRequest> items, Long couponId) {
        return new CreateOrderRequest(items, null, "张三", "13800000000",
                "北京市朝阳区望京SOHO T1", couponId, "尽快发货", 1);
    }

    /** 订阅领域事件（P0 进程内发布器，测试直接收集）。 */
    protected List<Object> captureEvents(String eventName) {
        List<Object> captured = new CopyOnWriteArrayList<>();
        eventPublisher.subscribe(eventName, event -> captured.add(event.data()));
        return captured;
    }

    protected static Instant plusMinutes(long minutes) {
        return Instant.now().plusSeconds(minutes * 60);
    }

    /** 便捷断言用：订单状态流水（含建单初始条目）。 */
    protected List<OrderStatusLog> logsOf(Long orderId) {
        return orderStatusLogRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
    }
}
