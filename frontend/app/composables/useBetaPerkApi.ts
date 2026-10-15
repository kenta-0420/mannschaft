import type { components } from '~/types/generated'

/**
 * F20.3 ベータ特典（本人・チーム/組織 照会）の API 呼び出し。
 *
 * 型は生成型（openapi-typescript）を最優先で使う（memory `feedback_fe_api_type_assertion_field_lie`）。
 * BE の team/org スコープパスパラメータは既存の他ドメイン composable（useBillingApi 等）と同様に
 * slug 文字列をそのまま渡す運用に揃える（Converter<String,Long> がサーバー側で解決する）。
 *
 * 申請 API は存在しない（自動付与のため・design 04 §1）。ここでは照会のみを扱う。
 */

// === 生成型（真実のソース = openapi-typescript）===
export type BetaPerkGrantItem = components['schemas']['BetaPerkGrantItem']
export type BetaPerkEligibilityStatus = components['schemas']['BetaPerkEligibilityStatus']
export type BetaPerkMetricProgress = components['schemas']['BetaPerkMetricProgress']
export type BetaPerkMyPerksResponse = components['schemas']['BetaPerkMyPerksResponse']

/** 表示スコープ種別（billing composable の BillingScopeKind と揃える）。 */
export type BetaPerkScopeKind = 'USER' | 'TEAM' | 'ORG'

export function useBetaPerkApi() {
  const api = useApi()

  /** 本人固定（scopeId を受け取らない・AC-17）。 */
  async function getMyBetaPerks() {
    return api<{ data: BetaPerkMyPerksResponse }>('/api/v1/me/beta-perks')
  }

  async function getTeamBetaPerks(teamId: string) {
    return api<{ data: BetaPerkGrantItem[] }>(`/api/v1/teams/${teamId}/beta-perks`)
  }

  async function getOrgBetaPerks(orgId: string) {
    return api<{ data: BetaPerkGrantItem[] }>(`/api/v1/organizations/${orgId}/beta-perks`)
  }

  /** TEAM/ORG スコープの照会を振り分ける（USER は getMyBetaPerks を直接使うこと）。 */
  async function getScopedBetaPerks(scopeKind: 'TEAM' | 'ORG', scopeId: string) {
    if (scopeKind === 'TEAM') return getTeamBetaPerks(scopeId)
    return getOrgBetaPerks(scopeId)
  }

  return {
    getMyBetaPerks,
    getTeamBetaPerks,
    getOrgBetaPerks,
    getScopedBetaPerks,
  }
}
