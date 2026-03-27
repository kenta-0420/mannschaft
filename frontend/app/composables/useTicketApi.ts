import type { TicketProductResponse, TicketBookResponse, TicketConsumption, TicketStats } from '~/types/ticket'

export function useTicketApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  // === Products ===
  async function getProducts(teamId: number) { return api<{ data: TicketProductResponse[] }>(`${b(teamId)}/ticket-products`) }
  async function createProduct(teamId: number, body: Record<string, unknown>) { return api<{ data: TicketProductResponse }>(`${b(teamId)}/ticket-products`, { method: 'POST', body }) }
  async function updateProduct(teamId: number, productId: number, body: Record<string, unknown>) { return api(`${b(teamId)}/ticket-products/${productId}`, { method: 'PUT', body }) }
  async function deleteProduct(teamId: number, productId: number) { return api(`${b(teamId)}/ticket-products/${productId}`, { method: 'DELETE' }) }

  // === Books ===
  async function getBooks(teamId: number, params?: Record<string, unknown>) {
    const q = new URLSearchParams()
    if (params) for (const [k, v] of Object.entries(params)) { if (v !== undefined && v !== null) q.set(k, String(v)) }
    return api<{ data: TicketBookResponse[] }>(`${b(teamId)}/ticket-books?${q}`)
  }
  async function issueBook(teamId: number, body: Record<string, unknown>) { return api<{ data: TicketBookResponse }>(`${b(teamId)}/ticket-books`, { method: 'POST', body }) }
  async function checkoutBook(teamId: number, productId: number) { return api<{ data: { checkoutUrl: string } }>(`${b(teamId)}/ticket-products/${productId}/checkout`, { method: 'POST' }) }

  // === Consumption ===
  async function consumeTicket(teamId: number, bookId: number, note?: string) { return api(`${b(teamId)}/ticket-books/${bookId}/consume`, { method: 'POST', body: { note } }) }
  async function voidConsumption(teamId: number, bookId: number, consumptionId: number) { return api(`${b(teamId)}/ticket-books/${bookId}/consumptions/${consumptionId}/void`, { method: 'PATCH' }) }
  async function getConsumptions(teamId: number, bookId: number) { return api<{ data: TicketConsumption[] }>(`${b(teamId)}/ticket-books/${bookId}/consumptions`) }

  // === Stats ===
  async function getStats(teamId: number) { return api<{ data: TicketStats }>(`${b(teamId)}/ticket-stats`) }

  // === My tickets ===
  async function getMyTickets() { return api<{ data: TicketBookResponse[] }>('/api/v1/my-tickets') }

  return { getProducts, createProduct, updateProduct, deleteProduct, getBooks, issueBook, checkoutBook, consumeTicket, voidConsumption, getConsumptions, getStats, getMyTickets }
}
