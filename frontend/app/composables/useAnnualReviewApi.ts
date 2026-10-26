import type {
  AnnualReviewCreateRequest,
  AnnualReviewResponse,
  AnnualReviewResponseItem,
  AnnualReviewResponseSubmitRequest,
} from '~/types/residenceStatus'

export function useAnnualReviewApi() {
  const api = useApi()

  async function listReviews(orgId: string) {
    return api<{ data: AnnualReviewResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews`,
    )
  }

  async function createReview(orgId: string, body: AnnualReviewCreateRequest) {
    return api<{ data: AnnualReviewResponse }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews`,
      { method: 'POST', body },
    )
  }

  async function closeReview(orgId: string, reviewId: string) {
    return api<{ data: AnnualReviewResponse }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews/${reviewId}/close`,
      { method: 'POST' },
    )
  }

  async function listMyReviews(orgId: string) {
    return api<{ data: AnnualReviewResponse[] }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews/my`,
    )
  }

  async function listResponses(orgId: string, reviewId: string) {
    return api<{ data: AnnualReviewResponseItem[] }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews/${reviewId}/responses`,
    )
  }

  async function submitMyResponse(
    orgId: string,
    reviewId: string,
    body: AnnualReviewResponseSubmitRequest,
  ) {
    return api<{ data: AnnualReviewResponseItem }>(
      `/api/v1/organizations/${orgId}/residence-status/annual-reviews/${reviewId}/responses/my`,
      { method: 'POST', body },
    )
  }

  return { listReviews, createReview, closeReview, listMyReviews, listResponses, submitMyResponse }
}
