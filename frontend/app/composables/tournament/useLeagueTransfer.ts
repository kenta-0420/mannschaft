// リーグ昇降格移籍（LeagueTransfer）APIを担当
// 送り出し側: getCandidates / promote / relegate
// 受け入れ側: getInboundTransfers / approve / decline / cancel
import type { components } from '~/types/generated/index'

export type LeagueTransferResponse = components['schemas']['LeagueTransferResponse']
export type TransferCandidateResponse = components['schemas']['TransferCandidateResponse']
export type PromoteRequest = components['schemas']['PromoteRequest']
export type RelegateRequest = components['schemas']['RelegateRequest']

export function useLeagueTransfer(orgId: string) {
  const api = useApi()
  const b = `/api/v1/organizations/${orgId}`

  /**
   * 昇降格候補チーム一覧取得（送り出し側 ADMIN のみ）
   * GET /organizations/{orgId}/tournaments/{tId}/transfer-candidates
   */
  async function getCandidates(tId: number) {
    return api<{ data: TransferCandidateResponse[] }>(
      `${b}/tournaments/${tId}/transfer-candidates`,
    )
  }

  /**
   * 昇格送り出し
   * POST /organizations/{orgId}/tournaments/{tId}/league-transfers/promote
   */
  async function promote(tId: number, req: PromoteRequest) {
    return api<{ data: LeagueTransferResponse[] }>(
      `${b}/tournaments/${tId}/league-transfers/promote`,
      { method: 'POST', body: req },
    )
  }

  /**
   * 降格送り出し
   * POST /organizations/{orgId}/tournaments/{tId}/league-transfers/relegate
   */
  async function relegate(tId: number, req: RelegateRequest) {
    return api<{ data: LeagueTransferResponse[] }>(
      `${b}/tournaments/${tId}/league-transfers/relegate`,
      { method: 'POST', body: req },
    )
  }

  /**
   * 受信箱（受け入れ側）
   * GET /organizations/{orgId}/inbound-transfers
   */
  async function getInboundTransfers(direction?: 'PROMOTION' | 'RELEGATION') {
    const q = direction ? `?direction=${direction}` : ''
    return api<{ data: LeagueTransferResponse[] }>(`${b}/inbound-transfers${q}`)
  }

  /**
   * 承認・配属
   * POST /organizations/{orgId}/tournaments/{tId}/divisions/{divId}/league-transfers/{id}/approve
   */
  async function approve(tId: number, divId: number, transferId: string) {
    return api<{ data: LeagueTransferResponse }>(
      `${b}/tournaments/${tId}/divisions/${divId}/league-transfers/${transferId}/approve`,
      { method: 'POST' },
    )
  }

  /**
   * 却下
   * POST /organizations/{orgId}/league-transfers/{id}/decline
   */
  async function decline(transferId: string) {
    return api<{ data: LeagueTransferResponse }>(
      `${b}/league-transfers/${transferId}/decline`,
      { method: 'POST' },
    )
  }

  /**
   * 取消
   * POST /organizations/{orgId}/league-transfers/{id}/cancel
   */
  async function cancel(transferId: string) {
    return api<{ data: LeagueTransferResponse }>(
      `${b}/league-transfers/${transferId}/cancel`,
      { method: 'POST' },
    )
  }

  return {
    getCandidates,
    promote,
    relegate,
    getInboundTransfers,
    approve,
    decline,
    cancel,
  }
}
