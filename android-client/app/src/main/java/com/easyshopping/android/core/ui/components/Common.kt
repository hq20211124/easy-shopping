package com.easyshopping.android.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.easyshopping.android.core.network.Money
import com.easyshopping.android.core.network.OrderStatus
import com.easyshopping.android.core.ui.theme.TextSecondary

// ---------------------------------------------------------------- 顶部栏

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

// ---------------------------------------------------------------- 金额文本

/**
 * 金额文本：两位小数（后端 BigDecimal 字符串直渲染，不做 Double 换算）。
 * @param price 后端金额字面量
 * @param prefix ¥ 或划线原价等前缀
 */
@Composable
fun PriceText(
    price: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.error,
    fontSize: Int = 16,
    fontWeight: FontWeight = FontWeight.Bold,
    prefix: String = "¥",
) {
    Text(
        text = prefix + Money.twoDecimals(price),
        modifier = modifier,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
    )
}

/** 划线原价（有原价且高于现价才显示）。 */
@Composable
fun OriginPriceText(price: String?, current: String?, modifier: Modifier = Modifier) {
    val origin = runCatching { java.math.BigDecimal(price ?: "") }.getOrNull()
    val now = runCatching { java.math.BigDecimal(current ?: "") }.getOrNull()
    if (origin == null || now == null || origin <= now) return
    Text(
        text = "¥" + Money.twoDecimals(price),
        modifier = modifier,
        color = TextSecondary,
        fontSize = 12.sp,
        style = MaterialTheme.typography.bodySmall.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough),
    )
}

// ---------------------------------------------------------------- 状态 Chip

/**
 * 状态 Chip：订单状态/通用标签。订单状态色按 8 值状态机语义区分（0 基，唯一定义见 OrderStatus）。
 */
@Composable
fun StatusChip(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** 订单状态 chip：按状态值取色。 */
@Composable
fun OrderStatusChip(status: Int, statusText: String?, modifier: Modifier = Modifier) {
    val color = when (OrderStatus.of(status)) {
        OrderStatus.UNPAID -> MaterialTheme.colorScheme.error
        OrderStatus.PAID, OrderStatus.SHIPPED -> MaterialTheme.colorScheme.primary
        OrderStatus.COMPLETED -> com.easyshopping.android.core.ui.theme.SuccessGreen
        OrderStatus.REFUNDING, OrderStatus.REFUNDED -> com.easyshopping.android.core.ui.theme.WarningAmber
        else -> TextSecondary
    }
    StatusChip(text = statusText ?: OrderStatus.of(status)?.label ?: "-", modifier = modifier, color = color)
}

// ---------------------------------------------------------------- 加载 / 空 / 错误

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** 空态 / 错误态（统一：文案 + 重试按钮，plans/android-frontend.md 1.3.2 AppEmpty）。 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = message, color = TextSecondary, fontSize = 14.sp)
        if (actionText != null && onAction != null) {
            OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(actionText)
            }
        }
    }
}

// ---------------------------------------------------------------- 数量步进器

/** 数量步进器：min=1（到界禁用），max 由库存/服务端上限决定（maxItems 禁止硬编码）。 */
@Composable
fun QuantityStepper(
    quantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    min: Int = 1,
    max: Int = Int.MAX_VALUE,
    showDelete: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StepperButton(isMinus = true, enabled = quantity > min, onClick = onDecrease)
        Text(
            text = quantity.toString(),
            modifier = Modifier
                .padding(horizontal = 8.dp)
                .background(Color(0xFFF5F5F5), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = 14.sp,
        )
        StepperButton(isMinus = false, enabled = quantity < max, onClick = onIncrease)
        if (showDelete) {
            IconButton(onClick = { onDelete?.invoke() }, modifier = Modifier.padding(start = 4.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = TextSecondary)
            }
        }
    }
}

@Composable
private fun StepperButton(
    isMinus: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(
                if (enabled) Color.White else Color(0xFFF5F5F5),
                RoundedCornerShape(4.dp),
            )
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (isMinus) {
            // material-icons-core 无 Remove，手绘减号（依赖最小化，不引 extended 图标库）
            Box(
                modifier = Modifier
                    .size(width = 12.dp, height = 2.dp)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary else Color(0xFFBDBDBD),
                        RoundedCornerShape(1.dp),
                    ),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFFBDBDBD),
            )
        }
    }
}

// ---------------------------------------------------------------- 底部主按钮

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontWeight = FontWeight.Medium)
        }
    }
}
