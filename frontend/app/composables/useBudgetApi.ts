import type { FiscalYearResponse, BudgetCategoryResponse, BudgetTransactionResponse, BudgetSummary, BudgetConfig } from '~/types/budget'

export function useBudgetApi() {
  const api = useApi()

  function base(scopeType: 'team' | 'organization', scopeId: number) {
    return scopeType === 'team' ? `/api/v1/teams/${scopeId}` : `/api/v1/organizations/${scopeId}`
  }

  // === Fiscal Years ===
  async function getFiscalYears(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: FiscalYearResponse[] }>(`${base(scopeType, scopeId)}/budget/fiscal-years`) }
  async function createFiscalYear(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api<{ data: FiscalYearResponse }>(`${base(scopeType, scopeId)}/budget/fiscal-years`, { method: 'POST', body }) }
  async function updateFiscalYear(scopeType: 'team' | 'organization', scopeId: number, fyId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}`, { method: 'PUT', body }) }
  async function closeFiscalYear(scopeType: 'team' | 'organization', scopeId: number, fyId: number) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/close`, { method: 'PATCH' }) }

  // === Categories ===
  async function getCategories(scopeType: 'team' | 'organization', scopeId: number, fyId: number) { return api<{ data: BudgetCategoryResponse[] }>(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/categories`) }
  async function createCategory(scopeType: 'team' | 'organization', scopeId: number, fyId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/categories`, { method: 'POST', body }) }
  async function updateCategory(scopeType: 'team' | 'organization', scopeId: number, fyId: number, catId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/categories/${catId}`, { method: 'PUT', body }) }

  // === Transactions ===
  async function getTransactions(scopeType: 'team' | 'organization', scopeId: number, fyId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) q.set(k, String(v)) }
    return api<{ data: BudgetTransactionResponse[]; meta: Record<string, unknown> }>(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/transactions?${q}`)
  }
  async function createTransaction(scopeType: 'team' | 'organization', scopeId: number, fyId: number, body: Record<string, unknown>) { return api<{ data: BudgetTransactionResponse }>(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/transactions`, { method: 'POST', body }) }
  async function reverseTransaction(scopeType: 'team' | 'organization', scopeId: number, fyId: number, txId: number, reason: string) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/transactions/${txId}/reverse`, { method: 'POST', body: { reason } }) }

  // === Summary & Report ===
  async function getSummary(scopeType: 'team' | 'organization', scopeId: number, fyId: number) { return api<{ data: BudgetSummary }>(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/summary`) }
  async function generateReport(scopeType: 'team' | 'organization', scopeId: number, fyId: number) { return api(`${base(scopeType, scopeId)}/budget/fiscal-years/${fyId}/reports`, { method: 'POST' }) }

  // === Config ===
  async function getConfig(scopeType: 'team' | 'organization', scopeId: number) { return api<{ data: BudgetConfig }>(`${base(scopeType, scopeId)}/budget/config`) }
  async function updateConfig(scopeType: 'team' | 'organization', scopeId: number, body: Record<string, unknown>) { return api(`${base(scopeType, scopeId)}/budget/config`, { method: 'PUT', body }) }

  return { getFiscalYears, createFiscalYear, updateFiscalYear, closeFiscalYear, getCategories, createCategory, updateCategory, getTransactions, createTransaction, reverseTransaction, getSummary, generateReport, getConfig, updateConfig }
}
