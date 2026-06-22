import type {
  ProjectResponse,
  TeamProjectResponse,
  OrgProjectResponse,
  CreateProjectRequest,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
  UpdateMilestoneRequest,
  GatesSummaryResponse,
  MilestoneCompletionMode,
  ForceUnlockResponse,
  InitializeGateResponse,
} from '~/types/project'
import type { TodoResponse } from '~/types/todo'

export function useProjectApi() {
  const api = useApi()

  // teamId === null で個人スコープ (`/api/v1/users/me/projects`)、
  // 文字列の場合はチームスコープ (`/api/v1/teams/{teamId}/projects`)
  function buildBase(teamId: string | null) {
    if (teamId === null) {
      return '/api/v1/users/me/projects'
    }
    return `/api/v1/teams/${teamId}/projects`
  }

  // 組織スコープ専用のベース URL を構築する (`/api/v1/organizations/{orgSlug}/projects`)
  function buildOrgBase(orgSlug: string) {
    return `/api/v1/organizations/${orgSlug}/projects`
  }

  function buildScopedBase(teamId: string | null, projectId: number) {
    return `${buildBase(teamId)}/${projectId}`
  }

  function buildOrgScopedBase(orgSlug: string, projectId: number) {
    return `${buildOrgBase(orgSlug)}/${projectId}`
  }

  // === Projects ===
  async function listProjects(teamId: string | null) {
    return api<{ data: ProjectResponse[] }>(buildBase(teamId))
  }

  async function listOrgProjects(orgSlug: string) {
    return api<{ data: ProjectResponse[] }>(buildOrgBase(orgSlug))
  }

  async function getOrgProject(orgSlug: string, projectId: number) {
    return api<{ data: ProjectResponse }>(buildOrgScopedBase(orgSlug, projectId))
  }

  async function createOrgProject(orgSlug: string, body: CreateProjectRequest) {
    return api<{ data: ProjectResponse }>(buildOrgBase(orgSlug), { method: 'POST', body })
  }

  async function updateOrgProject(orgSlug: string, projectId: number, body: UpdateProjectRequest) {
    return api<{ data: ProjectResponse }>(buildOrgScopedBase(orgSlug, projectId), {
      method: 'PUT',
      body,
    })
  }

  async function deleteOrgProject(orgSlug: string, projectId: number) {
    return api(buildOrgScopedBase(orgSlug, projectId), { method: 'DELETE' })
  }

  async function completeOrgProject(orgSlug: string, projectId: number) {
    return api(`${buildOrgScopedBase(orgSlug, projectId)}/complete`, { method: 'PATCH' })
  }

  async function reopenOrgProject(orgSlug: string, projectId: number) {
    return api(`${buildOrgScopedBase(orgSlug, projectId)}/reopen`, { method: 'PATCH' })
  }

  async function listOrgMilestones(orgSlug: string, projectId: number) {
    return api<{ data: MilestoneResponse[] }>(`${buildOrgScopedBase(orgSlug, projectId)}/milestones`)
  }

  async function createOrgMilestone(orgSlug: string, projectId: number, body: CreateMilestoneRequest) {
    return api<{ data: MilestoneResponse }>(`${buildOrgScopedBase(orgSlug, projectId)}/milestones`, {
      method: 'POST',
      body,
    })
  }

  async function updateOrgMilestone(
    orgSlug: string,
    projectId: number,
    milestoneId: number,
    body: UpdateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(
      `${buildOrgScopedBase(orgSlug, projectId)}/milestones/${milestoneId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteOrgMilestone(orgSlug: string, projectId: number, milestoneId: number) {
    return api(`${buildOrgScopedBase(orgSlug, projectId)}/milestones/${milestoneId}`, {
      method: 'DELETE',
    })
  }

  async function completeOrgMilestone(orgSlug: string, projectId: number, milestoneId: number) {
    return api(`${buildOrgScopedBase(orgSlug, projectId)}/milestones/${milestoneId}/complete`, {
      method: 'PATCH',
    })
  }

  async function getOrgProjectTodos(orgSlug: string, projectId: number) {
    return api<{ data: unknown[] }>(`${buildOrgScopedBase(orgSlug, projectId)}/todos`)
  }

  /**
   * マイページ チームプロジェクト集約（GET /api/v1/me/team-projects）。
   * ログインユーザーが所属する全チームのプロジェクトを一括取得する。
   */
  async function listMyTeamProjects() {
    return api<{ data: TeamProjectResponse[] }>('/api/v1/me/team-projects')
  }

  /**
   * マイページ 組織プロジェクト集約（GET /api/v1/me/org-projects）。
   * ログインユーザーが所属する全組織のプロジェクトを一括取得する。
   * listMyTeamProjects の組織版（対称設計）。
   */
  async function listMyOrgProjects() {
    return api<{ data: OrgProjectResponse[] }>('/api/v1/me/org-projects')
  }

  async function getProject(teamId: string | null, projectId: number) {
    return api<{ data: ProjectResponse }>(buildScopedBase(teamId, projectId))
  }

  async function createProject(teamId: string | null, body: CreateProjectRequest) {
    return api<{ data: ProjectResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateProject(
    teamId: string | null,
    projectId: number,
    body: UpdateProjectRequest,
  ) {
    return api<{ data: ProjectResponse }>(buildScopedBase(teamId, projectId), {
      method: 'PUT',
      body,
    })
  }

  async function deleteProject(teamId: string | null, projectId: number) {
    return api(buildScopedBase(teamId, projectId), { method: 'DELETE' })
  }

  async function completeProject(teamId: string | null, projectId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/complete`, { method: 'PATCH' })
  }

  async function reopenProject(teamId: string | null, projectId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/reopen`, { method: 'PATCH' })
  }

  // === Milestones ===
  async function listMilestones(teamId: string | null, projectId: number) {
    return api<{ data: MilestoneResponse[] }>(`${buildScopedBase(teamId, projectId)}/milestones`)
  }

  async function createMilestone(
    teamId: string | null,
    projectId: number,
    body: CreateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(`${buildScopedBase(teamId, projectId)}/milestones`, {
      method: 'POST',
      body,
    })
  }

  async function updateMilestone(
    teamId: string | null,
    projectId: number,
    milestoneId: number,
    body: UpdateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteMilestone(teamId: string | null, projectId: number, milestoneId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}`, {
      method: 'DELETE',
    })
  }

  async function completeMilestone(teamId: string | null, projectId: number, milestoneId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/complete`, {
      method: 'PATCH',
    })
  }

  // === Project Todos ===
  async function getProjectTodos(teamId: string | null, projectId: number) {
    return api<{ data: unknown[] }>(`${buildScopedBase(teamId, projectId)}/todos`)
  }

  // === F02.7 マイルストーンゲート ===

  // ゲート状態サマリー取得（チーム/組織/個人 対応）
  async function getGatesSummary(teamId: string | null, projectId: number) {
    return api<{ data: GatesSummaryResponse }>(`${buildScopedBase(teamId, projectId)}/gates`)
  }

  // 完了モード変更
  async function changeCompletionMode(
    teamId: string | null,
    projectId: number,
    milestoneId: number,
    mode: MilestoneCompletionMode,
  ) {
    return api<{ data: MilestoneResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/completion-mode`,
      {
        method: 'PATCH',
        body: { completionMode: mode },
      },
    )
  }

  // 強制アンロック
  async function forceUnlockMilestone(
    teamId: string | null,
    projectId: number,
    milestoneId: number,
    reason: string,
  ) {
    return api<{ data: ForceUnlockResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/force-unlock`,
      {
        method: 'PATCH',
        body: { reason },
      },
    )
  }

  // ゲート初期化（既存プロジェクト向け）
  async function initializeGate(teamId: string | null, projectId: number, milestoneId: number) {
    return api<{ data: InitializeGateResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/initialize-gate`,
      { method: 'PATCH' },
    )
  }

  // マイルストーン内 TODO 並び替え
  async function reorderMilestoneTodos(
    teamId: string | null,
    projectId: number,
    milestoneId: number,
    todoIds: number[],
  ) {
    return api<{ data: TodoResponse[] }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/todos/reorder`,
      {
        method: 'PATCH',
        body: { todoIds },
      },
    )
  }

  return {
    listProjects,
    listMyTeamProjects,
    listMyOrgProjects,
    getProject,
    createProject,
    updateProject,
    deleteProject,
    completeProject,
    reopenProject,
    listMilestones,
    createMilestone,
    updateMilestone,
    deleteMilestone,
    completeMilestone,
    getProjectTodos,
    // F02.7
    getGatesSummary,
    changeCompletionMode,
    forceUnlockMilestone,
    initializeGate,
    reorderMilestoneTodos,
    // 組織スコープ
    listOrgProjects,
    getOrgProject,
    createOrgProject,
    updateOrgProject,
    deleteOrgProject,
    completeOrgProject,
    reopenOrgProject,
    listOrgMilestones,
    createOrgMilestone,
    updateOrgMilestone,
    deleteOrgMilestone,
    completeOrgMilestone,
    getOrgProjectTodos,
  }
}
