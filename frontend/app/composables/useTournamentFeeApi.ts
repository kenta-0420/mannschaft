import type {
  MyTournamentFeesResponse,
  TournamentFeeCheckoutRequest,
  TournamentFeeCheckoutResponse,
} from '~/types/tournament'

export function useTournamentFeeApi() {
  const api = useApi()

  async function getMyTournamentFees(): Promise<{ data: MyTournamentFeesResponse }> {
    return api<{ data: MyTournamentFeesResponse }>('/api/v1/tournament-fees/my')
  }

  async function checkoutFee(
    feeId: string,
    req?: TournamentFeeCheckoutRequest,
  ): Promise<{ data: TournamentFeeCheckoutResponse }> {
    return api<{ data: TournamentFeeCheckoutResponse }>(
      `/api/v1/tournament-fees/${feeId}/checkout`,
      { method: 'POST', body: req ?? {} },
    )
  }

  return { getMyTournamentFees, checkoutFee }
}
