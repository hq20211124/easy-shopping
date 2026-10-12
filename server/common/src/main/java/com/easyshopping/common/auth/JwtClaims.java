package com.easyshopping.common.auth;

import java.util.List;

/**
 * 解析后的 JWT 声明。
 *
 * @param userId          用户 ID（sub）
 * @param roles           角色列表
 * @param tokenType       {@code access} / {@code refresh}
 * @param expiresAtEpochSecond 过期时间（epoch 秒）
 * @param jti             Token 唯一 ID（登出黑名单/单端互踢用，P1 落 Redis）
 */
public record JwtClaims(long userId, List<String> roles, String tokenType,
                        long expiresAtEpochSecond, String jti) {

    public boolean isAccessToken() {
        return JwtService.TYPE_ACCESS.equals(tokenType);
    }
}
