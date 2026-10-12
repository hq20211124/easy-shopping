package com.order.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 存量明文刷数的启动期触发器（<b>默认关闭</b>）。
 *
 * <p><b>开关</b>：{@code app.crypto.receiver-mobile.backfill-enabled=true}。
 * 默认 false —— 全表扫 + 逐行 UPDATE 是<b>一次性运维动作</b>，让每个实例每次启动都跑一遍
 * （或更糟：误在灰度期间打开，多实例并发刷同一批行）都属于把危险动作放进默认路径。
 * 推荐做法：需要刷数的那一次发布把开关打开，跑完（看日志里的 converted/failed 计数）后
 * 立刻改回 false 并再发一次；代码层面靠 {@link ReceiverMobileBackfillService} 的幂等性兜底。</p>
 *
 * <p><b>放在启动期而不是定时任务</b>：刷数是一次性的，挂在定时任务上会让人误以为它会自己收敛；
 * 启动期跑还能保证「刷完才开始接流量」，避免新旧格式混着被读（虽然读取侧本就兼容）。</p>
 */
@Component
@ConditionalOnProperty(prefix = "app.crypto.receiver-mobile", name = "backfill-enabled", havingValue = "true")
public class ReceiverMobileBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ReceiverMobileBackfillRunner.class);

    private final ReceiverMobileBackfillService backfillService;

    public ReceiverMobileBackfillRunner(ReceiverMobileBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.warn("检测到 app.crypto.receiver-mobile.backfill-enabled=true，开始执行存量明文手机号刷数"
                + "（一次性运维动作，刷完请关闭该开关）");
        backfillService.backfill(ReceiverMobileBackfillService.DEFAULT_BATCH_SIZE);
    }
}