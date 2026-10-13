/**
 * システム管理（`/system-admin` 配下）のアクセス制御ミドルウェア（CMP-260930-1933 / ALIC-1）。
 *
 * 実証済みの欠陥: 一般会員が URL 直打ちで `/system-admin` を開くと、ダッシュボードと管理メニュー一式が
 * 描画されていた（データ API は BE が 403 で弾くためデータ漏れは無いが、管理導線が見えてしまう）。
 *
 * 方針（`admin-console.ts` / `org-role-guard.ts` と揃える）:
 * - `useAuthStore().isSystemAdmin` で判定する。SYSTEM_ADMIN 以外はダッシュボードへ戻し、エラートーストを出す。
 * - 404 による存在秘匿はしない（列挙防止の本丸は BE の認可）。
 * - 未ログインは `auth` ミドルウェアが先に `/login` へ送る。必ず `['auth', 'system-admin']` の順で指定する。
 *
 * これは UX（誤遷移の早期遮断）のための表示制御であり、認可の最終判断ではない。BE は各 API で必ず認可する。
 */
export default defineNuxtRouteMiddleware(() => {
  // ユーザー情報は client の認証復元に依存するため SSR では素通りし、client で確定させる（auth.ts と同じ作法）。
  if (import.meta.server) return

  const authStore = useAuthStore()
  // 未認証は auth ミドルウェアの担当。ここでは「ログイン済みだが SYSTEM_ADMIN ではない」場合だけを扱う。
  if (!authStore.isAuthenticated) return
  if (authStore.isSystemAdmin) return

  const nuxtApp = useNuxtApp()
  const t = (key: string): string => nuxtApp.$i18n.t(key)
  const toast = nuxtApp.$toast as
    | { add: (opts: Record<string, unknown>) => void }
    | undefined
  if (toast) {
    toast.add({
      severity: 'error',
      summary: t('adminConsole.middleware.accessDeniedTitle'),
      detail: t('adminConsole.middleware.accessDeniedBody'),
      life: 5000,
    })
  }
  return navigateTo('/dashboard')
})
