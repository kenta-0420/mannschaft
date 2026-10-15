/**
 * @vite-pwa/nuxt の仮想モジュール virtual:pwa-register/vue のモック。
 * Vitest 環境では SW が存在しないため、空のスタブを返す。
 */
import { ref } from 'vue'

export function useRegisterSW() {
  return {
    needRefresh: ref(false),
    offlineReady: ref(false),
    updateServiceWorker: () => Promise.resolve(),
  }
}
