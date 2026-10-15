import type { ApiResponse } from '~/types/api'
import type {
  CancelContractRequest,
  JobContractResponse,
  JobPageParams,
  JobPagedResponse,
  RejectCompletionRequest,
  ReportCompletionRequest,
} from '~/types/jobmatching'

/**
 * F13.1 求人契約 API クライアント。
 *
 * <p>Backend Controller: {@code JobContractController}（/api/v1/contracts/*, /api/v1/me/contracts）。
 * 採用確定そのものは {@link useJobApplicationApi#acceptApplication} が担う。
 * 本 composable は成立後の完了報告・承認・差し戻し・キャンセル・閲覧を扱う。</p>
 */
export function useJobContractApi() {
  const api = useApi()

  // ============================================================
  // 一覧・詳細
  // ============================================================

  /**
   * 自分が関与する契約一覧（Worker/Requester 兼用）。
   * BE: GET /api/v1/me/contracts
   */
  async function listMyContracts(params?: JobPageParams) {
    const q = new URLSearchParams()
    if (params?.page != null) q.set('page', String(params.page))
    if (params?.size != null) q.set('size', String(params.size))
    const suffix = q.toString() ? `?${q.toString()}` : ''
    return api<JobPagedResponse<JobContractResponse>>(`/api/v1/me/contracts${suffix}`)
  }

  /** 契約詳細を取得する。BE: GET /api/v1/contracts/{id} */
  async function getContract(contractId: number) {
    return api<ApiResponse<JobContractResponse>>(`/api/v1/contracts/${contractId}`)
  }

  // ============================================================
  // 完了フロー
  // ============================================================

  /**
   * Worker が業務完了を報告する（MATCHED → COMPLETION_REPORTED）。
   * BE: POST /api/v1/contracts/{id}/report-completion
   */
  async function reportCompletion(contractId: number, body?: ReportCompletionRequest) {
    return api<ApiResponse<JobContractResponse>>(
      `/api/v1/contracts/${contractId}/report-completion`,
      { method: 'POST', body: body ?? {} },
    )
  }

  /**
   * Requester が完了承認する（COMPLETION_REPORTED → COMPLETED）。
   * BE: POST /api/v1/contracts/{id}/approve-completion
   */
  async function approveCompletion(contractId: number) {
    return api<ApiResponse<JobContractResponse>>(
      `/api/v1/contracts/${contractId}/approve-completion`,
      { method: 'POST' },
    )
  }

  /**
   * Requester が完了を差し戻す（COMPLETION_REPORTED → MATCHED、rejection_count + 1）。
   * BE: POST /api/v1/contracts/{id}/reject-completion
   */
  async function rejectCompletion(contractId: number, body: RejectCompletionRequest) {
    return api<ApiResponse<JobContractResponse>>(
      `/api/v1/contracts/${contractId}/reject-completion`,
      { method: 'POST', body },
    )
  }

  // ============================================================
  // キャンセル
  // ============================================================

  /**
   * 契約をキャンセルする（Requester または Worker 本人）。
   * BE: POST /api/v1/contracts/{id}/cancel
   */
  async function cancelContract(contractId: number, body?: CancelContractRequest) {
    return api<ApiResponse<JobContractResponse>>(
      `/api/v1/contracts/${contractId}/cancel`,
      { method: 'POST', body: body ?? {} },
    )
  }

  return {
    listMyContracts,
    getContract,
    reportCompletion,
    approveCompletion,
    rejectCompletion,
    cancelContract,
  }
}
