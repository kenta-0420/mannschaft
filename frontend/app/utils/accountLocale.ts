/**
 * アカウント言語（users.locale）を表示言語と i18n Cookie へ適用する純粋ロジック。
 *
 * 【設計】ログイン中はアカウントに保存された言語を正とする（CMP-261007-1243）。
 * 保存・起動・ログイン・2FA・OAuth のすべての経路がこの関数を通る。
 * 未ログイン時は呼ばれないため、従来どおり Cookie / ブラウザ言語の検出が働く。
 *
 * 【Cookie を先に書く理由】
 * @nuxtjs/i18n の setLocale は、切替先言語のメッセージ（言語ごとに約 90 ファイル）を
 * 読み終えるまで locale も Cookie も変えない。dev 環境ではこの読み込みに数十秒かかり、
 * その間 Cookie は旧言語のまま残る。また setLocale は現在の locale と同じ値を渡されると
 * 何もしないため、「表示は en・Cookie は de」のようなずれを直せない。
 * そこで正規 API の setLocaleCookie で Cookie を即座にアカウント言語へ揃え、
 * そのうえで表示言語を setLocale で切り替える。
 */

import { isSupportedLocale, type SupportedLocale } from './normalizeLocale'

/** @nuxtjs/i18n のグローバル composer のうち、本処理が使う部分。 */
export interface AccountLocaleTarget {
  locale: { value: string }
  setLocale: (code: SupportedLocale) => Promise<void>
  setLocaleCookie: (code: SupportedLocale) => void
}

/**
 * アカウント言語を Cookie と表示言語へ適用する。
 *
 * @returns 適用した言語コード。未設定・非対応コードの場合は null（何も変更しない）
 */
export async function applyAccountLocaleTo(
  target: AccountLocaleTarget,
  accountLocale: string | null | undefined,
): Promise<SupportedLocale | null> {
  if (!accountLocale || !isSupportedLocale(accountLocale)) return null
  target.setLocaleCookie(accountLocale)
  if (target.locale.value !== accountLocale) {
    await target.setLocale(accountLocale)
  }
  return accountLocale
}
