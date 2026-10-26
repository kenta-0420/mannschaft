import type { FeedbackResponse } from '~/types/feedback'
import type { PagedResponse, ApiResponse } from '~/types/api'

export interface SystemAdminFeedbackParams {
  status?: string
  page?: number
  size?: number
  sort?: string
}

/**
 * システム管理者向け目安箱 API composable（F10.1）。
 * GET /api/v1/system-admin/feedbacks — GENERAL スコープ・scopeId IS NULL 全件
 */
export function useSystemAdminFeedbackApi() {
  const api = useApi()

  /**
   * 目安箱一覧を取得する（GENERAL スコープ・scopeId IS NULL 全件）。
   */
  async function getFeedbacks(params?: SystemAdminFeedbackParams) {
    return api<PagedResponse<FeedbackResponse>>('/api/v1/system-admin/feedbacks', {
      query: params,
    })
  }

  /**
   * 目安箱に回答する。
   */
  async function respondToFeedback(
    id: number,
    body: { adminResponse: string; isPublicResponse?: boolean },
  ) {
    return api<ApiResponse<FeedbackResponse>>(`/api/v1/system-admin/feedbacks/${id}/respond`, {
      method: 'PATCH',
      body,
    })
  }

  /**
   * 目安箱のステータスを変更する。
   */
  async function updateFeedbackStatus(id: number, body: { status: string }) {
    return api<ApiResponse<FeedbackResponse>>(`/api/v1/system-admin/feedbacks/${id}/status`, {
      method: 'PATCH',
      body,
    })
  }

  return { getFeedbacks, respondToFeedback, updateFeedbackStatus }
}
