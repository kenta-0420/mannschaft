/**
 * パスワード変更後のリダイレクト共通ヘルパー。
 *
 * 変更成功 → overlayフェードイン → 一定時間保持 → ログアウト（/loginへ遷移）の
 * 挙動を settings/password.vue と settings/account.vue で統一するために使う。
 */

/** overlay を表示してから logout を呼ぶまでの保持時間 (ms) */
const REDIRECT_HOLD_MS = 900

export function usePasswordChangeRedirect() {
  const authStore = useAuthStore()
  const redirecting = ref(false)

  /**
   * redirecting フラグを立てて overlay をフェードイン表示し、
   * REDIRECT_HOLD_MS 後に logout してログイン画面へ誘導する。
   */
  async function triggerRedirect(): Promise<void> {
    redirecting.value = true
    await new Promise<void>((resolve) => setTimeout(resolve, REDIRECT_HOLD_MS))
    await authStore.logout({ reason: 'password_changed' })
  }

  return { redirecting, triggerRedirect }
}
