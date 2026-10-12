import { onUnmounted, ref } from 'vue'

/**
 * 支付倒计时：基准一律取服务端下发的 pay_deadline（裁决 C7），前端不硬编码分钟数。
 */
export function useCountdown() {
  const text = ref('')
  const expired = ref(false)
  let timer: ReturnType<typeof setInterval> | null = null

  const stop = () => {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  const render = (deadline?: string | null) => {
    if (!deadline) {
      text.value = ''
      return
    }
    const left = Math.floor((new Date(deadline).getTime() - Date.now()) / 1000)
    if (left <= 0) {
      expired.value = true
      text.value = '00:00'
      stop()
      return
    }
    const h = Math.floor(left / 3600)
    const m = Math.floor((left % 3600) / 60)
    const s = left % 60
    const p = (n: number) => String(n).padStart(2, '0')
    text.value = h > 0 ? `${p(h)}:${p(m)}:${p(s)}` : `${p(m)}:${p(s)}`
  }

  const start = (deadline?: string | null) => {
    stop()
    expired.value = false
    render(deadline)
    if (!deadline || expired.value) return
    timer = setInterval(() => render(deadline), 1000)
  }

  onUnmounted(stop)

  return { text, expired, start, stop }
}
