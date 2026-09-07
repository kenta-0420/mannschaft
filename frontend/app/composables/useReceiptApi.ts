import type { ReceiptResponse, ReceiptIssuerSettings, ReceiptPreset } from '~/types/receipt'

/** BE ReceiptAdminController / ReceiptSettingsController が受け付けるスコープ種別。 */
export type ReceiptScopeType = 'TEAM' | 'ORGANIZATION'

/** 領収書一覧のページング引数。BE は 0 起点の page と size を受ける。 */
export interface ReceiptListParams {
  page?: number
  size?: number
}

/** BE PagedResponse の実体（data + meta）。 */
export interface PagedReceiptResponse {
  data: ReceiptResponse[]
  meta: { total: number; page: number; size: number; totalPages: number }
}

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
  // BE `ReceiptAdminController` は admin 系のほぼ全エンドポイントで
  // `@RequestParam String scopeType` / `@RequestParam Long scopeId` を必須として要求する。
  // 送らないと Spring が 400 を返して画面が全く動かないため、
  // `=== Settings ===` 節と同じく第1・第2引数でスコープを受け取る（F08.4 AC-7）。
  async function getReceipts(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    params?: ReceiptListParams,
  ) {
    // BE は page を 0 起点の `page`、件数を `size` で受ける（`per_page` ではない）。
    const qs = buildQuery({ scopeType, scopeId, ...params })
    return api<PagedReceiptResponse>(`/api/v1/admin/receipts?${qs}`)
  }

  async function getReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptResponse }>(`/api/v1/admin/receipts/${receiptId}?${qs}`)
  }

  async function issueReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    body: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptResponse }>(`/api/v1/admin/receipts?${qs}`, { method: 'POST', body })
  }

  async function bulkIssueReceipts(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    body: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipts/bulk?${qs}`, { method: 'POST', body })
  }

  async function previewReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    body: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipts/preview?${qs}`, { method: 'POST', body })
  }

  async function approveReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptResponse }>(`/api/v1/admin/receipts/${receiptId}/approve?${qs}`, {
      method: 'PATCH',
    })
  }

  // BE `VoidReceiptRequest.reason` は `@NotBlank`。本文なしで叩くと 400 になるため必須引数にする。
  async function voidReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
    body: { reason: string },
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptResponse }>(`/api/v1/admin/receipts/${receiptId}/void?${qs}`, {
      method: 'POST',
      body,
    })
  }

  async function bulkVoidReceipts(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    body: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipts/bulk-void?${qs}`, { method: 'POST', body })
  }

  // BE は再発行プレビューを返す（実発行ではない）。`@RequestBody` は必須なので既定で空オブジェクトを送る。
  async function reissueReceipt(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
    body: Record<string, unknown> = {},
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipts/${receiptId}/reissue?${qs}`, { method: 'POST', body })
  }

  async function sendReceiptEmail(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
    body: Record<string, unknown> = {},
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api(`/api/v1/admin/receipts/${receiptId}/send-email?${qs}`, { method: 'POST', body })
  }

  async function downloadPdf(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    receiptId: number,
    kind?: string,
  ) {
    const qs = buildQuery({ scopeType, scopeId, kind })
    return api(`/api/v1/admin/receipts/${receiptId}/pdf?${qs}`)
  }

  async function exportReceipts(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId, ...params })
    return api(`/api/v1/admin/receipts/export?${qs}`)
  }

  async function getDescriptionSuggestions(
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    params?: Record<string, unknown>,
  ) {
    const qs = buildQuery({ scopeType, scopeId, ...params })
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
  async function getSettings(scopeType: ReceiptScopeType, scopeId: string | number) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptIssuerSettings }>(`/api/v1/admin/receipt-settings?${qs}`)
  }

  // BE は PATCH（差分更新）。フル置換の PUT ではない（F08.4 §9.2）。
  async function updateSettings(
    scopeType: ReceiptScopeType,
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
    scopeType: ReceiptScopeType,
    scopeId: string | number,
    formData: FormData,
  ) {
    const qs = buildQuery({ scopeType, scopeId })
    return api<{ data: ReceiptIssuerSettings }>(`/api/v1/admin/receipt-settings/logo?${qs}`, {
      method: 'POST',
      body: formData,
    })
  }

  async function deleteLogo(scopeType: ReceiptScopeType, scopeId: string | number) {
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
