package com.easyshopping.user.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.common.idempotent.Idempotent;
import com.easyshopping.user.dto.AddressDtos.AddressRequest;
import com.easyshopping.user.dto.AddressDtos.AddressResponse;
import com.easyshopping.user.service.AddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 收货地址 CRUD（默认地址唯一；删除软删，plans/ios-backend.md 5.2.2）。 */
@RestController
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    /** GET /api/v1/user/addresses：默认地址排首；手机号脱敏出参。 */
    @GetMapping("/api/v1/user/addresses")
    public ApiResponse<Map<String, Object>> list() {
        List<AddressResponse> addresses = addressService.list(UserContext.requireUserId());
        return ApiResponse.ok(Map.of("addresses", addresses));
    }

    /** POST /api/v1/user/addresses：首条地址自动设默认；isDefault=true 时接管默认位。 */
    @PostMapping("/api/v1/user/addresses")
    @Idempotent
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody AddressRequest request) {
        Long id = addressService.create(UserContext.requireUserId(), request);
        return ApiResponse.ok(Map.of("addressId", id));
    }

    /** PUT /api/v1/user/addresses/{addressId} */
    @PutMapping("/api/v1/user/addresses/{addressId}")
    @Idempotent
    public ApiResponse<Map<String, Object>> update(@PathVariable Long addressId,
                                                   @Valid @RequestBody AddressRequest request) {
        boolean updated = addressService.update(UserContext.requireUserId(), addressId, request);
        return ApiResponse.ok(Map.of("updated", updated));
    }

    /** DELETE /api/v1/user/addresses/{addressId}：软删；默认位自动顺延。 */
    @DeleteMapping("/api/v1/user/addresses/{addressId}")
    @Idempotent
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long addressId) {
        boolean deleted = addressService.delete(UserContext.requireUserId(), addressId);
        return ApiResponse.ok(Map.of("deleted", deleted));
    }

    /** POST /api/v1/user/addresses/{addressId}/default：设为默认（plans/android-backend.md 5.2.2）。 */
    @PostMapping("/api/v1/user/addresses/{addressId}/default")
    @Idempotent
    public ApiResponse<Map<String, Object>> setDefault(@PathVariable Long addressId) {
        boolean ok = addressService.setDefault(UserContext.requireUserId(), addressId);
        return ApiResponse.ok(Map.of("success", ok));
    }
}
