package com.easyshopping.android.feature.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Address
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.core.ui.components.AppTopBar
import com.easyshopping.android.core.ui.components.EmptyState
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class AddressListUiState(
    val loading: Boolean = false,
    val addresses: List<Address> = emptyList(),
    val opAddressId: Long? = null,
    val error: String = "",
    val messageId: Int = 0,
)

sealed interface AddressListIntent {
    data object Refresh : AddressListIntent
    data class SetDefault(val addressId: Long) : AddressListIntent
    data class Delete(val address: Address) : AddressListIntent
}

sealed interface AddressListEffect {
    data class Toast(val message: String) : AddressListEffect
    data object RequireLogin : AddressListEffect
}

/**
 * 地址列表（plans/android-frontend.md 4.9.2）：默认标记 + 设默认 + 删除。
 * 删除由服务端校验"是否有未完成订单引用"，客户端只透传结果。
 */
class AddressListViewModel(
    private val api: Api = ServiceLocator.api,
) : MviViewModel<AddressListUiState, AddressListIntent, AddressListEffect>(AddressListUiState()) {

    init {
        refresh()
    }

    override fun onIntent(intent: AddressListIntent) {
        when (intent) {
            AddressListIntent.Refresh -> refresh()
            is AddressListIntent.SetDefault -> setDefault(intent.addressId)
            is AddressListIntent.Delete -> delete(intent.address)
        }
    }

    private fun refresh() {
        setState { copy(loading = addresses.isEmpty(), error = "") }
        viewModelScope.launch {
            try {
                val resp = safeCall { api.addresses() }
                setState { copy(loading = false, addresses = resp.addresses) }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
                if (isReloginError(e)) sendEffect(AddressListEffect.RequireLogin)
            }
        }
    }

    private fun setDefault(addressId: Long) {
        setState { copy(opAddressId = addressId) }
        viewModelScope.launch {
            try {
                safeCall { api.setDefaultAddress(addressId) }
                refresh()
            } catch (e: Throwable) {
                sendEffect(AddressListEffect.Toast(e.toApiMessage()))
                setState { copy(opAddressId = null) }
            }
        }
    }

    private fun delete(address: Address) {
        setState { copy(opAddressId = address.id) }
        viewModelScope.launch {
            try {
                safeCall { api.deleteAddress(address.id) }
                sendEffect(AddressListEffect.Toast("地址已删除"))
                refresh()
            } catch (e: Throwable) {
                // 服务端会拦截"存在未完成订单引用"的删除
                sendEffect(AddressListEffect.Toast(e.toApiMessage()))
                setState { copy(opAddressId = null) }
            }
        }
    }
}

class AddressListVmFactory : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AddressListViewModel() as T
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddressListScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onCreate: () -> Unit,
    vm: AddressListViewModel = viewModel(factory = AddressListVmFactory()),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                is AddressListEffect.Toast -> snackbar.showSnackbar(e.message)
                AddressListEffect.RequireLogin -> snackbar.showSnackbar("登录已失效，请重新登录")
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "地址管理",
                onBack = onBack,
                actions = {
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Filled.Add, contentDescription = "新增地址", tint = BrandOrange)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading && state.addresses.isEmpty() -> LoadingState(Modifier.padding(padding))
            state.error.isNotEmpty() && state.addresses.isEmpty() -> EmptyState(
                message = state.error,
                actionText = "重试",
                onAction = { vm.onIntent(AddressListIntent.Refresh) },
                modifier = Modifier.padding(padding),
            )

            state.addresses.isEmpty() -> EmptyState(
                message = "还没有收货地址",
                actionText = "新增地址",
                onAction = onCreate,
                modifier = Modifier.padding(padding),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(state.addresses.size, key = { state.addresses[it].id }) { i ->
                    val a = state.addresses[i]
                    AddressRow(
                        address = a,
                        busy = state.opAddressId == a.id,
                        onClick = { onEdit(a.id) },
                        onSetDefault = { vm.onIntent(AddressListIntent.SetDefault(a.id)) },
                        onDelete = { vm.onIntent(AddressListIntent.Delete(a)) },
                    )
                    HorizontalDivider(color = com.easyshopping.android.core.ui.theme.Divider)
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: Address,
    busy: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(address.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.width(10.dp))
            Text(address.mobile, color = TextSecondary, fontSize = 13.sp)
            if (address.isDefault == true) {
                Spacer(Modifier.width(6.dp))
                Text("默认", color = BrandOrange, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(address.fullAddress, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (address.isDefault != true) {
                Text(
                    "设为默认",
                    color = BrandOrange,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clickable(enabled = !busy) { onSetDefault() }
                        .padding(end = 16.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "删除",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable(enabled = !busy) { onDelete() }
                    .padding(start = 16.dp),
            )
        }
    }
}
