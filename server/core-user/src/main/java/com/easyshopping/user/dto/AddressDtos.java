package com.easyshopping.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** 地址出入参（plans/ios-backend.md 5.2.2；mobile 出参脱敏、入参明文）。 */
public final class AddressDtos {

    private AddressDtos() {
    }

    public record AddressRequest(
            @NotBlank @Size(max = 32) String name,
            @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确") String mobile,
            @NotBlank @Size(max = 32) String province,
            @NotBlank @Size(max = 32) String city,
            @Size(max = 32) String district,
            @NotBlank @Size(max = 256) String detail,
            @Size(max = 10) String zipCode,
            Boolean isDefault,
            BigDecimal latitude,
            BigDecimal longitude) {
    }

    public record AddressResponse(
            Long id,
            String name,
            String mobile,
            String province,
            String city,
            String district,
            String detail,
            String zipCode,
            Boolean isDefault) {
    }
}
