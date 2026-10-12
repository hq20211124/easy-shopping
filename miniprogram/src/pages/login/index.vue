<script setup lang="ts">
import { ref } from 'vue'
import { useUserStore } from '@/store/user'
import { useCartStore } from '@/store/cart'

/** P0：手机号+密码登录 / 手机号+验证码注册（验证码固定 123456，core-user README）。 */
const userStore = useUserStore()
const cartStore = useCartStore()

const mode = ref<'login' | 'register'>('login')
const mobile = ref('')
const password = ref('')
const code = ref('')
const submitting = ref(false)

const PHONE_RE = /^1[3-9]\d{9}$/

async function submit() {
  if (submitting.value) return
  if (!PHONE_RE.test(mobile.value)) {
    uni.showToast({ title: '请输入正确手机号', icon: 'none' })
    return
  }
  if (mode.value === 'login') {
    if (!password.value) {
      uni.showToast({ title: '请输入密码', icon: 'none' })
      return
    }
  } else if (!code.value || !password.value) {
    uni.showToast({ title: '请输入验证码与密码', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    if (mode.value === 'login') {
      await userStore.loginByPassword(mobile.value, password.value)
    } else {
      await userStore.register(mobile.value, code.value, password.value)
    }
    uni.showToast({ title: '登录成功', icon: 'success' })
    cartStore.refreshCount()
    setTimeout(() => uni.switchTab({ url: '/pages/index/index' }), 400)
  } catch {
    /* 错误 toast 已由请求层统一处理 */
  } finally {
    submitting.value = false
  }
}

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
}
</script>

<template>
  <view class="login">
    <view class="login__logo">🛒</view>
    <view class="login__title">易购商城</view>
    <view class="login__sub">{{ mode === 'login' ? '手机号 + 密码登录' : '注册新账户（验证码 123456）' }}</view>

    <view class="login__form">
      <view class="login__field">
        <input v-model="mobile" class="login__input" type="number" maxlength="11" placeholder="请输入手机号" />
      </view>
      <view v-if="mode === 'register'" class="login__field">
        <input v-model="code" class="login__input" type="number" maxlength="6" placeholder="请输入验证码（P0：123456）" />
      </view>
      <view class="login__field">
        <input v-model="password" class="login__input" type="password" placeholder="请输入密码（6~32 位）" />
      </view>

      <button class="login__btn" :disabled="submitting" @click="submit">
        {{ mode === 'login' ? '登 录' : '注 册' }}
      </button>
      <view class="login__switch" @click="switchMode">
        {{ mode === 'login' ? '没有账户？去注册' : '已有账户？去登录' }}
      </view>
    </view>
  </view>
</template>

<style scoped>
.login {
  padding: 120rpx 60rpx 0;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.login__logo {
  font-size: 120rpx;
}
.login__title {
  font-size: 40rpx;
  font-weight: 600;
  margin-top: 16rpx;
}
.login__sub {
  font-size: 24rpx;
  color: #999;
  margin-top: 8rpx;
}
.login__form {
  width: 100%;
  margin-top: 64rpx;
}
.login__field {
  border-bottom: 1rpx solid #eee;
  padding: 24rpx 0;
}
.login__input {
  font-size: 30rpx;
}
.login__btn {
  margin-top: 64rpx;
  background: #e4393c;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.login__btn[disabled] {
  background: #f5a8a8;
  color: #fff;
}
.login__switch {
  text-align: center;
  margin-top: 32rpx;
  color: #576b95;
  font-size: 26rpx;
}
</style>
