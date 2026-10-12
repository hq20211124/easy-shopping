package com.easyshopping.android.core.network

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 金额序列化：后端 BigDecimal 落 JSON 可能是数字（7999.00）也可能是字符串（"7999.00"），
 * 两种都收，统一以字符串承载。金额**禁止 Double 参与计算**（plans/android-frontend.md 1.6.3）。
 */
@Serializer(forClass = String::class)
object AmountSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Amount", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val d = decoder as? JsonDecoder ?: return decoder.decodeString()
        return when (val el = d.decodeJsonElement()) {
            is JsonPrimitive -> if (el is JsonNull) "0" else el.content
            else -> "0"
        }
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

/** 兼容任意 JSON 值的宽松解析（服务端字段缺失/类型漂移时不炸 App）。 */
object LenientJson {
    val instance = Json {
        ignoreUnknownKeys = true        // 服务端加字段不崩
        isLenient = true
        coerceInputValues = true        // null → 字段默认值
        explicitNulls = false
    }
}

/** 金额展示：统一两位小数。入参是后端 BigDecimal 的字符串/数字字面量。 */
object Money {
    fun twoDecimals(raw: String?): String {
        if (raw.isNullOrBlank()) return "0.00"
        return try {
            BigDecimal(raw).setScale(2, RoundingMode.HALF_UP).toPlainString()
        } catch (e: NumberFormatException) {
            "0.00"
        }
    }

    /** 展示用：¥7999.00 */
    fun display(raw: String?): String = "¥" + twoDecimals(raw)

    /** 求和（仅 UI 展示兜底，权威金额一律以后端为准，客户端不自证）。 */
    fun sum(raws: List<String?>): String =
        raws.fold(BigDecimal.ZERO) { acc, s ->
            acc.add(
                if (s.isNullOrBlank()) BigDecimal.ZERO
                else runCatching { BigDecimal(s) }.getOrElse { BigDecimal.ZERO }
            )
        }.setScale(2, RoundingMode.HALF_UP).toPlainString()

    /** 计算差额：a - b，两位小数（用于倒计时/优惠展示等纯展示场景）。 */
    fun subtract(a: String?, b: String?): String {
        val x = runCatching { BigDecimal(a ?: "0") }.getOrElse { BigDecimal.ZERO }
        val y = runCatching { BigDecimal(b ?: "0") }.getOrElse { BigDecimal.ZERO }
        return x.subtract(y).setScale(2, RoundingMode.HALF_UP).toPlainString()
    }
}

/**
 * 时间解析：后端 Instant 序列化为 ISO-8601（如 2026-10-12T04:00:00Z），
 * 兼容无时区的本地时间写法；解析失败返回 null（UI 隐藏倒计时，**不猜分钟数**）。
 */
object TimeParser {
    private val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSS]")

    fun parseInstant(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(text, localFmt).atZone(ZoneId.systemDefault()).toInstant()
            }.getOrNull()
    }
}
