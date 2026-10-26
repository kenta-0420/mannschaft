import type { ApiResponse } from '~/types/api'
import type { components } from '~/types/generated'
import type { BroadcastChannel, BroadcastGroupRange, BroadcastTargetRole } from '~/types/announcement_broadcast'

/** チームグループ（並び順どおりに返る） */
export type OrgTeamGroup = components['schemas']['OrgTeamGroupResponse']
/** 宛先プレビューのレスポンス */
export type AudiencePreview = components['schemas']['AudiencePreviewResponseDto']

/** 宛先プレビューのリクエスト（broadcast と同じ宛先項目に channel / targetRole を足したもの） */
export interface AudiencePreviewRequest {
  channel?: BroadcastChannel
  targetRole?: BroadcastTargetRole
  targetTeamIds?: string[] | null
  targetGroupIds?: string[]
  targetGroupRange?: BroadcastGroupRange
  includeUnassigned?: boolean
  templateId?: number | null
}

/** チームグループ一覧の取得結果。`enabled=false` は組織がグループ機能を使っていない（409 ORG_067） */
export interface TeamGroupsResult {
  enabled: boolean
  groups: OrgTeamGroup[]
  unassignedTeamCount: number
}

/** API エラーから BE の error.code / error.message を取り出す（握りつぶさず表示へ回すため） */
export function readApiError(e: unknown): { code: string | null, message: string | null } {
  const detail = (e as { data?: { error?: { code?: string, message?: string } } })?.data?.error
  return { code: detail?.code ?? null, message: detail?.message ?? null }
}

/**
 * F01.2.1 8-E 告知ウィザード（組織スコープ）の宛先まわりの API。
 *
 * - GET  /api/v1/organizations/{slug}/team-groups                    … チームグループ一覧
 * - POST /api/v1/organizations/{orgId}/broadcast/audience-preview     … 宛先プレビュー
 *
 * `{slug}` / `{orgId}` はどちらも組織の URL 識別子（slug）を渡せる（BE の ScopeSlugIdConverter が解決する）。
 */
export function useBroadcastAudience(orgScopeId: string) {
  const api = useApi()

  /** チームグループ一覧。409 ORG_067（グループ機能 off）だけを `enabled=false` として返し、他の失敗は投げ直す */
  async function fetchTeamGroups(): Promise<TeamGroupsResult> {
    try {
      const res = await api<{ data?: OrgTeamGroup[], meta?: { unassignedTeamCount?: number } }>(
        `/api/v1/organizations/${orgScopeId}/team-groups`,
      )
      return {
        enabled: true,
        groups: res.data ?? [],
        unassignedTeamCount: res.meta?.unassignedTeamCount ?? 0,
      }
    }
    catch (e: unknown) {
      if (readApiError(e).code === 'ORG_067') {
        return { enabled: false, groups: [], unassignedTeamCount: 0 }
      }
      throw e
    }
  }

  /** 宛先プレビュー（何も書き込まない） */
  async function previewAudience(body: AudiencePreviewRequest): Promise<AudiencePreview> {
    const res = await api<ApiResponse<AudiencePreview>>(
      `/api/v1/organizations/${orgScopeId}/broadcast/audience-preview`,
      { method: 'POST', body },
    )
    return res.data
  }

  return { fetchTeamGroups, previewAudience }
}
