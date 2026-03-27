import type { ReceiptResponse, ReceiptIssuerSettings, ReceiptPreset } from '~/types/receipt'

export function useReceiptApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Issuer Settings ===
  async function getSettings(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: ReceiptIssuerSettings }>(`${base(scopeType, scopeId)}/receipt-settings`) }
  async function updateSettings(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/receipt-settings`, { method: 'PUT', body }) }

  // === Receipts ===
  async function getReceipts(scopeType: 'team' | 'organization', scopeId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) q.set(k, String(v)) }
    return api<{ data: ReceiptResponse[]; meta: Record<string, unknown> }>(`${base(scopeType, scopeId)}/receipts?${q}`)
  }
  async function getReceipt(scopeType: 'team' | 'organization', scopeId: number, receiptId: number) { return api<{ data: ReceiptResponse }>(`${base(scopeType, scopeId)}/receipts/${receiptId}`) }
  async function issueReceipt(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api<{ data: ReceiptResponse }>(`${base(scopeType, scopeId)}/receipts`, { method: 'POST', body }) }
  async function voidReceipt(scopeType: 'team' | 'organization', scopeId: number, receiptId: number, reason: string) { return api(`${base(scopeType, scopeId)}/receipts/${receiptId}/void`, { method: 'PATCH', body: { reason } }) }
  async function downloadPdf(scopeType: 'team' | 'organization', scopeId: number, receiptId: number) { return api(`${base(scopeType, scopeId)}/receipts/${receiptId}/pdf`) }

  // === Presets ===
  async function getPresets(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: ReceiptPreset[] }>(`${base(scopeType, scopeId)}/receipt-presets`) }
  async function createPreset(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/receipt-presets`, { method: 'POST', body }) }

  return { getSettings, updateSettings, getReceipts, getReceipt, issueReceipt, voidReceipt, downloadPdf, getPresets, createPreset }
}
