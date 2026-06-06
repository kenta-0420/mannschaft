import type { SwitchableChildrenResponse } from '~/types/guardianship'

/**
 * F08.9 後見切替 API の型付きラッパー（設計書 02 §2.1）。
 *
 * 認証ユーザー（保護者）視点。エンドポイントは BE PR #P3a main 済み。
 * - 切替可能な子の一覧: GET /api/v1/me/guardianship/switchable-children
 *
 * BE は全レスポンスを ApiResponse（{ data: ... }）でラップする。
 */
export function useGuardianshipApi() {
  const api = useApi()

  /**
   * 認証ユーザー（保護者）が後見切替できる子と、年齢到達で封印された子を取得する。
   * P5 加入 UI では children（switchAllowed=true）を受益者の選択肢に使う。
   */
  async function listSwitchableChildren() {
    return api<{ data: SwitchableChildrenResponse }>(
      '/api/v1/me/guardianship/switchable-children',
    )
  }

  return {
    listSwitchableChildren,
  }
}
