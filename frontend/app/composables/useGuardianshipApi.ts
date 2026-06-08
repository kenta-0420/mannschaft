import type { IndependenceStatusResponse, SwitchableChildrenResponse } from '~/types/guardianship'

/**
 * F08.9 後見切替 API の型付きラッパー（設計書 02 §2.1）。
 *
 * 認証ユーザー（保護者）視点。エンドポイントは BE PR #P3a / #1342 main 済み。
 * - 切替可能な子の一覧: GET /api/v1/me/guardianship/switchable-children
 * - 後見切替開始: POST /api/v1/me/guardianship/switch
 * - 後見切替終了: DELETE /api/v1/me/guardianship/switch
 * - 自立移行状況: GET /api/v1/me/guardianship/children/{childUserId}/independence-status
 * - 引き継ぎ開始: POST /api/v1/me/guardianship/children/{childUserId}/handover/initiate
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

  /**
   * 後見切替を開始する（BE 検証 + 監査ログのみ。ヘッダはクライアント側で管理）。
   * 成功後は guardianshipSwitchStore.startSwitch() を呼ぶこと。
   */
  async function startSwitch(childUserId: number): Promise<void> {
    await api('/api/v1/me/guardianship/switch', {
      method: 'POST',
      body: { childUserId },
    })
  }

  /**
   * 後見切替を終了する。
   * 成功後は guardianshipSwitchStore.endSwitch() を呼ぶこと。
   */
  async function endSwitch(childUserId: number): Promise<void> {
    await api('/api/v1/me/guardianship/switch', {
      method: 'DELETE',
      query: { childUserId },
    })
  }

  /**
   * 子の自立移行ステータスを取得する。
   * sealDate（封印境界日）・stageKey・passwordSet を返す。
   */
  async function getIndependenceStatus(childUserId: number) {
    return api<{ data: IndependenceStatusResponse }>(
      `/api/v1/me/guardianship/children/${childUserId}/independence-status`,
    )
  }

  /**
   * 子への引き継ぎ（独立準備）を開始する。
   * childEmail を渡すとそのアドレスへ設定リンクを送る（省略可）。
   */
  async function initiateHandover(childUserId: number, childEmail?: string): Promise<void> {
    await api(`/api/v1/me/guardianship/children/${childUserId}/handover/initiate`, {
      method: 'POST',
      body: childEmail ? { childEmail } : {},
    })
  }

  return {
    listSwitchableChildren,
    startSwitch,
    endSwitch,
    getIndependenceStatus,
    initiateHandover,
  }
}
