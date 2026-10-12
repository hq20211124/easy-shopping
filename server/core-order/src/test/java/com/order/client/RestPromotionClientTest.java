package com.order.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * 关单退券契约测试（真实 HTTP 报文）。
 *
 * <p>审计 P0-3：超时关单 / 买家取消 / 后台关闭三条路径原先只释放库存、<b>从不退券</b>，
 * 用户未付款被自动关单即永久损失一张券。本类锁定 core-promotion 退券接口的路径与请求体。</p>
 */
class RestPromotionClientTest {

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RestPromotionClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://core-promotion");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RestPromotionClient(builder.build());
    }

    @Test
    void 退券受理_命中真实路径与请求体_转发用户身份_返回true() {
        server.expect(once(), requestTo("http://core-promotion/api/v1/coupons/release"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header("X-User-Id", "10086"))
                .andExpect(jsonPath("$.couponId").value(60001))
                .andExpect(jsonPath("$.orderId").value(1001))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"couponId\":60001,\"status\":0}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.releaseCoupon(60001L, 10086L, 1001L)).isTrue();
        server.verify();
    }

    @Test
    void 退券失败_返回false且不抛异常() {
        server.expect(once(), requestTo("http://core-promotion/api/v1/coupons/release"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.releaseCoupon(60001L, 10086L, 1001L)).isFalse();
        server.verify();
    }
}