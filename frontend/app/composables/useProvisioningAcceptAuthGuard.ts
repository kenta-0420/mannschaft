/**
 * `pages/provisioning/accept.vue` の未ログイン時ルートガード（検分 P1 再発防止のため、
 * ページ側の inline middleware から切り出して単体テスト可能にしたもの）。
 *
 * 未ログイン時は、URL フラグメントのトークンを sessionStorage へ退避してから、
 * トークンを含まない `/login?redirect=%2Fprovisioning%2Faccept` へ遷移させる
 * （`frontend/app/middleware/auth.ts` の redirect クエリ復帰パターンに整合）。
 */

/** ログイン往復中にトークンを一時退避する sessionStorage の固定キー。 */
export const PROVISIONING_ACCEPT_TOKEN_STORAGE_KEY = 'provisioning_accept_token'

/** 承諾画面のパス（redirect クエリの復帰先）。 */
export const PROVISIONING_ACCEPT_PATH = '/provisioning/accept'

export interface ProvisioningAcceptLoginRedirect {
  path: '/login'
  query: { redirect: typeof PROVISIONING_ACCEPT_PATH }
}

/**
 * 未ログイン時に呼ぶ。トークンがあれば sessionStorage へ退避し、
 * ログイン画面への遷移先（トークンを含まない）を返す。
 */
export function buildProvisioningAcceptLoginRedirect(hashToken: string | null): ProvisioningAcceptLoginRedirect {
  if (hashToken && typeof window !== 'undefined') {
    try {
      window.sessionStorage.setItem(PROVISIONING_ACCEPT_TOKEN_STORAGE_KEY, hashToken)
    } catch {
      // sessionStorage が使えない環境（プライベートモード等）でも致命的ではない。
      // ログイン後の復帰時にフラグメントが無く sessionStorage にも無ければ notFound 扱いになる。
    }
  }
  return { path: '/login', query: { redirect: PROVISIONING_ACCEPT_PATH } }
}
