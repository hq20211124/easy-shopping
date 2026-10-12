package com.easyshopping.common.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private final JwtService jwtService =
            new JwtService("unit-test-secret-0123456789abcdef-0123456789", 7200, 604800);

    @Test
    void accessTokenRoundTrip() {
        String token = jwtService.generateAccessToken(10086L, List.of("ROLE_BUYER", "ROLE_VIP"));
        JwtClaims claims = jwtService.parse(token);
        assertEquals(10086L, claims.userId());
        assertEquals(List.of("ROLE_BUYER", "ROLE_VIP"), claims.roles());
        assertTrue(claims.isAccessToken());
    }

    @Test
    void refreshTokenType() {
        String token = jwtService.generateRefreshToken(10086L);
        JwtClaims claims = jwtService.parse(token);
        assertEquals("refresh", claims.tokenType());
    }

    @Test
    void tamperedTokenRejected() {
        String token = jwtService.generateAccessToken(1L, List.of());
        String[] parts = token.split("\\.");
        String forged = parts[0] + "." + parts[1] + "." + "AAAA" + parts[2].substring(4);
        assertThrows(InvalidTokenException.class, () -> jwtService.parse(forged));
    }

    @Test
    void expiredTokenRejected() {
        JwtService shortLived = new JwtService("unit-test-secret-0123456789abcdef-0123456789", -1, 604800);
        String token = shortLived.generateAccessToken(1L, List.of());
        assertThrows(InvalidTokenException.class, () -> shortLived.parse(token));
    }

    @Test
    void garbageRejected() {
        assertThrows(InvalidTokenException.class, () -> jwtService.parse("not-a-jwt"));
    }
}
