package com.cart.web;

import com.cart.dto.CartDtos.AddRequest;
import com.cart.dto.CartDtos.CartListResponse;
import com.cart.dto.CartDtos.RemoveRequest;
import com.cart.dto.CartDtos.SelectRequest;
import com.cart.dto.CartDtos.UpdateRequest;
import com.cart.service.CartService;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.idempotent.Idempotent;
import com.easyshopping.common.api.UserContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 购物车接口（契约双路径，同一个 handler 挂两套映射）。
 *
 * <ul>
 *   <li>POST 动作式（plans/android-frontend 1.6 P0：cart/list、cart/add、cart/update、cart/remove、cart/select）</li>
 *   <li>RESTful（plans/ios-backend 4.2.1：GET/POST /cart、PUT/DELETE /cart/{cartItemId}）</li>
 * </ul>
 *
 * <p>列表为 GET（不要求幂等头）；写操作全部 {@code @Idempotent}（Idempotency-Key 头，DEV.md 6）。
 * P0 不发购物车域事件（{@code DomainEventNames} 无 cart.* 常量，不自造事件名）。</p>
 */
@RestController
@RequestMapping
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    // ------------------------------------------------------------------ 列表

    @GetMapping({"/api/v1/cart/list", "/api/v1/cart"})
    public ApiResponse<CartListResponse> list() {
        return ApiResponse.ok(cartService.list(UserContext.requireUserId()));
    }

    // ------------------------------------------------------------------ 加入购物车

    @Idempotent
    @PostMapping({"/api/v1/cart/add", "/api/v1/cart", "/api/v1/cart/items"})
    public ApiResponse<?> add(@RequestBody AddRequest request) {
        return ApiResponse.ok(cartService.add(UserContext.requireUserId(), request));
    }

    // ------------------------------------------------------------------ 修改 / 勾选

    @Idempotent
    @PostMapping("/api/v1/cart/update")
    public ApiResponse<?> update(@RequestBody UpdateRequest request) {
        return ApiResponse.ok(cartService.update(UserContext.requireUserId(),
                requireCartItemId(request), request));
    }

    @Idempotent
    @PutMapping({"/api/v1/cart/{cartItemId}", "/api/v1/cart/items/{cartItemId}"})
    public ApiResponse<?> updateByPath(@PathVariable("cartItemId") Long cartItemId,
                                  @RequestBody UpdateRequest request) {
        return ApiResponse.ok(cartService.update(UserContext.requireUserId(), cartItemId, request));
    }

    @Idempotent
    @PostMapping("/api/v1/cart/select")
    public ApiResponse<?> select(@RequestBody(required = false) SelectRequest request) {
        return ApiResponse.ok(cartService.select(UserContext.requireUserId(),
                request == null ? new SelectRequest(null, null) : request));
    }

    // ------------------------------------------------------------------ 移除

    @Idempotent
    @PostMapping("/api/v1/cart/remove")
    public ApiResponse<?> remove(@RequestBody RemoveRequest request) {
        return ApiResponse.ok(cartService.remove(UserContext.requireUserId(), request));
    }

    @Idempotent
    @DeleteMapping({"/api/v1/cart/{cartItemId}", "/api/v1/cart/items/{cartItemId}"})
    public ApiResponse<?> removeByPath(@PathVariable("cartItemId") Long cartItemId) {
        return ApiResponse.ok(cartService.remove(UserContext.requireUserId(),
                new RemoveRequest(List.of(cartItemId))));
    }

    /**
     * POST /cart/update 以 body 定位行。契约要求 cartItemId 定位（android-backend 16.4），
     * 缺失时给出可读的参数错误而非 500。
     */
    private static Long requireCartItemId(UpdateRequest request) {
        Long cartItemId = request == null ? null : request.cartItemId();
        if (cartItemId == null) {
            throw new BizException(BizErrorCode.PARAM_ERROR, "cartItemId 不能为空");
        }
        return cartItemId;
    }
}
