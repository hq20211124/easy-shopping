package com.easyshopping.common.auth;

/** Token 无效/过期/被篡改。网关与服务端捕获后统一返回 1003。 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }

    public InvalidTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
