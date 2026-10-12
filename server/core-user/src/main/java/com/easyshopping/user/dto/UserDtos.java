package com.easyshopping.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** 用户资料出入参（plans/ios-backend.md 5.2.1 / plans/android-backend.md 5.1.3）。 */
public final class UserDtos {

    private UserDtos() {
    }

    /**
     * 买家资料（P0 会员等级/积分为占位，TODO(P1) core-member 会员域下发 levelName/points/growthValue）。
     * 字段级权限：手机号/邮箱出参一律脱敏；cost 类内部字段（如注册渠道成本归属）无权限不返回
     * （plans/admin-backend.md 2.2.3 字段级权限，管理端商品 cost:read 同规则）。
     */
    public record ProfileResponse(
            Long userId,
            String username,
            String nickname,
            String avatar,
            String mobile,
            String email,
            Integer gender,
            String birthday,
            String registerDate,
            Integer level,
            String levelName,
            Integer points,
            Boolean verified) {
    }

    /** PUT /api/v1/user/profile（白名单字段；手机号换绑 P1：验证码+风控） */
    public record ProfileUpdateRequest(
            @Pattern(regexp = "^.{1,32}$", message = "昵称长度 1~32") String nickname,
            String avatar,
            @Email(message = "邮箱格式不正确") String email,
            @Min(0) @Max(2) Integer gender,
            @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "生日格式 yyyy-MM-dd") String birthday) {
    }
}
