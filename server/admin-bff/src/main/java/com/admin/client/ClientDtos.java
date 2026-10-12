package com.admin.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 服务间调用 DTO（core-user 内部端点契约，见 core-user/README.md）。 */
public final class ClientDtos {

    private ClientDtos() {
    }

    /** GET /api/v1/internal/users 的行结构（camelCase，core-user Jackson 默认）。 */
    public record InternalUserDto(
            Long userId,
            String username,
            String nickname,
            String mobile,
            String email,
            Integer gender,
            String birthday,
            String avatar,
            Integer status,
            List<String> roles,
            Integer loginFailCount,
            Instant lastLoginAt,
            Instant createdAt) {
    }

    /** POST /api/v1/internal/auth/verify 响应。 */
    public record VerifyResult(Long userId, String username, String nickname, List<String> roles, Integer status) {
    }

    /** admin-bff 系统管理 → core-user 的账号写入请求体。 */
    public record InternalUserWrite(String username, String password, String nickname,
                                    String mobile, String roles, Integer status,
                                    String email, Integer gender, String birthday) {

        public static InternalUserWrite create(String username, String password, String nickname, String mobile) {
            return new InternalUserWrite(username, password, nickname, mobile, "ADMIN", null, null, null, null);
        }

        public Map<String, Object> asMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            putIfPresent(map, "username", username);
            putIfPresent(map, "password", password);
            putIfPresent(map, "nickname", nickname);
            putIfPresent(map, "mobile", mobile);
            putIfPresent(map, "roles", roles);
            putIfPresent(map, "status", status);
            putIfPresent(map, "email", email);
            putIfPresent(map, "gender", gender);
            putIfPresent(map, "birthday", birthday);
            return map;
        }

        private static void putIfPresent(Map<String, Object> map, String key, Object value) {
            if (value != null) {
                map.put(key, value);
            }
        }
    }
}
