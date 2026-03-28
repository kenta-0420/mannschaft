import type { DirectMailResponse, DirectMailTemplate } from '~/types/line'

export function useDirectMailApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  async function getMails(teamId: number) { return api<{ data: DirectMailResponse[] }>(`${b(teamId)}/direct-mails`) }
  async function getMail(teamId: number, id: number) { return api<{ data: DirectMailResponse }>(`${b(teamId)}/direct-mails/${id}`) }
  async function createMail(teamId: number, body: Record<string, unknown>) { return api<{ data: DirectMailResponse }>(`${b(teamId)}/direct-mails`, { method: 'POST', body }) }
  async function updateMail(teamId: number, id: number, body: Record<string, unknown>) { return api(`${b(teamId)}/direct-mails/${id}`, { method: 'PUT', body }) }
  async function deleteMail(teamId: number, id: number) { return api(`${b(teamId)}/direct-mails/${id}`, { method: 'DELETE' }) }
  async function sendMail(teamId: number, id: number) { return api(`${b(teamId)}/direct-mails/${id}/send`, { method: 'POST' }) }
  async function scheduleMail(teamId: number, id: number, scheduledAt: string) { return api(`${b(teamId)}/direct-mails/${id}/schedule`, { method: 'POST', body: { scheduledAt } }) }
  async function cancelMail(teamId: number, id: number) { return api(`${b(teamId)}/direct-mails/${id}/cancel`, { method: 'POST' }) }
  async function getTemplates(teamId: number) { return api<{ data: DirectMailTemplate[] }>(`${b(teamId)}/direct-mail-templates`) }

  return { getMails, getMail, createMail, updateMail, deleteMail, sendMail, scheduleMail, cancelMail, getTemplates }
}
