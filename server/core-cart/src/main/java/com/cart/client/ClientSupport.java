package com.cart.client;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * 服务间调用公共支撑（DEV.md 3：RestClient + 静态地址，超时 连接1s/读3s，只有幂等接口可重试）。
 *
 * <p>统一解析 common 响应信封 {@code {code, msg, data, traceId}}：{@code code != 0} 时把远端业务码
 * 映射为本地 {@link BizException}（3001/3002/3003 直传，其余按 9999）。</p>
 */
final class ClientSupport {

    private ClientSupport() {
    }

    static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    static <T> T unwrap(ApiResponse<T> response, String remote) {
        if (response == null) {
            throw new BizException(BizErrorCode.SYSTEM_ERROR, remote + " 无响应");
        }
        if (response.code() != BizErrorCode.SUCCESS.getCode()) {
            throw new BizException(mapCode(response.code()), remote + ": " + response.msg());
        }
        return response.data();
    }

    /** 远端业务码 → 本地码（前端已依赖的语义不可变更，DEV.md 4.6）。 */
    private static BizErrorCode mapCode(int code) {
        return switch (code) {
            case 3001 -> BizErrorCode.PRODUCT_NOT_FOUND;
            case 3002 -> BizErrorCode.OUT_OF_STOCK;
            case 3003 -> BizErrorCode.PRODUCT_OFF_SHELF;
            case 3004 -> BizErrorCode.CART_ITEM_NOT_FOUND;
            default -> BizErrorCode.SYSTEM_ERROR;
        };
    }

    static String joinIds(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
