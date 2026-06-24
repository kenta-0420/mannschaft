/**
 * テーマ クライアント初期化プラグイン — .client.ts（ブラウザ専用）
 *
 * localStorage（正本）を読み込んで store と DOM を確定させる。
 * また cookie（SSR への鏡）との整合を維持するため、
 * localStorage に保存データがある場合は cookie にも再同期する。
 *
 * 【役割分担】
 *   - app/plugins/appearance.ts（universal）: SSR 時に cookie → useHead で <html> を確定
 *   - 本プラグイン（client）: localStorage（正本）→ store → DOM クラス・CSS 変数を確定
 *
 * localStorage > cookie の優先度とする（localStorage が正本）。
 * hydration 後にここで上書きされるため、SSR と client で一時的に差異が生じても問題ない。
 */
export default defineNuxtPlugin(() => {
  const appearanceStore = useAppearanceStore()
  // localStorage の正本から読み込んで DOM クラスと CSS 変数を確定させる。
  // persistToStorage が localStorage と cookie を同時に書くため、
  // ここで loadFromStorage を呼べば cookie も自動的に最新に保たれる。
  appearanceStore.loadFromStorage()
})
