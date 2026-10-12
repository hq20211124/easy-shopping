package com.admin.client;

import com.admin.client.ClientDtos.InternalUserDto;
import com.admin.client.ClientDtos.InternalUserWrite;
import com.admin.client.ClientDtos.VerifyResult;
import com.easyshopping.common.api.ApiResponse;
import com.easyshopping.common.api.BizErrorCode;
import com.easyshopping.common.api.BizException;
import com.easyshopping.common.api.PageResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * core-user 客户端（管理端禁止直查其 DB —— plans/admin-backend.md 1.1.2）。
 *
 * <p>调用端点清单（core-user 侧已实现，见其 README）：
 * POST /api/v1/internal/auth/verify、GET/POST/PUT/DELETE /api/v1/internal/users、
 * PUT /api/v1/internal/users/{id}/roles、POST /api/v1/internal/users/{id}/login-fail-reset。</p>
 */
@Component
public class CoreUserClient {

    private static final String SERVICE = "core-user";
    private static final ParameterizedTypeReference<ApiResponse<VerifyResult>> VERIFY_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<InternalUserDto>> USER_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<PageResult<InternalUserDto>>> USER_PAGE_TYPE =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<ApiResponse<java.util.Map<String, Object>>> MAP_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public CoreUserClient(@Value("${service.urls.core-user}") String baseUrl) {
        this.restClient = RemoteSupport.restClient(baseUrl);
    }

    /** 管理端登录凭据校验；错误码透传（2001/2002/2003…）。 */
    public VerifyResult verify(String username, String password) {
        try {
            ApiResponse<VerifyResult> resp = restClient.post()
                    .uri("/api/v1/internal/auth/verify")
                    .body(java.util.Map.of("username", username, "password", password))
                    .retrieve()
                    .body(VERIFY_TYPE);
            if (resp == null || resp.code() == 0) {
                return RemoteSupport.unwrap(resp, SERVICE);
            }
            throw switch (resp.code()) {
                case 2001 -> new BizException(BizErrorCode.USER_NOT_FOUND);
                case 2002 -> new BizException(BizErrorCode.PASSWORD_ERROR, resp.msg());
                case 2003 -> new BizException(BizErrorCode.CAPTCHA_ERROR);
                case 2004 -> new BizException(BizErrorCode.CAPTCHA_EXPIRED);
                default -> new BizException(BizErrorCode.SYSTEM_ERROR, SERVICE + ": " + resp.msg());
            };
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public InternalUserDto getUser(long userId) {
        try {
            return RemoteSupport.unwrap(
                    restClient.get().uri("/api/v1/internal/users/{id}", userId).retrieve().body(USER_TYPE),
                    SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public PageResult<InternalUserDto> listUsers(String role, String keyword, int pageNum, int pageSize) {
        try {
            return RemoteSupport.unwrap(restClient.get()
                    .uri(uri -> uri.path("/api/v1/internal/users")
                            .queryParamIfPresent("role", java.util.Optional.ofNullable(role))
                            .queryParamIfPresent("keyword", java.util.Optional.ofNullable(keyword))
                            .queryParam("pageNum", pageNum)
                            .queryParam("pageSize", pageSize)
                            .build())
                    .retrieve().body(USER_PAGE_TYPE), SERVICE);
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public long createUser(InternalUserWrite body) {
        try {
            java.util.Map<String, Object> data = RemoteSupport.unwrap(
                    restClient.post().uri("/api/v1/internal/users").body(body.asMap())
                            .retrieve().body(MAP_TYPE), SERVICE);
            return ((Number) data.get("userId")).longValue();
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public boolean updateUser(long userId, InternalUserWrite body) {
        try {
            java.util.Map<String, Object> data = RemoteSupport.unwrap(
                    restClient.put().uri("/api/v1/internal/users/{id}", userId).body(body.asMap())
                            .retrieve().body(MAP_TYPE), SERVICE);
            return Boolean.TRUE.equals(data.get("success"));
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public boolean deleteUser(long userId) {
        try {
            java.util.Map<String, Object> data = RemoteSupport.unwrap(
                    restClient.delete().uri("/api/v1/internal/users/{id}", userId)
                            .retrieve().body(MAP_TYPE), SERVICE);
            return Boolean.TRUE.equals(data.get("success"));
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    public boolean updateUserRoles(long userId, String rolesCsv) {
        try {
            java.util.Map<String, Object> data = RemoteSupport.unwrap(
                    restClient.put().uri("/api/v1/internal/users/{id}/roles", userId)
                            .body(java.util.Map.of("roles", rolesCsv))
                            .retrieve().body(MAP_TYPE), SERVICE);
            return Boolean.TRUE.equals(data.get("success"));
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }

    /** 解锁（清零失败计数），运营在连续失败锁定后使用。 */
    public boolean resetLoginFail(long userId) {
        try {
            java.util.Map<String, Object> data = RemoteSupport.unwrap(
                    restClient.post().uri("/api/v1/internal/users/{id}/login-fail-reset", userId)
                            .body(java.util.Map.of("failCount", 0))
                            .retrieve().body(MAP_TYPE), SERVICE);
            return Boolean.TRUE.equals(data.get("success"));
        } catch (RuntimeException e) {
            throw RemoteSupport.translate(e, SERVICE);
        }
    }
}
