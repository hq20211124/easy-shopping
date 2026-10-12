<script setup lang="ts">
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { useUserStore } from '@/store/user'
import { useCartStore } from '@/store/cart'

onLaunch(() => {
  // 启动时恢复本地登录态；游客态不预取 user/cart 接口（plans 14.2）
  const user = useUserStore()
  user.restore()
  if (user.isLoggedIn) {
    useCartStore().refreshCount()
  }
})

onShow(() => {
  /* 小程序回到前台，P0 无需额外刷新 */
})
</script>

<style>
page {
  background-color: #f5f5f5;
  font-size: 28rpx;
  color: #333;
}
</style>
