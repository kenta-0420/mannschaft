import type {
  ProjectResponse,
  CreateProjectRequest,
  UpdateProjectRequest,
  MilestoneResponse,
  CreateMilestoneRequest,
  UpdateMilestoneRequest,
} from '~/types/project'

export function useProjectApi() {
  const api = useApi()

  function buildBase(teamId: number) {
    return `/api/v1/teams/${teamId}/projects`
  }

  // === Projects ===
  async function listProjects(teamId: number) {
    return api<{ data: ProjectResponse[] }>(buildBase(teamId))
  }

  async function getProject(teamId: number, projectId: number) {
    return api<{ data: ProjectResponse }>(`${buildBase(teamId)}/${projectId}`)
  }

  async function createProject(teamId: number, body: CreateProjectRequest) {
    return api<{ data: ProjectResponse }>(buildBase(teamId), { method: 'POST', body })
  }

  async function updateProject(teamId: number, projectId: number, body: UpdateProjectRequest) {
    return api<{ data: ProjectResponse }>(`${buildBase(teamId)}/${projectId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteProject(teamId: number, projectId: number) {
    return api(`${buildBase(teamId)}/${projectId}`, { method: 'DELETE' })
  }

  async function completeProject(teamId: number, projectId: number) {
    return api(`${buildBase(teamId)}/${projectId}/complete`, { method: 'PATCH' })
  }

  async function reopenProject(teamId: number, projectId: number) {
    return api(`${buildBase(teamId)}/${projectId}/reopen`, { method: 'PATCH' })
  }

  // === Milestones ===
  async function listMilestones(teamId: number, projectId: number) {
    return api<{ data: MilestoneResponse[] }>(`${buildBase(teamId)}/${projectId}/milestones`)
  }

  async function createMilestone(teamId: number, projectId: number, body: CreateMilestoneRequest) {
    return api<{ data: MilestoneResponse }>(`${buildBase(teamId)}/${projectId}/milestones`, {
      method: 'POST',
      body,
    })
  }

  async function updateMilestone(
    teamId: number,
    projectId: number,
    milestoneId: number,
    body: UpdateMilestoneRequest,
  ) {
    return api<{ data: MilestoneResponse }>(
      `${buildBase(teamId)}/${projectId}/milestones/${milestoneId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteMilestone(teamId: number, projectId: number, milestoneId: number) {
    return api(`${buildBase(teamId)}/${projectId}/milestones/${milestoneId}`, { method: 'DELETE' })
  }

  async function completeMilestone(teamId: number, projectId: number, milestoneId: number) {
    return api(`${buildBase(teamId)}/${projectId}/milestones/${milestoneId}/complete`, {
      method: 'PATCH',
    })
  }

  // === Project Todos ===
  async function getProjectTodos(teamId: number, projectId: number) {
    return api<{ data: unknown[] }>(`${buildBase(teamId)}/${projectId}/todos`)
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
  }
}
