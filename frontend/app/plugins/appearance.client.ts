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
  // loadFromStorage は localStorage 読み込み成功時に cookie へ書き戻す（writeCookie）ため、
  // 既存ユーザー（localStorage にダーク設定があるが cookie が未生成）でも、
  // ここを通った次回訪問の SSR から FOUC が消える。
  appearanceStore.loadFromStorage()
})
