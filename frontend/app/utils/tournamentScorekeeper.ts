// F08.7 順位UI Wave B-3: スコアキーパー指名 UI の純関数ヘルパー。
// 入力検証・重複判定など UI から切り出して単体テスト可能にする。
import type { ScorekeeperResponse } from '~/types/tournament'

/**
 * ユーザー ID 入力文字列を正の整数へパースする。
 * 不正（空・非数値・0 以下・小数）の場合は null を返す。
 */
export function parseUserIdInput(raw: string | number | null | undefined): number | null {
  if (raw === null || raw === undefined) return null
  const s = String(raw).trim()
  if (s === '') return null
  // 整数のみ許可（小数点・記号・前後空白付き数値は弾く）
  if (!/^\d+$/.test(s)) return null
  const n = Number(s)
  if (!Number.isInteger(n) || n <= 0) return null
  return n
}

/**
 * 指定ユーザーが既に指名済みかどうか。
 * BE 側も冪等だが、FE で事前に重複を検知して無駄な POST と紛らわしい挙動を避ける。
 */
export function isAlreadyScorekeeper(
  scorekeepers: ScorekeeperResponse[],
  userId: number,
): boolean {
  return scorekeepers.some((sk) => sk.userId === userId)
}
