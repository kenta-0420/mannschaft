import type { FeedbackResponse, CreateFeedbackRequest } from '~/types/feedback'

export function useFeedbackApi() {
  const api = useApi()

  async function createFeedback(body: CreateFeedbackRequest) {
    return api<{ data: FeedbackResponse }>('/api/v1/feedbacks', { method: 'POST', body })
  }

  async function getMyFeedbacks(params?: { page?: number; size?: number }) {
    const query = new URLSearchParams()
    query.set('page', String(params?.page ?? 0))
    query.set('size', String(params?.size ?? 20))
    return api<{
      data: FeedbackResponse[]
      meta: { total: number; page: number; size: number; totalPages: number }
    }>(`/api/v1/feedbacks/me?${query}`)
  }

  async function voteFeedback(id: number) {
    return api(`/api/v1/feedbacks/${id}/votes`, { method: 'POST' })
  }

  async function unvoteFeedback(id: number) {
    return api(`/api/v1/feedbacks/${id}/votes`, { method: 'DELETE' })
  }

  return {
    createFeedback,
    getMyFeedbacks,
    voteFeedback,
    unvoteFeedback,
  }
}
