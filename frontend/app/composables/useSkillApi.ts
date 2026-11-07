import type {
  MemberSkillResponse,
  SkillSearchItem,
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
  async function getSkill(teamId: string, skillId: number) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}`,
    )
  }

  async function registerSkill(teamId: string, body: RegisterSkillRequest) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills`,
      { method: 'POST', body },
    )
  }

  async function updateSkill(teamId: string, skillId: number, body: UpdateSkillRequest) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSkill(teamId: string, skillId: number) {
    return api(`/api/v1/teams/${teamId}/skills/${skillId}`, { method: 'DELETE' })
  }

  async function verifySkill(teamId: string, skillId: number) {
    return api<{ data: MemberSkillResponse }>(
      `/api/v1/teams/${teamId}/skills/${skillId}/verify`,
      { method: 'PATCH' },
    )
  }

  async function getMySkills(teamId: string) {
    return api<{ data: MemberSkillResponse[] }>(
      `/api/v1/teams/${teamId}/skills/me`,
    )
  }

  async function searchSkills(teamId: string, params: Record<string, unknown> = {}) {
    const qs = buildQuery(params)
    return api<{ data: SkillSearchItem[] }>(
      `/api/v1/teams/${teamId}/skills/search${qs ? `?${qs}` : ''}`,
    )
  }

  async function getCertificateUrl(teamId: string, skillId: number) {
    return api<{ data: string }>(
      `/api/v1/teams/${teamId}/skills/${skillId}/certificate-url`,
    )
  }

  async function getSkillUploadUrl(teamId: string) {
    return api<{ data: { uploadUrl: string; s3Key: string } }>(
      `/api/v1/teams/${teamId}/skills/upload-url`,
      { method: 'POST' },
    )
  }

  // === Skill Categories ===
  /** includeInactive=true で無効化済みカテゴリも返す（BE 既定は false）。 */
  async function getSkillCategories(teamId: string, includeInactive = false) {
    return api<{ data: SkillCategoryResponse[] }>(
      `/api/v1/teams/${teamId}/skill-categories${includeInactive ? '?includeInactive=true' : ''}`,
    )
  }

  async function getSkillCategory(teamId: string, categoryId: number) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories/${categoryId}`,
    )
  }

  async function createSkillCategory(teamId: string, body: CreateSkillCategoryRequest) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories`,
      { method: 'POST', body },
    )
  }

  async function updateSkillCategory(
    teamId: string,
    categoryId: number,
    body: UpdateSkillCategoryRequest,
  ) {
    return api<{ data: SkillCategoryResponse }>(
      `/api/v1/teams/${teamId}/skill-categories/${categoryId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSkillCategory(teamId: string, categoryId: number) {
    return api(`/api/v1/teams/${teamId}/skill-categories/${categoryId}`, {
      method: 'DELETE',
    })
  }

  // === Skill Matrix ===
  async function getSkillMatrix(teamId: string) {
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
