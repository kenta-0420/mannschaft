import type {
  TeamPageResponse,
  CreateTeamPageRequest,
  UpdateTeamPageRequest,
  SectionResponse,
  CreateSectionRequest,
  UpdateSectionRequest,
  FieldResponse,
  CreateFieldRequest,
  UpdateFieldRequest,
} from '~/types/team-page'

export function useTeamPageApi() {
  const api = useApi()
  const base = '/api/v1/team/pages'
  const fieldBase = '/api/v1/team/member-fields'

  // === Pages ===
  async function listPages() {
    return api<{ data: TeamPageResponse[] }>(base)
  }

  async function getPage(pageId: number) {
    return api<{ data: TeamPageResponse }>(`${base}/${pageId}`)
  }

  async function createPage(body: CreateTeamPageRequest) {
    return api<{ data: TeamPageResponse }>(base, { method: 'POST', body })
  }

  async function updatePage(pageId: number, body: UpdateTeamPageRequest) {
    return api<{ data: TeamPageResponse }>(`${base}/${pageId}`, { method: 'PUT', body })
  }

  async function deletePage(pageId: number) {
    return api(`${base}/${pageId}`, { method: 'DELETE' })
  }

  async function publishPage(pageId: number) {
    return api(`${base}/${pageId}/publish`, { method: 'PATCH' })
  }

  async function createPreviewToken(pageId: number) {
    return api<{ data: { token: string } }>(`${base}/${pageId}/preview-token`, { method: 'POST' })
  }

  async function deletePreviewToken(pageId: number) {
    return api(`${base}/${pageId}/preview-token`, { method: 'DELETE' })
  }

  async function copyMembers(pageId: number) {
    return api(`${base}/${pageId}/copy-members`, { method: 'POST' })
  }

  // === Sections ===
  async function listSections(pageId: number) {
    return api<{ data: SectionResponse[] }>(`${base}/${pageId}/sections`)
  }

  async function createSection(pageId: number, body: CreateSectionRequest) {
    return api<{ data: SectionResponse }>(`${base}/${pageId}/sections`, { method: 'POST', body })
  }

  async function updateSection(sectionId: number, body: UpdateSectionRequest) {
    return api<{ data: SectionResponse }>(`/api/v1/team/sections/${sectionId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteSection(sectionId: number) {
    return api(`/api/v1/team/sections/${sectionId}`, { method: 'DELETE' })
  }

  // === Member Fields ===
  async function listFields() {
    return api<{ data: FieldResponse[] }>(fieldBase)
  }

  async function createField(body: CreateFieldRequest) {
    return api<{ data: FieldResponse }>(fieldBase, { method: 'POST', body })
  }

  async function updateField(fieldId: number, body: UpdateFieldRequest) {
    return api<{ data: FieldResponse }>(`${fieldBase}/${fieldId}`, { method: 'PUT', body })
  }

  async function deleteField(fieldId: number) {
    return api(`${fieldBase}/${fieldId}`, { method: 'DELETE' })
  }

  return {
    listPages,
    getPage,
    createPage,
    updatePage,
    deletePage,
    publishPage,
    createPreviewToken,
    deletePreviewToken,
    copyMembers,
    listSections,
    createSection,
    updateSection,
    deleteSection,
    listFields,
    createField,
    updateField,
    deleteField,
  }
}
