import { useToast } from 'primevue/usetoast'
import type { ToastServiceMethods } from 'primevue/toastservice'
import { nextTick } from 'vue'

export default defineNuxtPlugin((nuxtApp) => {
  // useToast は setup コンテキスト外では動かないため、
  // $toast として提供し useApi 等から参照可能にする
  const toast = useToast()
  let mounted = false
  let pending: (() => void)[] = []

  function dispatch(operation: () => void) {
    if (mounted) operation()
    else pending.push(operation)
  }

  // 初回ミドルウェアと app:mounted は、親 Suspense 内の <Toast> 購読開始より先に動きうる。
  // Suspense 解決後の mounted 効果が実行されるまで待って配信する。
  // 取消も同じ順序で配信し、保留中に取り消した通知を復活させない。
  nuxtApp.hook('app:suspense:resolve', async () => {
    await nextTick()
    if (mounted) return
    mounted = true
    const operations = pending
    pending = []
    for (const operation of operations) operation()
  })

  const bufferedToast: ToastServiceMethods = {
    add: message => dispatch(() => toast.add(message)),
    remove: message => dispatch(() => toast.remove(message)),
    removeGroup: group => dispatch(() => toast.removeGroup(group)),
    removeAllGroups: () => dispatch(() => toast.removeAllGroups()),
  }

  return {
    provide: {
      toast: bufferedToast,
    },
  }
})
