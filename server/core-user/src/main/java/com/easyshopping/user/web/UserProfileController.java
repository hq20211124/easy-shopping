package com.easyshopping.user.web;

import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.UserContext;
import com.easyshopping.user.dto.UserDtos.ProfileResponse;
import com.easyshopping.user.dto.UserDtos.ProfileUpdateRequest;
import com.easyshopping.user.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 买家资料（网关注入 X-User-Id；本服务不重复校验 Token，DEV.md 4.5）。 */
@RestController
public class UserProfileController {

    private final UserService userService;

    public UserProfileController(UserService userService) {
        this.userService = userService;
    }

    /** GET /api/v1/user/profile：手机号/邮箱脱敏（138****8000）。 */
    @GetMapping("/api/v1/user/profile")
    public ApiResponse<ProfileResponse> profile() {
        return ApiResponse.ok(userService.getProfile(UserContext.requireUserId()));
    }

    /** PUT /api/v1/user/profile：白名单字段更新（手机号换绑 P1）。 */
    @PutMapping("/api/v1/user/profile")
    public ApiResponse<Map<String, Object>> update(@Valid @RequestBody ProfileUpdateRequest request) {
        boolean updated = userService.updateProfile(UserContext.requireUserId(), request);
        return ApiResponse.ok(Map.of("updated", updated));
    }
}
