// 大会参加費管理 composable（F08.7.1 §6）
import type { components } from '~/types/generated/index'

type TournamentFeeResponse = components['schemas']['TournamentFeeResponse']
type CreateTournamentFeeRequest = components['schemas']['CreateTournamentFeeRequest']
type CheckoutResponse = components['schemas']['CheckoutResponse']

export function useTournamentFee(orgId: string, tournamentId: number) {
  const api = useApi()
  const base = `/api/v1/organizations/${orgId}/tournaments/${tournamentId}/fees`

  /** 参加費一覧取得（主催者ADMIN向け）*/
  async function listFees(): Promise<TournamentFeeResponse[]> {
    const res = await api<{ data: TournamentFeeResponse[] }>(base)
    return res.data ?? []
  }

  /** 参加費作成（主催者ADMIN）*/
  async function createFee(req: CreateTournamentFeeRequest): Promise<TournamentFeeResponse> {
    const res = await api<{ data: TournamentFeeResponse }>(base, {
      method: 'POST',
      body: req,
    })
    return res.data!
  }

  /** 参加費削除（主催者ADMIN）*/
  async function deleteFee(feeId: string): Promise<void> {
    await api(`${base}/${feeId}`, { method: 'DELETE' })
  }

  /**
   * チームが参加費を支払う（Stripe Checkout または MANUAL）
   * checkoutUrl が空でない場合は Stripe Checkout、空の場合は MANUAL として扱う
   */
  async function checkout(feeId: string, teamId: string): Promise<CheckoutResponse> {
    const res = await api<{ data: CheckoutResponse }>(
      `${base}/${feeId}/teams/${teamId}/checkout`,
      { method: 'POST' },
    )
    return res.data!
  }

  return {
    listFees,
    createFee,
    deleteFee,
    checkout,
  }
}
