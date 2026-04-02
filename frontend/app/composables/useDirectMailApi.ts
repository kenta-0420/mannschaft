import type { DirectMailResponse, DirectMailTemplate } from '~/types/line'

export function useDirectMailApi() {
  const api = useApi()

  function buildBase(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Templates ===
  async function getTemplates(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: DirectMailTemplate[] }>(
      `${buildBase(scopeType, scopeId)}/direct-mail-templates`,
    )
  }

  async function createTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: DirectMailTemplate }>(
      `${buildBase(scopeType, scopeId)}/direct-mail-templates`,
      { method: 'POST', body },
    )
  }

  async function updateTemplate(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mail-templates/${id}`, {
      method: 'PUT',
      body,
    })
  }

  async function deleteTemplate(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mail-templates/${id}`, { method: 'DELETE' })
  }

  // === Mails ===
  async function getMails(scopeType: 'team' | 'organization', scopeId: number) {
    return api<{ data: DirectMailResponse[] }>(`${buildBase(scopeType, scopeId)}/direct-mails`)
  }

  async function getMail(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api<{ data: DirectMailResponse }>(`${buildBase(scopeType, scopeId)}/direct-mails/${id}`)
  }

  async function createMail(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: DirectMailResponse }>(`${buildBase(scopeType, scopeId)}/direct-mails`, {
      method: 'POST',
      body,
    })
  }

  async function updateMail(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    body: Record<string, unknown>,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mails/${id}`, { method: 'PUT', body })
  }

  async function sendMail(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mails/${id}/send`, { method: 'POST' })
  }

  async function scheduleMail(
    scopeType: 'team' | 'organization',
    scopeId: number,
    id: number,
    scheduledAt: string,
  ) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mails/${id}/schedule`, {
      method: 'POST',
      body: { scheduledAt },
    })
  }

  async function cancelMail(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api(`${buildBase(scopeType, scopeId)}/direct-mails/${id}/cancel`, { method: 'POST' })
  }

  async function getRecipients(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api<{ data: unknown[] }>(
      `${buildBase(scopeType, scopeId)}/direct-mails/${id}/recipients`,
    )
  }

  async function getMailStats(scopeType: 'team' | 'organization', scopeId: number, id: number) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/direct-mails/${id}/stats`)
  }

  async function estimateRecipients(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(
      `${buildBase(scopeType, scopeId)}/direct-mails/estimate-recipients`,
      { method: 'POST', body },
    )
  }

  async function previewMail(
    scopeType: 'team' | 'organization',
    scopeId: number,
    body: Record<string, unknown>,
  ) {
    return api<{ data: unknown }>(`${buildBase(scopeType, scopeId)}/direct-mails/preview`, {
      method: 'POST',
      body,
    })
  }

  // === Images ===
  async function uploadImage(scopeType: 'team' | 'organization', scopeId: number, body: FormData) {
    return api<{ data: { url: string } }>(`${buildBase(scopeType, scopeId)}/direct-mails/images`, {
      method: 'POST',
      body,
    })
  }

  return {
    getTemplates,
    createTemplate,
    updateTemplate,
    deleteTemplate,
    getMails,
    getMail,
    createMail,
    updateMail,
    sendMail,
    scheduleMail,
    cancelMail,
    getRecipients,
    getMailStats,
    estimateRecipients,
    previewMail,
    uploadImage,
  }
}
