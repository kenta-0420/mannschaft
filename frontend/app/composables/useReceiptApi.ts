import type { ReceiptResponse, ReceiptIssuerSettings, ReceiptPreset } from '~/types/receipt'

export function useReceiptApi() {
  const api = useApi()

  function buildQuery(params?: Record<string, unknown>): string {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) q.set(k, String(v))
      }
    return q.toString()
  }

  // === Admin Receipts ===
  async function getReceipts(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: ReceiptResponse[]; meta: Record<string, unknown> }>(
      `/api/v1/admin/receipts?${qs}`,
    )
  }

  async function getReceipt(receiptId: number) {
    return api<{ data: ReceiptResponse }>(`/api/v1/admin/receipts/${receiptId}`)
  }

  async function issueReceipt(body: Record<string, unknown>) {
    return api<{ data: ReceiptResponse }>('/api/v1/admin/receipts', { method: 'POST', body })
  }

  async function bulkIssueReceipts(body: Record<string, unknown>) {
    return api('/api/v1/admin/receipts/bulk', { method: 'POST', body })
  }

  async function previewReceipt(body: Record<string, unknown>) {
    return api('/api/v1/admin/receipts/preview', { method: 'POST', body })
  }

  async function approveReceipt(receiptId: number) {
    return api(`/api/v1/admin/receipts/${receiptId}/approve`, { method: 'PATCH' })
  }

  async function voidReceipt(receiptId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/admin/receipts/${receiptId}/void`, { method: 'POST', body })
  }

  async function bulkVoidReceipts(body: Record<string, unknown>) {
    return api('/api/v1/admin/receipts/bulk-void', { method: 'POST', body })
  }

  async function reissueReceipt(receiptId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/admin/receipts/${receiptId}/reissue`, { method: 'POST', body })
  }

  async function sendReceiptEmail(receiptId: number, body?: Record<string, unknown>) {
    return api(`/api/v1/admin/receipts/${receiptId}/send-email`, { method: 'POST', body })
  }

  async function downloadPdf(receiptId: number) {
    return api(`/api/v1/admin/receipts/${receiptId}/pdf`)
  }

  async function exportReceipts(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/admin/receipts/export?${qs}`)
  }

  async function getDescriptionSuggestions(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/admin/receipts/description-suggestions?${qs}`)
  }

  async function requestDownloadZip(body: Record<string, unknown>) {
    return api<{ data: { jobId: string } }>('/api/v1/admin/receipts/download-zip', {
      method: 'POST',
      body,
    })
  }

  async function getDownloadZipStatus(jobId: string) {
    return api(`/api/v1/admin/receipts/download-zip/${jobId}`)
  }

  // === Receipt Queue ===
  async function getReceiptQueue(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/admin/receipt-queue?${qs}`)
  }

  async function approveQueueItem(id: number) {
    return api(`/api/v1/admin/receipt-queue/${id}/approve`, { method: 'POST' })
  }

  async function skipQueueItem(id: number) {
    return api(`/api/v1/admin/receipt-queue/${id}/skip`, { method: 'PATCH' })
  }

  async function bulkApproveQueue(body: Record<string, unknown>) {
    return api('/api/v1/admin/receipt-queue/bulk-approve', { method: 'POST', body })
  }

  // === Settings ===
  // scopeType/scopeId は BE が全4本で必須クエリパラメータとして要求する（F08.4 D-1）。
  async function getSettings(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: string | number) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptIssuerSettings }>(`/api/v1/admin/receipt-settings?${qs}`)
  }

  // BE は PATCH（差分更新）。フル置換の PUT ではない（F08.4 §9.2）。
  async function updateSettings(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: string | number,
    body: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptIssuerSettings }>(`/api/v1/admin/receipt-settings?${qs}`, {
      method: 'PATCH',
      body,
    })
  }

  async function uploadLogo(
    scopeType: 'TEAM' | 'ORGANIZATION',
    scopeId: string | number,
    formData: FormData,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptIssuerSettings }>(`/api/v1/admin/receipt-settings/logo?${qs}`, {
      method: 'POST',
      body: formData,
    })
  }

  async function deleteLogo(scopeType: 'TEAM' | 'ORGANIZATION', scopeId: string | number) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipt-settings/logo?${qs}`, { method: 'DELETE' })
  }

  // === Presets ===
  async function getPresets() {
    return api<{ data: ReceiptPreset[] }>('/api/v1/admin/receipt-presets')
  }

  async function createPreset(body: Record<string, unknown>) {
    return api('/api/v1/admin/receipt-presets', { method: 'POST', body })
  }

  async function updatePreset(presetId: number, body: Record<string, unknown>) {
    return api(`/api/v1/admin/receipt-presets/${presetId}`, { method: 'PUT', body })
  }

  async function deletePreset(presetId: number) {
    return api(`/api/v1/admin/receipt-presets/${presetId}`, { method: 'DELETE' })
  }

  // === My Receipts ===
  async function getMyReceipts(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: ReceiptResponse[] }>(`/api/v1/my/receipts?${qs}`)
  }

  async function getMyAnnualSummary(params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/my/receipts/annual-summary?${qs}`)
  }

  async function getMyReceiptPdf(receiptId: number) {
    return api(`/api/v1/my/receipts/${receiptId}/pdf`)
  }

  return {
    getReceipts,
    getReceipt,
    issueReceipt,
    bulkIssueReceipts,
    previewReceipt,
    approveReceipt,
    voidReceipt,
    bulkVoidReceipts,
    reissueReceipt,
    sendReceiptEmail,
    downloadPdf,
    exportReceipts,
    getDescriptionSuggestions,
    requestDownloadZip,
    getDownloadZipStatus,
    getReceiptQueue,
    approveQueueItem,
    skipQueueItem,
    bulkApproveQueue,
    getSettings,
    updateSettings,
    uploadLogo,
    deleteLogo,
    getPresets,
    createPreset,
    updatePreset,
    deletePreset,
    getMyReceipts,
    getMyAnnualSummary,
    getMyReceiptPdf,
  }
}
