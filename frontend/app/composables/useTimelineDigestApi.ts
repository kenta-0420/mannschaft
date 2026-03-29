import type {
  DigestConfigResponse,
  DigestConfigRequest,
  DigestGenerateRequest,
  DigestGenerateResponse,
  DigestDetailResponse,
  DigestSummaryResponse,
  DigestEditRequest,
  DigestPublishRequest,
  DigestPublishResponse,
  DigestRegenerateRequest,
} from '~/types/timeline-digest'
import type { CursorMeta } from '~/types/api'

export function useTimelineDigestApi() {
  const api = useApi()
  const base = '/api/v1/timeline-digest'

  async function listDigests(cursor?: string, limit: number = 20) {
    const query = new URLSearchParams()
    if (cursor) query.set('cursor', cursor)
    query.set('limit', String(limit))
    return api<{ data: DigestSummaryResponse[]; meta: CursorMeta }>(`${base}?${query}`)
  }

  async function getDigest(digestId: number) {
    return api<{ data: DigestDetailResponse }>(`${base}/${digestId}`)
  }

  async function deleteDigest(digestId: number) {
    return api(`${base}/${digestId}`, { method: 'DELETE' })
  }

  async function editDigest(digestId: number, body: DigestEditRequest) {
    return api<{ data: DigestDetailResponse }>(`${base}/${digestId}`, { method: 'PATCH', body })
  }

  async function generateDigest(body: DigestGenerateRequest) {
    return api<{ data: DigestGenerateResponse }>(`${base}/generate`, { method: 'POST', body })
  }

  async function publishDigest(digestId: number, body: DigestPublishRequest) {
    return api<{ data: DigestPublishResponse }>(`${base}/${digestId}/publish`, {
      method: 'POST',
      body,
    })
  }

  async function regenerateDigest(digestId: number, body?: DigestRegenerateRequest) {
    return api(`${base}/${digestId}/regenerate`, { method: 'POST', body: body ?? {} })
  }

  // === Config ===
  async function getConfig() {
    return api<{ data: DigestConfigResponse }>(`${base}/config`)
  }

  async function updateConfig(body: DigestConfigRequest) {
    return api(`${base}/config`, { method: 'PUT', body })
  }

  async function deleteConfig() {
    return api(`${base}/config`, { method: 'DELETE' })
  }

  return {
    listDigests,
    getDigest,
    deleteDigest,
    editDigest,
    generateDigest,
    publishDigest,
    regenerateDigest,
    getConfig,
    updateConfig,
    deleteConfig,
  }
}
