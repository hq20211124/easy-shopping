package com.order.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * {@code order_main.receiver_mobile} 的 JPA {@link AttributeConverter}：列里放密文，实体里放明文。
 *
 * <p><b>为什么把加解密放在持久化边界（而不是在 Service 里逐处调用）</b>：本字段有 6+ 条读写路径
 * （下单父单、管理端列表/详情、管理端明文专用接口、内部快照、日志/事件载荷…）。若由调用方各自
 * 「记得加密/记得解密」，漏一处就是一次明文外泄，而且审计上无法逐点证明覆盖。
 * 放在 Converter 里后：</p>
 * <ol>
 *   <li><b>写入自动加密</b>——任何 {@code orderMainRepository.save(...)} 落库的都是密文，
 *       不存在「忘了调 encrypt」的分支；</li>
 *   <li><b>读取自动解密</b>——实体内存里永远是明文，故 {@code OrderQueryService} 的
 *       {@code maskMobile(getReceiverMobile())} 与
 *       {@code receiverMobileForShip}（带权限+留痕的明文口）<b>一行都不用改</b>，
 *       既不用动脱敏口径，也不会出现「把 v1: 串当手机号渲染」的隐蔽事故；</li>
 *   <li>存量明文行在<b>读取时</b>按明文直出（{@link ReceiverMobileCipher#decrypt} 的前缀判定），
 *       明文/密文同列共存，无需停机刷数。</li>
 * </ol>
 *
 * <p><b>密文列宽</b>：见 {@link com.order.domain.OrderMain#getReceiverMobile()}（varchar(255)）。
 * 存量 varchar(20) 的库需先扩列，见模块 README「11. 收货人手机号落库加密」。</p>
 *
 * <p><b>本 Converter 必须是 Spring Bean</b>：靠 Spring Boot 默认的 {@code SpringBeanContainer}
 * 被 Hibernate 回调注入 {@link ReceiverMobileCipher}；若容器装配失败会直接启动报错（不会退化成
 * 「没有密钥的裸 Converter 静默写明文」）。</p>
 */
@Converter(autoApply = false)
@Component
public class ReceiverMobileConverter implements AttributeConverter<String, String> {

    private final ReceiverMobileCipher cipher;

    public ReceiverMobileConverter(ReceiverMobileCipher cipher) {
        this.cipher = cipher;
    }

    /** 实体明文 → 列密文。 */
    @Override
    public String convertToDatabaseColumn(String plaintext) {
        return cipher.encrypt(plaintext);
    }

    /** 列密文（或存量明文）→ 实体明文。 */
    @Override
    public String convertToEntityAttribute(String stored) {
        return cipher.decrypt(stored);
    }
}