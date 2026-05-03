import type {
  CreateFormSubmissionRequest,
  CreateFormTemplateRequest,
  FormSubmissionResponse,
  FormTemplateResponse,
  UpdateFormSubmissionRequest,
  UpdateFormTemplateRequest,
} from '~/types/form'
import type { PageMeta } from '~/types/api'

interface FormListParams {
  status?: string
  page?: number
  size?: number
}

export function useFormApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Form Templates ===
  async function listTemplates(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: FormListParams,
  ) {
    const query = new URLSearchParams()
    if (params?.status) query.set('status', params.status)
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: FormTemplateResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/form-templates?${query}`,
    )
  }

  async function getTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api<{ data: FormTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}`,
    )
  }

  async function createTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateFormTemplateRequest,
  ) {
    return api<{ data: FormTemplateResponse }>(`${buildBase(scopeType, scopeId)}/form-templates`, {
      method: 'POST',
      body,
    })
  }

  async function updateTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    body: UpdateFormTemplateRequest,
  ) {
    return api<{ data: FormTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/form-templates/${templateId}`, {
      method: 'DELETE',
    })
  }

  async function publishTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api<{ data: FormTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/publish`,
      { method: 'POST' },
    )
  }

  async function closeTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
  ) {
    return api<{ data: FormTemplateResponse }>(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/close`,
      { method: 'POST' },
    )
  }

  // === Template Submissions (管理者向け) ===
  async function listTemplateSubmissions(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: FormSubmissionResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/submissions?${query}`,
    )
  }

  async function approveSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    submissionId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/submissions/${submissionId}/approve`,
      { method: 'POST' },
    )
  }

  async function rejectSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    submissionId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/submissions/${submissionId}/reject`,
      { method: 'POST' },
    )
  }

  async function returnSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    templateId: number,
    submissionId: number,
  ) {
    return api(
      `${buildBase(scopeType, scopeId)}/form-templates/${templateId}/submissions/${submissionId}/return`,
      { method: 'POST' },
    )
  }

  // === Form Submissions ===
  async function createSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: CreateFormSubmissionRequest,
  ) {
    return api<{ data: FormSubmissionResponse }>(
      `${buildBase(scopeType, scopeId)}/form-submissions`,
      { method: 'POST', body },
    )
  }

  async function getSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    submissionId: number,
  ) {
    return api<{ data: FormSubmissionResponse }>(
      `${buildBase(scopeType, scopeId)}/form-submissions/${submissionId}`,
    )
  }

  async function updateSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    submissionId: number,
    body: UpdateFormSubmissionRequest,
  ) {
    return api<{ data: FormSubmissionResponse }>(
      `${buildBase(scopeType, scopeId)}/form-submissions/${submissionId}`,
      { method: 'PUT', body },
    )
  }

  async function deleteSubmission(
    scopeType: 'team' | 'organization',
    scopeId: number,
    submissionId: number,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/form-submissions/${submissionId}`, {
      method: 'DELETE',
    })
  }

  async function listMySubmissions(
    scopeType: 'team' | 'organization',
    scopeId: number,
    params?: { page?: number; size?: number },
  ) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{ data: FormSubmissionResponse[]; meta: PageMeta }>(
      `${buildBase(scopeType, scopeId)}/form-submissions/my?${query}`,
    )
  }

  // === Admin Form Presets ===
  async function listFormPresets() {
    return api<{ data: Array<Record<string, unknown>> }>('/api/v1/admin/form-presets')
  }

  async function createFormPreset(body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>('/api/v1/admin/form-presets', {
      method: 'POST',
      body,
    })
  }

  async function getFormPreset(presetId: number) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/admin/form-presets/${presetId}`)
  }

  async function updateFormPreset(presetId: number, body: Record<string, unknown>) {
    return api<{ data: Record<string, unknown> }>(`/api/v1/admin/form-presets/${presetId}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteFormPreset(presetId: number) {
    return api(`/api/v1/admin/form-presets/${presetId}`, { method: 'DELETE' })
  }

  return {
    listTemplates,
    getTemplate,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    publishTemplate,
    closeTemplate,
    listTemplateSubmissions,
    approveSubmission,
    rejectSubmission,
    returnSubmission,
    createSubmission,
    getSubmission,
    updateSubmission,
    deleteSubmission,
    listMySubmissions,
    listFormPresets,
    createFormPreset,
    getFormPreset,
    updateFormPreset,
    deleteFormPreset,
  }
}
