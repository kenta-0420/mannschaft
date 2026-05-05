/**
 * クライアント起動時に {@code useScopeStore} を localStorage から復元する。
 *
 * <p>従来は {@code ScopeSelector.vue} の {@code onMounted} でのみ
 * {@code scopeStore.loadFromStorage()} が呼ばれており、ScopeSelector を
 * 配置しない画面（例: F08.7 シフト予算管理 admin 4 画面）ではリロード時に
 * 組織スコープが個人にリセットされてしまっていた。</p>
 *
 * <p>このプラグインで全画面共通でスコープを復元することで、ScopeSelector の
 * 有無に関わらず ↻ リロード時もスコープを保持する。</p>
 */
export default defineNuxtPlugin(() => {
  const scopeStore = useScopeStore()
  scopeStore.loadFromStorage()
})
