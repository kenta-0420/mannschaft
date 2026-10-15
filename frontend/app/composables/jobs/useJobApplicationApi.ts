import type { ApiResponse } from '~/types/api'
import type {
  ApplyRequest,
  JobApplicationResponse,
  JobContractResponse,
  JobPageParams,
  JobPagedResponse,
  RejectApplicationRequest,
} from '~/types/jobmatching'

/**
 * F13.1 求人応募 API クライアント。
 *
 * <p>Backend Controller: {@code JobApplicationController}（/api/v1/jobs/{jobId}/applications,
 * /api/v1/me/applications, /api/v1/applications/{id}）。</p>
 *
 * <p>採用確定（accept）のレスポンスは応募ではなく契約（{@link JobContractResponse}）である点に注意。
 * 「採用 = 契約生成」という業務上のセマンティクスを明確に表現するため BE 設計に合わせている。</p>
 */
export function useJobApplicationApi() {
  const api = useApi()

  // ============================================================
  // 求人配下の応募操作
  // ============================================================

  /**
   * 求人の応募者一覧を取得する（Requester 視点）。
   * BE: GET /api/v1/jobs/{jobId}/applications
   */
  async function listApplicationsByJob(jobId: number, params?: JobPageParams) {
    const q = new URLSearchParams()
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<JobPagedResponse<JobApplicationResponse>>(
      `/api/v1/jobs/${jobId}/applications${suffix}`,
    )
  }

  /**
   * 求人に応募する（Worker 視点）。
   * BE: POST /api/v1/jobs/{jobId}/apply
   */
  async function applyToJob(jobId: number, body: ApplyRequest) {
    return api<ApiResponse<JobApplicationResponse>>(`/api/v1/jobs/${jobId}/apply`, {
      method: 'POST',
      body,
    })
  }

  // ============================================================
  // 自分の応募履歴
  // ============================================================

  /** 自分の応募履歴を取得する。BE: GET /api/v1/me/applications */
  async function listMyApplications(params?: JobPageParams) {
    const q = new URLSearchParams()
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<JobPagedResponse<JobApplicationResponse>>(
      `/api/v1/me/applications${suffix}`,
    )
  }

  // ============================================================
  // 応募個別操作
  // ============================================================

  /** 応募詳細を取得する。BE: GET /api/v1/applications/{id} */
  async function getApplication(applicationId: number) {
    return api<ApiResponse<JobApplicationResponse>>(`/api/v1/applications/${applicationId}`)
  }

  /**
   * 応募を採用確定する（APPLIED → ACCEPTED、契約レコード生成）。
   * BE: POST /api/v1/applications/{id}/accept
   * レスポンスは {@link JobContractResponse}（契約成立の結果）。
   */
  async function acceptApplication(applicationId: number) {
    return api<ApiResponse<JobContractResponse>>(
      `/api/v1/applications/${applicationId}/accept`,
      { method: 'POST' },
    )
  }

  /**
   * 応募を不採用にする（APPLIED → REJECTED）。
   * BE: POST /api/v1/applications/{id}/reject
   */
  async function rejectApplication(applicationId: number, body?: RejectApplicationRequest) {
    return api<ApiResponse<JobApplicationResponse>>(
      `/api/v1/applications/${applicationId}/reject`,
      { method: 'POST', body: body ?? {} },
    )
  }

  /**
   * 応募を取り下げる（APPLIED → WITHDRAWN、応募者本人のみ）。
   * BE: POST /api/v1/applications/{id}/withdraw
   */
  async function withdrawApplication(applicationId: number) {
    return api<ApiResponse<JobApplicationResponse>>(
      `/api/v1/applications/${applicationId}/withdraw`,
      { method: 'POST' },
    )
  }

  return {
    listApplicationsByJob,
    applyToJob,
    listMyApplications,
    getApplication,
    acceptApplication,
    rejectApplication,
    withdrawApplication,
  }
}
