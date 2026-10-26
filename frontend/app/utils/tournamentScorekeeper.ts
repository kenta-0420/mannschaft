// F08.7 順位UI Wave B-3 / ③: スコアキーパー指名 UI の純関数ヘルパー。
// 入力検証・重複判定・メンバー候補フィルタなど UI から切り出して単体テスト可能にする。
import type { ScorekeeperResponse } from '~/types/tournament'
import type { MemberCardListItem } from '~/types/member-card'

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

/**
 * メンバー候補（会員証一覧）から、既に指名済みのユーザーを除外する。
 * 1 ユーザーが複数の会員証を持つ可能性に備え、userId 単位で重複排除する。
 *
 * @param candidates 会員証一覧（org スコープ）
 * @param scorekeepers 既存のスコアキーパー指名一覧
 * @returns 未指名かつ userId 重複のない候補
 */
export function filterMemberCandidates(
  candidates: MemberCardListItem[],
  scorekeepers: ScorekeeperResponse[],
): MemberCardListItem[] {
  const assigned = new Set(scorekeepers.map((sk) => sk.userId))
  const seen = new Set<number>()
  const result: MemberCardListItem[] = []
  for (const c of candidates) {
    if (assigned.has(c.userId)) continue
    if (seen.has(c.userId)) continue
    seen.add(c.userId)
    result.push(c)
  }
  return result
}
