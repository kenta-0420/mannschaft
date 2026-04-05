import type {
  MemberSkillResponse,
  SkillCategoryResponse,
  RegisterSkillRequest,
  UpdateSkillRequest,
  CreateSkillCategoryRequest,
  UpdateSkillCategoryRequest,
  SkillMatrixResponse,
} from '~/types/skill'

export function useSkillApi() {
  const api = useApi()

  function buildQuery(params: Record<string, unknown>): string {
    const query = new URLSearchParams()
    for (const [key, value] of Object.entries(params)) {
      if (value !== undefined && value !== null) query.set(key, String(value))
    }
    return query.toString()
  }

  // === Skills ===
  async function getSkill(teamId: number, skillId: number) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}`,
    )
  }

  async function registerSkill(teamId: number, body: RegisterSkillRequest) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills`,
      { method: 'POST', body },
    )
  }

  async function updateSkill(teamId: number, skillId: number, body: UpdateSkillRequest) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSkill(teamId: number, skillId: number) {
    return api(`/api/v1/teams/${teamId}/skills/${skillId}`, { method: 'DELETE' })
  }

  async function verifySkill(teamId: number, skillId: number) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}/verify`,
      { method: 'POST' },
    )
  }

  async function getMySkills(teamId: number) {
    return api<{ data: MemberSkillResponse[] }>(
      `/api/v1/teams/${teamId}/skills/me`,
    )
  }

  async function searchSkills(teamId: number, params: Record<string, unknown> = {}) {
    const qs = buildQuery(params)
    return api<{ data: MemberSkillResponse[] }>(
      `/api/v1/teams/${teamId}/skills/search${qs ? `?${qs}` : ''}`,
    )
  }

  async function getCertificateUrl(teamId: number, skillId: number) {
    return api<{ data: { url: string } }>(
      `/api/v1/teams/${teamId}/skills/${skillId}/certificate-url`,
    )
  }

  async function getSkillUploadUrl(teamId: number) {
    return api<{ data: { uploadUrl: string; s3Key: string } }>(
      `/api/v1/teams/${teamId}/skills/upload-url`,
      { method: 'POST' },
    )
  }

  // === Skill Categories ===
  async function getSkillCategories(teamId: number) {
    return api<{ data: SkillCategoryResponse[] }>(
      `/api/v1/teams/${teamId}/skill-categories`,
    )
  }

  async function getSkillCategory(teamId: number, categoryId: number) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories/${categoryId}`,
    )
  }

  async function createSkillCategory(teamId: number, body: CreateSkillCategoryRequest) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories`,
      { method: 'POST', body },
    )
  }

  async function updateSkillCategory(
    teamId: number,
    categoryId: number,
    body: UpdateSkillCategoryRequest,
  ) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories/${categoryId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSkillCategory(teamId: number, categoryId: number) {
    return api(`/api/v1/teams/${teamId}/skill-categories/${categoryId}`, {
      method: 'DELETE',
    })
  }

  // === Skill Matrix ===
  async function getSkillMatrix(teamId: number) {
    return api<{ data: SkillMatrixResponse }>(
      `/api/v1/teams/${teamId}/skill-matrix`,
    )
  }

  return {
    getSkill,
    registerSkill,
    updateSkill,
    deleteSkill,
    verifySkill,
    getMySkills,
    searchSkills,
    getCertificateUrl,
    getSkillUploadUrl,
    getSkillCategories,
    getSkillCategory,
    createSkillCategory,
    updateSkillCategory,
    deleteSkillCategory,
    getSkillMatrix,
  }
}
