// 書類提出受付（F08.7.1 / FE-E）: 提出枠CRUD・状況ダッシュボード・チーム提出を担当
import type {
  SubmissionRequirementResponse,
  CreateSubmissionRequirementRequest,
  UpdateSubmissionRequirementRequest,
  SubmissionStatusDashboardResponse,
  SubmitForTeamRequest,
} from '~/types/tournament'

export function useTournamentSubmission(orgId: string, tournamentId: number) {
  const api = useApi()

  const base = `/api/v1/organizations/${orgId}/tournaments/${tournamentId}/submission-requirements`

  // === 主催者向け: 提出枠全件一覧 ===
  async function listRequirementsForOrganizer() {
    return api<{ data: SubmissionRequirementResponse[] }>(base)
  }

  // === チーム向け: 自チーム対象の提出枠一覧 ===
  async function listRequirementsForTeam(teamId: string) {
    return api<{ data: SubmissionRequirementResponse[] }>(`${base}?teamId=${teamId}`)
  }

  // === 提出枠作成（主催ADMIN） ===
  async function createRequirement(req: CreateSubmissionRequirementRequest) {
    return api<{ data: SubmissionRequirementResponse }>(base, {
      method: 'POST',
      body: req,
    })
  }

  // === 提出枠更新 ===
  async function updateRequirement(reqId: number, req: UpdateSubmissionRequirementRequest) {
    return api<{ data: SubmissionRequirementResponse }>(`${base}/${reqId}`, {
      method: 'PATCH',
      body: req,
    })
  }

  // === 提出枠削除 ===
  async function deleteRequirement(reqId: number) {
    return api(`${base}/${reqId}`, { method: 'DELETE' })
  }

  // === 提出状況ダッシュボード（チーム別集計） ===
  async function getStatusDashboard(reqId: number) {
    return api<{ data: SubmissionStatusDashboardResponse }>(`${base}/${reqId}/status`)
  }

  // === チームが提出（F05.6 form_submission起票） ===
  async function submitForTeam(reqId: number, teamId: string, body: SubmitForTeamRequest) {
    return api<{ data: { formSubmissionId: number } }>(
      `${base}/${reqId}/teams/${teamId}/submit`,
      { method: 'POST', body },
    )
  }

  return {
    listRequirementsForOrganizer,
    listRequirementsForTeam,
    createRequirement,
    updateRequirement,
    deleteRequirement,
    getStatusDashboard,
    submitForTeam,
  }
}
