import { useToast } from 'primevue/usetoast'
import type { ToastServiceMethods } from 'primevue/toastservice'

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

  // 初回ミドルウェアは <Toast> の購読開始より先に動く。
  // 取消も同じ順序で配信し、保留中に取り消した通知を復活させない。
  nuxtApp.hook('app:mounted', () => {
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
