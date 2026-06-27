import type { RouterConfig } from '@nuxt/schema'

export default <RouterConfig>{
  scrollBehavior(to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    // /settings に設定サブページから戻る場合は settings/index.vue がスクロールを管理する
    if (to.path === '/settings' && _from?.path?.startsWith('/settings/')) {
      return false
    }
    return { top: 0 }
  },
}
