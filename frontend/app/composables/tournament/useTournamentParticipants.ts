// 参加者・チーム単位履歴・エントリーメンバー・エントリーテンプレートを担当
import type {
  TournamentParticipant,
  TeamTournamentHistoryResponse,
  TeamTournamentStatsResponse,
  EntryMemberListResponse,
  EntryLoadResponse,
  EntryMemberSummary,
  EntryTemplate,
  EntryTemplateDetail,
  ApplyTemplateResponse,
} from '~/types/tournament'

export function useTournamentParticipants() {
  const api = useApi()
  const b = (orgId: string) => `/api/v1/organizations/${orgId}`

  // === Participants ===
  async function getParticipants(orgId: string, tId: number, divId: number) {
    return api<{ data: TournamentParticipant[] }>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants`,
    )
  }
  async function addParticipant(
    orgId: string,
    tId: number,
    divId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants`, {
      method: 'POST',
      body,
    })
  }
  async function updateParticipant(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    body: Record<string, unknown>,
  ) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}`, {
      method: 'PATCH',
      body,
    })
  }
  async function removeParticipant(orgId: string, tId: number, divId: number, pId: number) {
    return api(`${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}`, {
      method: 'DELETE',
    })
  }

  // === Team-scoped ===
  async function getTeamTournamentHistory(teamId: string) {
    return api<{ data: TeamTournamentHistoryResponse }>(`/api/v1/teams/${teamId}/tournament-history`)
  }
  async function getTeamTournamentStats(teamId: string) {
    return api<{ data: TeamTournamentStatsResponse }>(`/api/v1/teams/${teamId}/tournament-stats`)
  }

  // ===== Phase 9: エントリーメンバー =====

  // エントリー一覧取得
  async function getEntryMembers(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    includeTeamMembers = false,
  ) {
    const query = includeTeamMembers ? '?includeTeamMembers=true' : ''
    return api<EntryMemberListResponse>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members${query}`,
    )
  }

  // チームメンバーから一括ロード
  async function loadEntryMembersFromTeam(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    body: { userIds?: number[] | null; overwriteExisting?: boolean },
  ) {
    return api<EntryLoadResponse>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members/load-from-team`,
      { method: 'POST', body },
    )
  }

  // エントリー全置換
  async function upsertEntryMembers(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    body: {
      members: Array<{
        userId: number
        jerseyNumber?: number | null
        position?: string | null
        notes?: string | null
        sortOrder?: number
      }>
    },
  ) {
    return api<EntryMemberListResponse>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members`,
      { method: 'PUT', body },
    )
  }

  // 個別削除
  async function deleteEntryMember(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    entryMemberId: string,
    force = false,
  ) {
    const query = force ? '?force=true' : ''
    return api(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members/${entryMemberId}${query}`,
      { method: 'DELETE' },
    )
  }

  // エントリーサマリー（主催者用）
  async function getEntrySummary(orgId: string, tId: number, divId: number) {
    return api<EntryMemberSummary>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/entry-summary`,
    )
  }

  // PDF ダウンロード
  async function downloadEntryPdf(orgId: string, tId: number, divId: number, pId: number) {
    return api(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members/pdf`,
      { responseType: 'blob' as const },
    ) as Promise<Blob>
  }

  // ===== Phase 9-B: エントリーテンプレート =====

  // テンプレート一覧
  async function getEntryTemplates(orgId: string, teamId: string) {
    return api<EntryTemplate[]>(`${b(orgId)}/teams/${teamId}/entry-templates`)
  }

  // テンプレート詳細
  async function getEntryTemplate(orgId: string, teamId: string, templateId: string) {
    return api<EntryTemplateDetail>(`${b(orgId)}/teams/${teamId}/entry-templates/${templateId}`)
  }

  // テンプレート作成
  async function createEntryTemplate(
    orgId: string,
    teamId: string,
    body: {
      name: string
      description?: string | null
      sortOrder?: number
      members: Array<{
        userId: number
        jerseyNumber?: number | null
        position?: string | null
        sortOrder?: number
      }>
    },
  ) {
    return api<EntryTemplateDetail>(`${b(orgId)}/teams/${teamId}/entry-templates`, {
      method: 'POST',
      body,
    })
  }

  // テンプレート更新
  async function updateEntryTemplate(
    orgId: string,
    teamId: string,
    templateId: string,
    body: Parameters<typeof createEntryTemplate>[2],
  ) {
    return api<EntryTemplateDetail>(
      `${b(orgId)}/teams/${teamId}/entry-templates/${templateId}`,
      { method: 'PUT', body },
    )
  }

  // テンプレート削除
  async function deleteEntryTemplate(orgId: string, teamId: string, templateId: string) {
    return api(`${b(orgId)}/teams/${teamId}/entry-templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  // テンプレート適用
  async function applyEntryTemplate(
    orgId: string,
    tId: number,
    divId: number,
    pId: number,
    body: { templateId: string; overwriteExisting?: boolean },
  ) {
    return api<ApplyTemplateResponse>(
      `${b(orgId)}/tournaments/${tId}/divisions/${divId}/participants/${pId}/entry-members/apply-template`,
      { method: 'POST', body },
    )
  }

  return {
    getParticipants,
    addParticipant,
    updateParticipant,
    removeParticipant,
    getTeamTournamentHistory,
    getTeamTournamentStats,
    getEntryMembers,
    loadEntryMembersFromTeam,
    upsertEntryMembers,
    deleteEntryMember,
    getEntrySummary,
    downloadEntryPdf,
    getEntryTemplates,
    getEntryTemplate,
    createEntryTemplate,
    updateEntryTemplate,
    deleteEntryTemplate,
    applyEntryTemplate,
  }
}
