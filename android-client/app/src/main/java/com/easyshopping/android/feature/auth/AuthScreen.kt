package com.easyshopping.android.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.easyshopping.android.core.ui.components.AppTopBar
import com.easyshopping.android.core.ui.components.PrimaryButton
import com.easyshopping.android.core.ui.theme.BrandOrange
import com.easyshopping.android.core.ui.theme.TextSecondary

/**
 * 登录/注册页（P0：账号密码登录 + 手机号注册；短信验证码登录等三方通道见 TODO）。
 * 后端 core-user：POST /api/v1/auth/login、/api/v1/auth/register（P0 验证码固定 123456）。
 */
@Composable
fun AuthScreen(
    onLoggedIn: () -> Unit,
    onBack: (() -> Unit)? = null,
    initialRegister: Boolean = false,
    vm: AuthViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // 从「注册」路由进入时直接定位到注册 Tab（登录/注册本是同一页面的两个 Tab）
    LaunchedEffect(Unit) {
        if (initialRegister) vm.onIntent(AuthIntent.SwitchMode(AuthMode.Register))
    }

    // 一次性副作用：登录成功跳转（错误文案留在 State 里展示，避免旋转/重建后重复 Toast）
    LaunchedEffect(Unit) {
        vm.effect.collect { e ->
            when (e) {
                AuthEffect.LoggedIn -> onLoggedIn()
            }
        }
    }
    // 错误/提示文案：messageId 变化时提示一次
    LaunchedEffect(state.messageId) {
        if (state.messageId > 0 && state.message.isNotEmpty()) snackbar.showSnackbar(state.message)
    }

    Scaffold(
        topBar = {
            AppTopBar(title = if (state.mode == AuthMode.Login) "登录" else "注册", onBack = onBack)
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = if (state.mode == AuthMode.Login) "欢迎回来" else "创建账号",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.mode == AuthMode.Login) "使用手机号/用户名登录" else "手机号注册（P0 验证码固定 123456）",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))

            TabRow(selectedTabIndex = if (state.mode == AuthMode.Login) 0 else 1) {
                Tab(selected = state.mode == AuthMode.Login,
                    onClick = { vm.onIntent(AuthIntent.SwitchMode(AuthMode.Login)) },
                    text = { Text("登录") })
                Tab(selected = state.mode == AuthMode.Register,
                    onClick = { vm.onIntent(AuthIntent.SwitchMode(AuthMode.Register)) },
                    text = { Text("注册") })
            }
            Spacer(Modifier.height(24.dp))

            if (state.mode == AuthMode.Login) {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { vm.onIntent(AuthIntent.UsernameChanged(it)) },
                    label = { Text("手机号 / 用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { vm.onIntent(AuthIntent.PasswordChanged(it)) },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = { vm.onIntent(AuthIntent.UsernameChanged(it)) },
                    label = { Text("手机号") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.verifyCode,
                    onValueChange = { vm.onIntent(AuthIntent.CodeChanged(it)) },
                    label = { Text("短信验证码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = { vm.onIntent(AuthIntent.PasswordChanged(it)) },
                    label = { Text("密码（6~32 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.error.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(state.error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            Spacer(Modifier.height(32.dp))
            PrimaryButton(
                text = if (state.mode == AuthMode.Login) "登 录" else "注 册",
                onClick = { vm.onIntent(AuthIntent.Submit) },
                loading = state.loading,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "登录即代表同意《用户协议》与《隐私政策》",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}
