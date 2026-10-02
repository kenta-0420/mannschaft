/**
 * パスワードポリシー共通ユーティリティ。
 * settings/password.vue、settings/account（useAccountProfile）、
 * reset-password.vue から共用する。
 *
 * ポリシー: 8文字以上、大文字/小文字/数字/記号のうち3種以上。
 */

/**
 * 文字列に含まれる文字種の数を返す。
 * 大文字 / 小文字 / 数字 / 記号 をそれぞれ 1 種としてカウントする。
 */
export function countCharTypes(value: string): number {
  let count = 0
  if (/[A-Z]/.test(value)) count++
  if (/[a-z]/.test(value)) count++
  if (/[0-9]/.test(value)) count++
  if (/[^A-Za-z0-9]/.test(value)) count++
  return count
}

/**
 * パスワードがポリシーを満たすかを返す。
 * 条件: 8 文字以上、かつ countCharTypes(value) >= 3。
 */
export function meetsPasswordPolicy(value: string): boolean {
  return value.length >= 8 && countCharTypes(value) >= 3
}
