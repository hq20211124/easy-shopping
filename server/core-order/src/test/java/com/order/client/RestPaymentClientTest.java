package com.order.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 退款客户端契约测试（真实 HTTP 报文，非 mock 掉整个 PaymentClient）。
 *
 * <p>审计指出：{@code OrderRaceTest} 用 {@code @MockBean PaymentClient} 只验证「调用发生了」，
 * 而真实实现曾是 no-op（只打 WARN）→ 两条资金补偿路径实际失效且无人发现。
 * 本类锁定 core-payment 退款接口的<b>路径、请求体与失败语义</b>，防止再次退化为空实现。</p>
 */
class RestPaymentClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestPaymentClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://core-payment");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPaymentClient(builder.build());
    }

    @Test
    void 退款受理_命中真实路径与请求体_返回true() {
        server.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // 请求体不含 amount：退款金额由支付单全额决定（P0 不支持部分退款）
                .andExpect(jsonPath("$.orderId").value(1001))
                .andExpect(jsonPath("$.reason").value("后台关闭已支付订单，原路退款"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"ok\",\"data\":"
                        + "{\"orderId\":1001,\"paymentId\":2002,\"status\":\"refunded\",\"refundAmount\":189.00}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.refund(1001L, new BigDecimal("189.00"), "后台关闭已支付订单，原路退款")).isTrue();
        server.verify();
    }

    @Test
    void 退款幂等_远端返回成功即视为已受理() {
        server.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"orderId\":1001,\"status\":\"refunded\"}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.refund(1001L, new BigDecimal("189.00"), "重复退款")).isTrue();
        server.verify();
    }

    @Test
    void 远端业务拒绝_返回false且不抛异常() {
        server.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andRespond(withSuccess("{\"code\":4002,\"msg\":\"仅已支付订单可退款，当前状态=pending\"}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.refund(1001L, new BigDecimal("189.00"), "未支付订单")).isFalse();
        server.verify();
    }

    @Test
    void 支付域不可用_返回false且不抛异常() {
        server.expect(once(), requestTo("http://core-payment/api/v1/payment/refund"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.refund(1001L, new BigDecimal("189.00"), "支付域宕机")).isFalse();
        server.verify();
    }
}