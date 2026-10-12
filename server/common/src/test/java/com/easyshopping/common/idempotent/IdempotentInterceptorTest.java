package com.easyshopping.common.idempotent;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link IdempotentInterceptor} 两态语义回归测试（P1-2 修复）。
 *
 * <p>覆盖三条必须成立的性质：
 * <ol>
 *   <li>业务失败后同键重试<b>可成功</b>（占位被释放，用户能用同一提交意图重试）；</li>
 *   <li>成功后同键重试<b>仍冲突</b>（1005/409，重复提交被挡住）；</li>
 *   <li>并发同键<b>只放行一个</b>（PROCESSING 期间拒绝，保持原有语义）。</li>
 * </ol>
 * 另补：系统异常（非 BizException）保留 PROCESSING 占位，同键重试仍冲突（防重复提交副作用）。
 */
class IdempotentInterceptorTest {

    private MockMvc mockMvc;
    private TestController controller;

    @BeforeEach
    void setUp() {
        controller = new TestController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new IdempotentInterceptor())
                .build();
    }

    // ---------- ① 业务失败后同键重试可成功 ----------

    @Test
    void 业务失败后同键重试可成功_占位已释放() throws Exception {
        // 第一次：业务异常（库存不足 3002，秒杀场景最高频的业务失败）
        mockMvc.perform(idem("/test/biz-error", "key-biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(BizErrorCode.OUT_OF_STOCK.getCode()));

        // 同一提交意图（同 key）重试：必须真正进入业务逻辑，而不是 1005
        mockMvc.perform(idem("/test/biz-error", "key-biz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.attempts").value(2));
    }

    // ---------- ② 成功后同键重试仍冲突 ----------

    @Test
    void 成功后同键重试仍冲突_返回1005() throws Exception {
        mockMvc.perform(idem("/test/ok", "key-ok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(idem("/test/ok", "key-ok"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(BizErrorCode.IDEMPOTENT_CONFLICT.getCode()));
        assertThat(controller.attempts.get()).isEqualTo(1); // 业务方法只被调用过一次
    }

    // ---------- ③ 并发同键只放行一个 ----------

    @Test
    void 并发同键只放行一个() throws Exception {
        controller.block = new CountDownLatch(1); // 第一个请求停在业务里，保持 PROCESSING
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> statusOf(idem("/test/ok", "key-race")));
            // 等第一个请求真正进入业务（已拿到占位）再发第二个
            assertThat(controller.entered.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = pool.submit(() -> statusOf(idem("/test/ok", "key-race")));

            int secondStatus = second.get(10, TimeUnit.SECONDS);
            controller.block.countDown();
            int firstStatus = first.get(10, TimeUnit.SECONDS);

            assertThat(List.of(firstStatus, secondStatus)).containsExactlyInAnyOrder(200, 409);
        } finally {
            controller.block.countDown();
            pool.shutdownNow();
        }
    }

    // ---------- 系统异常保留占位 ----------

    @Test
    void 系统异常后占位保留_同键重试仍冲突() throws Exception {
        controller.mode = TestController.Mode.SYSTEM_ERROR;
        mockMvc.perform(idem("/test/ok", "key-sys"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(BizErrorCode.SYSTEM_ERROR.getCode()));

        // 系统异常意味着可能已产生部分副作用：占位保留到 TTL 过期，同键重试继续被拒
        controller.mode = TestController.Mode.OK;
        mockMvc.perform(idem("/test/ok", "key-sys"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(BizErrorCode.IDEMPOTENT_CONFLICT.getCode()));
    }

    private int statusOf(MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder idem(String uri, String key) {
        return post(uri).header("Idempotency-Key", key);
    }

    @RestController
    @RequestMapping("/test")
    static class TestController {

        enum Mode { OK, SYSTEM_ERROR }

        final AtomicInteger attempts = new AtomicInteger();
        volatile Mode mode = Mode.OK;
        volatile CountDownLatch block;
        final CountDownLatch entered = new CountDownLatch(1);

        @PostMapping("/ok")
        @Idempotent
        public ApiResponse<Resp> ok() throws InterruptedException {
            int attempt = attempts.incrementAndGet();
            if (block != null) {
                entered.countDown();
                block.await();
            }
            return switch (mode) {
                case OK -> ApiResponse.ok(new Resp(attempt));
                case SYSTEM_ERROR -> throw new IllegalStateException("下游 DB 抖动");
            };
        }

        @PostMapping("/biz-error")
        @Idempotent
        public ApiResponse<Resp> bizError() {
            int attempt = attempts.incrementAndGet();
            if (attempt <= 1) {
                throw new BizException(BizErrorCode.OUT_OF_STOCK, "库存不足");
            }
            return ApiResponse.ok(new Resp(attempt));
        }

        record Resp(int attempts) {
        }
    }
}
