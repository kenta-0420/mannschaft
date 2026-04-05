import type { TicketProductResponse, TicketBookResponse, TicketStats } from '~/types/ticket'

export function useTicketApi() {
  const api = useApi()
  const b = (teamId: number) => `/api/v1/teams/${teamId}`

  function buildQuery(params?: Record<string, unknown>): string {
    const q = new URLSearchParams()
    if (params)
      for (const [k, v] of Object.entries(params)) {
        if (v !== undefined && v !== null) q.set(k, String(v))
      }
    return q.toString()
  }

  // === Products ===
  async function getProducts(teamId: number) {
    return api<{ data: TicketProductResponse[] }>(`${b(teamId)}/ticket-products`)
  }
  async function createProduct(teamId: number, body: Record<string, unknown>) {
    return api<{ data: TicketProductResponse }>(`${b(teamId)}/ticket-products`, {
      method: 'POST',
      body,
    })
  }
  async function updateProduct(teamId: number, productId: number, body: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-products/${productId}`, { method: 'PUT', body })
  }
  async function deleteProduct(teamId: number, productId: number) {
    return api(`${b(teamId)}/ticket-products/${productId}`, { method: 'DELETE' })
  }
  async function checkoutProduct(teamId: number, productId: number) {
    return api<{ data: { checkoutUrl: string } }>(
      `${b(teamId)}/ticket-products/${productId}/checkout`,
      { method: 'POST' },
    )
  }

  // === Books ===
  async function getBooks(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api<{ data: TicketBookResponse[] }>(`${b(teamId)}/ticket-books?${qs}`)
  }
  async function getBook(teamId: number, bookId: number) {
    return api<{ data: TicketBookResponse }>(`${b(teamId)}/ticket-books/${bookId}`)
  }
  async function issueBook(teamId: number, body: Record<string, unknown>) {
    return api<{ data: TicketBookResponse }>(`${b(teamId)}/ticket-books/issue`, {
      method: 'POST',
      body,
    })
  }
  async function extendBook(teamId: number, bookId: number, body: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-books/${bookId}/extend`, { method: 'PATCH', body })
  }
  async function refundBook(teamId: number, bookId: number, body?: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-books/${bookId}/refund`, { method: 'POST', body })
  }

  // === Consumption ===
  async function consumeTicket(teamId: number, bookId: number, body?: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-books/${bookId}/consume`, { method: 'POST', body })
  }
  async function bulkConsume(teamId: number, body: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-books/bulk-consume`, { method: 'POST', body })
  }
  async function consumeByQr(teamId: number, body: Record<string, unknown>) {
    return api(`${b(teamId)}/ticket-books/consume-by-qr`, { method: 'POST', body })
  }
  async function voidConsumption(teamId: number, bookId: number, consumptionId: number) {
    return api(`${b(teamId)}/ticket-books/${bookId}/void/${consumptionId}`, { method: 'POST' })
  }

  // === Stats ===
  async function getStats(teamId: number) {
    return api<{ data: TicketStats }>(`${b(teamId)}/ticket-books/stats`)
  }
  async function exportStats(teamId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`${b(teamId)}/ticket-books/stats/export?${qs}`)
  }
  async function getUserTicketSummary(teamId: number, userId: number) {
    return api(`${b(teamId)}/users/${userId}/ticket-summary`)
  }

  // === My Tickets ===
  async function getMyTickets(teamId: number) {
    return api<{ data: TicketBookResponse[] }>(`${b(teamId)}/my-tickets`)
  }
  async function getMyTicketsWidget(teamId: number) {
    return api(`${b(teamId)}/my-tickets/widget`)
  }
  async function getMyTicket(teamId: number, bookId: number) {
    return api<{ data: TicketBookResponse }>(`${b(teamId)}/my-tickets/${bookId}`)
  }
  async function getMyTicketQr(teamId: number, bookId: number) {
    return api(`${b(teamId)}/my-tickets/${bookId}/qr`)
  }
  async function getMyTicketReceipt(teamId: number, bookId: number) {
    return api(`${b(teamId)}/my-tickets/${bookId}/receipt`)
  }

  // === Event Tickets ===
  async function getEventTicketTypes(eventId: number) {
    return api(`/api/v1/events/${eventId}/ticket-types`)
  }
  async function createEventTicketType(eventId: number, body: Record<string, unknown>) {
    return api(`/api/v1/events/${eventId}/ticket-types`, { method: 'POST', body })
  }
  async function getEventTicketType(eventId: number, ticketTypeId: number) {
    return api(`/api/v1/events/${eventId}/ticket-types/${ticketTypeId}`)
  }
  async function updateEventTicketType(
    eventId: number,
    ticketTypeId: number,
    body: Record<string, unknown>,
  ) {
    return api(`/api/v1/events/${eventId}/ticket-types/${ticketTypeId}`, { method: 'PATCH', body })
  }
  async function getEventTickets(eventId: number, params?: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/events/${eventId}/tickets?${qs}`)
  }
  async function getEventTicket(eventId: number, ticketId: number) {
    return api(`/api/v1/events/${eventId}/tickets/${ticketId}`)
  }
  async function getEventTicketByQr(eventId: number, params: Record<string, unknown>) {
    const qs = buildQuery(params)
    return api(`/api/v1/events/${eventId}/tickets/by-qr?${qs}`)
  }
  async function cancelEventTicket(eventId: number, ticketId: number) {
    return api(`/api/v1/events/${eventId}/tickets/${ticketId}/cancel`, { method: 'POST' })
  }

  return {
    getProducts,
    createProduct,
    updateProduct,
    deleteProduct,
    checkoutProduct,
    getBooks,
    getBook,
    issueBook,
    extendBook,
    refundBook,
    consumeTicket,
    bulkConsume,
    consumeByQr,
    voidConsumption,
    getStats,
    exportStats,
    getUserTicketSummary,
    getMyTickets,
    getMyTicketsWidget,
    getMyTicket,
    getMyTicketQr,
    getMyTicketReceipt,
    getEventTicketTypes,
    createEventTicketType,
    getEventTicketType,
    updateEventTicketType,
    getEventTickets,
    getEventTicket,
    getEventTicketByQr,
    cancelEventTicket,
  }
}
