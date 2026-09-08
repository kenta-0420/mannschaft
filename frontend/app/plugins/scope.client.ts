/**
 * クライアント起動時に {@code useScopeStore} を localStorage から復元し、
 * 以後はルート（URL）の変化に追従して現在スコープを同期する。
 *
 * <p>復元だけでは足りなかった理由（CMP-260907-0850）:
 * 現在スコープを書き込む UI が実質どこにも配置されておらず、実利用者のスコープは
 * 既定の「個人」から一生変わらなかった。ヘッダーのスコープナビ
 * （{@code Navigation/ScopeNavDropdown.vue}）は遷移するだけでストアに触れていなかったため、
 * 組織を選んでも {@code /admin/receipts} は「個人スコープでは利用できません」のままだった。</p>
 *
 * <p>そこで {@code useScopeRouteSync} により「今どのチーム／組織のページに居るか」を
 * 現在スコープの正本とする。切替 UI を新設せず、既存のナビゲーションがそのまま
 * スコープ切替になる（URL 直打ち・他画面からのリンクでも効く）。</p>
 */
export default defineNuxtPlugin(() => {
  const scopeStore = useScopeStore()
  scopeStore.loadFromStorage()

  const router = useRouter()
  const { syncFromPath } = useScopeRouteSync()

  // 初期表示（URL 直打ち・リロード）と、以後のクライアント遷移の双方で同期する。
  void syncFromPath(router.currentRoute.value.path)
  router.afterEach((to) => {
    void syncFromPath(to.path)
  })
})
