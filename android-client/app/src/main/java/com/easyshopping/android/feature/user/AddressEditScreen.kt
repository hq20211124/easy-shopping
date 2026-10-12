package com.easyshopping.android.feature.user

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.mvi.MviViewModel
import com.easyshopping.android.core.network.Address
import com.easyshopping.android.core.network.AddressRequest
import com.easyshopping.android.core.network.Api
import com.easyshopping.android.core.network.safeCall
import com.easyshopping.android.core.ui.components.AppTopBar
import com.easyshopping.android.core.ui.components.LoadingState
import com.easyshopping.android.core.ui.components.PrimaryButton
import com.easyshopping.android.core.ui.theme.TextSecondary
import com.easyshopping.android.di.ServiceLocator
import kotlinx.coroutines.launch

data class AddressEditUiState(
    val loading: Boolean = false,
    val existing: Address? = null,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String = "",
    val messageId: Int = 0,
)

sealed interface AddressEditIntent {
    data object Load : AddressEditIntent
    data class Save(val name: String, val mobile: String, val province: String, val city: String, val district: String, val detail: String, val isDefault: Boolean) : AddressEditIntent
}

sealed interface AddressEditEffect {
    data object Saved : AddressEditEffect
    data class Toast(val message: String) : AddressEditEffect
}

/**
 * 地址编辑页（新增 addressId=null / 编辑 addressId 非空）。
 * 提交字段与 core-user AddressDtos 对齐；isDefault 仅在新增/编辑时可勾选。
 */
class AddressEditViewModel(
    private val addressId: Long?,
    private val api: Api = ServiceLocator.api,
) : MviViewModel<AddressEditUiState, AddressEditIntent, AddressEditEffect>(AddressEditUiState()) {

    init {
        if (addressId != null) load()
    }

    override fun onIntent(intent: AddressEditIntent) {
        when (intent) {
            AddressEditIntent.Load -> load()
            is AddressEditIntent.Save -> save(intent)
        }
    }

    private fun load() {
        val id = addressId ?: return
        setState { copy(loading = true) }
        viewModelScope.launch {
            try {
                // 地址列表里找目标（无单查接口）
                val list = safeCall { api.addresses() }.addresses
                setState { copy(loading = false, existing = list.firstOrNull { it.id == id }) }
            } catch (e: Throwable) {
                setState { copy(loading = false, error = e.toApiMessage()) }
            }
        }
    }

    private fun save(intent: AddressEditIntent.Save) {
        // 前端基础校验（服务端仍会二次校验，错误码以服务端为准）
        if (intent.name.isBlank()) return toast("请填写收货人姓名")
        if (!intent.mobile.matches(Regex("^1\\d{10}$"))) return toast("请填写正确的手机号")
        if (intent.province.isBlank() || intent.city.isBlank()) return toast("请填写省/市")
        if (intent.detail.isBlank()) return toast("请填写详细地址")

        val body = AddressRequest(
            name = intent.name.trim(),
            mobile = intent.mobile.trim(),
            province = intent.province.trim(),
            city = intent.city.trim(),
            district = intent.district.trim().ifEmpty { null },
            detail = intent.detail.trim(),
            isDefault = intent.isDefault,
        )
        setState { copy(saving = true, error = "") }
        viewModelScope.launch {
            try {
                if (addressId == null) safeCall { api.createAddress(body) }
                else safeCall { api.updateAddress(addressId, body) }
                setState { copy(saving = false, saved = true) }
                sendEffect(AddressEditEffect.Saved)
            } catch (e: Throwable) {
                setState { copy(saving = false, error = e.toApiMessage(), messageId = messageId + 1) }
                sendEffect(AddressEditEffect.Toast(e.toApiMessage()))
            }
        }
    }

    private fun toast(msg: String) {
        sendEffect(AddressEditEffect.Toast(msg))
    }
}

class AddressEditVmFactory(private val addressId: Long?) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AddressEditViewModel(addressId) as T
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AddressEditScreen(
    addressId: Long?,
    onBack: () -> Unit,
) {
    val vm: AddressEditViewModel = viewModel(
        key = "address_edit_${addressId ?: "new"}",
        factory = AddressEditVmFactory(addressId),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 表单字段（编辑态在 existing 加载后回填一次）
    var name by rememberSaveable { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf("") }
    var province by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    var district by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var isDefault by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.existing) {
        val a = state.existing ?: return@LaunchedEffect
        name = a.name
        mobile = a.mobile
        province = a.province
        city = a.city
        district = a.district.orEmpty()
        detail = a.detail
        isDefault = a.isDefault == true
    }
    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                AddressEditEffect.Saved -> onBack()
                is AddressEditEffect.Toast -> snackbar.showSnackbar(e.message)
            }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = if (addressId == null) "新增地址" else "编辑地址", onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingState(Modifier.padding(padding))
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Field("收货人", name, { name = it }, KeyboardType.Text)
                Spacer(Modifier.height(12.dp))
                Field("手机号", mobile, { mobile = it }, KeyboardType.Phone)
                Spacer(Modifier.height(12.dp))
                Field("省份", province, { province = it }, KeyboardType.Text)
                Spacer(Modifier.height(12.dp))
                Field("城市", city, { city = it }, KeyboardType.Text)
                Spacer(Modifier.height(12.dp))
                Field("区县（选填）", district, { district = it }, KeyboardType.Text)
                Spacer(Modifier.height(12.dp))
                Field("详细地址", detail, { detail = it }, KeyboardType.Text)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设为默认地址", fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = isDefault, onCheckedChange = { isDefault = it })
                }
                Spacer(Modifier.height(24.dp))
                PrimaryButton(
                    text = if (state.saving) "保存中…" else "保存",
                    onClick = {
                        vm.onIntent(
                            AddressEditIntent.Save(name, mobile, province, city, district, detail, isDefault),
                        )
                    },
                    loading = state.saving,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboardType: KeyboardType) {
    Column {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

