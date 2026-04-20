import type {
  ProjectResponse,
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
  // 数値の場合はチーム/組織スコープ (`/api/v1/teams/{teamId}/projects`)
  function buildBase(teamId: number | null) {
    if (teamId === null) {
      return '/api/v1/users/me/projects'
    }
    return `/api/v1/teams/${teamId}/projects`
  }

  function buildScopedBase(teamId: number | null, projectId: number) {
    return `${buildBase(teamId)}/${projectId}`
  }

  // === Projects ===
  async function listProjects(teamId: number | null) {
    return api<{ data: ProjectResponse[] }>(buildBase(teamId))
  }

  async function getProject(teamId: number | null, projectId: number) {
    return api<{ data: ProjectResponse }>(buildScopedBase(teamId, projectId))
  }

  async function createProject(teamId: number | null, body: CreateProjectRequest) {
    return api<{ data: ProjectResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateProject(
    teamId: number | null,
    projectId: number,
    body: UpdateProjectRequest,
  ) {
    return api<{ data: ProjectResponse }>(buildScopedBase(teamId, projectId), {
      method: 'PUT',
      body,
    })
  }

  async function deleteProject(teamId: number | null, projectId: number) {
    return api(buildScopedBase(teamId, projectId), { method: 'DELETE' })
  }

  async function completeProject(teamId: number | null, projectId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/complete`, { method: 'PATCH' })
  }

  async function reopenProject(teamId: number | null, projectId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/reopen`, { method: 'PATCH' })
  }

  // === Milestones ===
  async function listMilestones(teamId: number | null, projectId: number) {
    return api<{ data: MilestoneResponse[] }>(`${buildScopedBase(teamId, projectId)}/milestones`)
  }

  async function createMilestone(
    teamId: number | null,
    projectId: number,
    body: CreateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(`${buildScopedBase(teamId, projectId)}/milestones`, {
      method: 'POST',
      body,
    })
  }

  async function updateMilestone(
    teamId: number | null,
    projectId: number,
    milestoneId: number,
    body: UpdateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteMilestone(teamId: number | null, projectId: number, milestoneId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}`, {
      method: 'DELETE',
    })
  }

  async function completeMilestone(teamId: number | null, projectId: number, milestoneId: number) {
    return api(`${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/complete`, {
      method: 'PATCH',
    })
  }

  // === Project Todos ===
  async function getProjectTodos(teamId: number | null, projectId: number) {
    return api<{ data: unknown[] }>(`${buildScopedBase(teamId, projectId)}/todos`)
  }

  // === F02.7 マイルストーンゲート ===

  // ゲート状態サマリー取得（チーム/組織/個人 対応）
  async function getGatesSummary(teamId: number | null, projectId: number) {
    return api<{ data: GatesSummaryResponse }>(`${buildScopedBase(teamId, projectId)}/gates`)
  }

  // 完了モード変更
  async function changeCompletionMode(
    teamId: number | null,
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
    teamId: number | null,
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
  async function initializeGate(teamId: number | null, projectId: number, milestoneId: number) {
    return api<{ data: InitializeGateResponse }>(
      `${buildScopedBase(teamId, projectId)}/milestones/${milestoneId}/initialize-gate`,
      { method: 'PATCH' },
    )
  }

  // マイルストーン内 TODO 並び替え
  async function reorderMilestoneTodos(
    teamId: number | null,
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
  }
}
