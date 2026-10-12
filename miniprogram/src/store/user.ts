import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { authApi } from '@/api/auth'
import type { TokenResponse } from '@/api/types'
import { clearTokens, getToken, setTokens } from '@/api/client'

const PROFILE_KEY = 'ec_profile'

export const useUserStore = defineStore('user', () => {
  const token = ref('')
  const userId = ref(0)
  const username = ref('')
  const nickname = ref('')

  const isLoggedIn = computed(() => Boolean(token.value))

  function applyLogin(res: TokenResponse) {
    token.value = res.accessToken
    userId.value = res.userId
    username.value = res.username
    nickname.value = res.nickname || res.username
    setTokens(res.accessToken, res.refreshToken)
    uni.setStorageSync(PROFILE_KEY, { userId: res.userId, username: res.username, nickname: nickname.value })
  }

  /** 启动时从本地存储恢复登录态（App.vue onLaunch 调用） */
  function restore() {
    token.value = getToken()
    const profile = uni.getStorageSync(PROFILE_KEY) as { userId: number; username: string; nickname: string } | ''
    if (profile) {
      userId.value = profile.userId
      username.value = profile.username
      nickname.value = profile.nickname
    }
  }

  async function loginByPassword(mobile: string, password: string) {
    applyLogin(await authApi.login(mobile, password))
  }

  /** 注册（P0 验证码固定 123456），成功即视为登录 */
  async function register(mobile: string, code: string, password: string) {
    applyLogin(await authApi.register(mobile, code, password))
  }

  async function logout() {
    try {
      await authApi.logout()
    } catch {
      /* 静默登出：refreshToken 丢失也允许 */
    }
    clearTokens()
    uni.removeStorageSync(PROFILE_KEY)
    token.value = ''
    userId.value = 0
    username.value = ''
    nickname.value = ''
  }

  return { token, userId, username, nickname, isLoggedIn, restore, loginByPassword, register, logout }
})
