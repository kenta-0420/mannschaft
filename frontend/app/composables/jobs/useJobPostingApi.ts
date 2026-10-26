import type { ApiResponse } from '~/types/api'
import type {
  CreateJobPostingRequest,
  FeePreviewResponse,
  JobPagedResponse,
  JobPostingResponse,
  JobPostingSummaryResponse,
  JobPostingStatus,
  SearchJobsParams,
  UpdateJobPostingRequest,
} from '~/types/jobmatching'

/**
 * F13.1 求人投稿 API クライアント（Requester 主体）。
 *
 * <p>Backend Controller: {@code JobPostingController}（/api/v1/jobs 配下）。
 * 認証は {@link useApi} で付与される Bearer トークンに依存する。</p>
 *
 * <p>ページングは BE の {@link org.springframework.data.domain.Pageable} に準拠し、
 * クエリは {@code page}（0 始まり）/{@code size}/{@code sort} を使用する。</p>
 */
export function useJobPostingApi() {
  const api = useApi()

  // ============================================================
  // 検索・取得
  // ============================================================

  /** チーム配下の求人一覧を取得する（BE: GET /api/v1/jobs）。 */
  async function searchJobs(params: SearchJobsParams) {
    const q = new URLSearchParams()
    q.set('teamId', String(params.teamId))
    if (params.status) q.set('status', params.status)
    if (params.page != null) q.set('page', String(params.page))
    if (params.size != null) q.set('size', String(params.size))
    return api<JobPagedResponse<JobPostingSummaryResponse>>(`/api/v1/jobs?${q.toString()}`)
  }

  /** 求人詳細を取得する（BE: GET /api/v1/jobs/{id}）。 */
  async function getJob(jobId: number) {
    return api<ApiResponse<JobPostingResponse>>(`/api/v1/jobs/${jobId}`)
  }

  /** 手数料プレビュー（BE: GET /api/v1/jobs/fee-preview）。 */
  async function previewFee(baseRewardJpy: number) {
    const q = new URLSearchParams()
    q.set('baseRewardJpy', String(baseRewardJpy))
    return api<ApiResponse<FeePreviewResponse>>(`/api/v1/jobs/fee-preview?${q.toString()}`)
  }

  // ============================================================
  // 作成・更新
  // ============================================================

  /** 求人を新規投稿する（DRAFT 作成）。BE: POST /api/v1/jobs */
  async function createJob(body: CreateJobPostingRequest) {
    return api<ApiResponse<JobPostingResponse>>('/api/v1/jobs', {
      method: 'POST',
      body,
    })
  }

  /** 求人を部分更新する。BE: PATCH /api/v1/jobs/{id} */
  async function updateJob(jobId: number, body: UpdateJobPostingRequest) {
    return api<ApiResponse<JobPostingResponse>>(`/api/v1/jobs/${jobId}`, {
      method: 'PATCH',
      body,
    })
  }

  // ============================================================
  // 状態遷移
  // ============================================================

  /** 求人を公開する（DRAFT → OPEN）。BE: POST /api/v1/jobs/{id}/publish */
  async function publishJob(jobId: number) {
    return api<ApiResponse<JobPostingResponse>>(`/api/v1/jobs/${jobId}/publish`, {
      method: 'POST',
    })
  }

  /** 求人の募集を終了する（OPEN → CLOSED）。BE: POST /api/v1/jobs/{id}/close */
  async function closeJob(jobId: number) {
    return api<ApiResponse<JobPostingResponse>>(`/api/v1/jobs/${jobId}/close`, {
      method: 'POST',
    })
  }

  /** 求人をキャンセルする。BE: POST /api/v1/jobs/{id}/cancel */
  async function cancelJob(jobId: number) {
    return api<ApiResponse<JobPostingResponse>>(`/api/v1/jobs/${jobId}/cancel`, {
      method: 'POST',
    })
  }

  /** 求人を論理削除する（応募者ゼロ件のみ）。BE: DELETE /api/v1/jobs/{id} */
  async function deleteJob(jobId: number) {
    return api<never>(`/api/v1/jobs/${jobId}`, {
      method: 'DELETE',
    })
  }

  return {
    searchJobs,
    getJob,
    previewFee,
    createJob,
    updateJob,
    publishJob,
    closeJob,
    cancelJob,
    deleteJob,
  }
}

/** JobPostingStatus ユニオンを画面フィルタで使うため再 export。 */
export type { JobPostingStatus }
