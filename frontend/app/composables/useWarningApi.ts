import type {
  WarningReReviewResponse,
  CreateReReviewRequest,
  SelfCorrectRequest,
  ViolationResponse,
  YabaiUnflagResponse,
  CreateUnflagRequest,
} from '~/types/warning'

export function useWarningApi() {
  const api = useApi()

  async function requestReReview(actionId: number, body: CreateReReviewRequest) {
    return api<{ data: WarningReReviewResponse }>(`/api/v1/warnings/${actionId}/re-review`, {
      method: 'POST',
      body,
    })
  }

  async function selfCorrect(actionId: number, body: SelfCorrectRequest) {
    return api<{ data: ViolationResponse }>(`/api/v1/warnings/${actionId}/self-correct`, {
      method: 'PATCH',
      body,
    })
  }

  async function createUnflagRequest(body: CreateUnflagRequest) {
    return api<{ data: YabaiUnflagResponse }>('/api/v1/yabai/unflag-request', {
      method: 'POST',
      body,
    })
  }

  async function getUnflagRequestStatus() {
    return api<{ data: YabaiUnflagResponse }>('/api/v1/yabai/unflag-request/status')
  }

  return {
    requestReReview,
    selfCorrect,
    createUnflagRequest,
    getUnflagRequestStatus,
  }
}
