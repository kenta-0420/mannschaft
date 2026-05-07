/**
 * F12.5 Phase 2 — システム管理者向けエラーレポート API composable。
 *
 * <p>P2-B 範囲のメソッドのみ実装する。AI 分析 / GitHub Issue / Kanban は
 * 後続フェーズで追加する。</p>
 */

import type {
  AiAnalysisListResponse,
  AiAnalysisResponse,
  ErrorReportConfig,
  ErrorReportDetail,
  GitHubIssueCreateResponse,
  ListParams,
  ListResponse,
  TimelineResponse,
  WorkflowStage,
} from '~/types/error-report'
import type { ApiResponse } from '~/types/api'

const BASE_URL = '/api/v1/system-admin/error-reports'

export function useErrorReportAdmin() {
  const api = useApi()

  /** エラーレポート一覧を取得する。 */
  function list(params: ListParams = {}) {
    const query: Record<string, string | number> = {}
    if (params.status) query.status = params.status
    if (params.severity) query.severity = params.severity
    if (params.from) query.from = params.from
    if (params.to) query.to = params.to
    if (params.page !== undefined) query.page = params.page
    if (params.size !== undefined) query.size = params.size
    if (params.sort) query.sort = params.sort
    return api<ListResponse>(BASE_URL, { params: query })
  }

  /** エラーレポート詳細を取得する。 */
  function get(id: number) {
    return api<ApiResponse<ErrorReportDetail>>(`${BASE_URL}/${id}`)
  }

  /** ワークフロー段階を更新する（NULL は未着手にリセット）。 */
  function updateWorkflowStage(id: number, stage: WorkflowStage | null) {
    return api<ApiResponse<ErrorReportDetail>>(`${BASE_URL}/${id}/workflow-stage`, {
      method: 'PATCH',
      body: { workflowStage: stage },
    })
  }

  /** 担当者を割り当て / 解除する。 */
  function assign(id: number, assigneeId: number | null) {
    return api<ApiResponse<ErrorReportDetail>>(`${BASE_URL}/${id}/assignee`, {
      method: 'PATCH',
      body: { assigneeId },
    })
  }

  /** 管理者コメントを追加する。 */
  function addComment(id: number, content: string) {
    return api<ApiResponse<null>>(`${BASE_URL}/${id}/comments`, {
      method: 'POST',
      body: { content },
    })
  }

  /** タイムラインを取得する。 */
  function fetchTimeline(id: number, cursor?: string, limit = 50) {
    const query: Record<string, string | number> = { limit }
    if (cursor) query.cursor = cursor
    return api<ApiResponse<TimelineResponse>>(`${BASE_URL}/${id}/timeline`, { params: query })
  }

  /**
   * F12.5 Phase 2-C — AI 再分析を即時実行する。
   */
  function reanalyze(id: number) {
    return api<ApiResponse<AiAnalysisResponse>>(`${BASE_URL}/${id}/ai-analyses`, {
      method: 'POST',
    })
  }

  /**
   * F12.5 Phase 2-C — AI 分析履歴を取得する。
   */
  function fetchAiAnalyses(id: number, page = 0, size = 20) {
    return api<AiAnalysisListResponse>(`${BASE_URL}/${id}/ai-analyses`, {
      params: { page, size },
    })
  }

  /**
   * F12.5 Phase 2-D — GitHub Issue を作成する。
   */
  function createGithubIssue(id: number) {
    return api<ApiResponse<GitHubIssueCreateResponse>>(`${BASE_URL}/${id}/github-issue`, {
      method: 'POST',
    })
  }

  /**
   * F12.5 Phase 2-D — エラーレポート機能の運用設定（GitHub/AI 有効状態）を取得する。
   */
  function fetchConfig() {
    return api<ApiResponse<ErrorReportConfig>>(`${BASE_URL}/config`)
  }

  return {
    list,
    get,
    updateWorkflowStage,
    assign,
    addComment,
    fetchTimeline,
    reanalyze,
    fetchAiAnalyses,
    createGithubIssue,
    fetchConfig,
  }
}
