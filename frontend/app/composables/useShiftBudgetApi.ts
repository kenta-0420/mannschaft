import type {
  AlertAcknowledgeRequest,
  AlertResponse,
  AllocationCreateRequest,
  AllocationListResponse,
  AllocationResponse,
  AllocationUpdateRequest,
  ConsumptionSummaryResponse,
  FailedEventResponse,
  FailedEventStatus,
  MonthlyCloseRequest,
  MonthlyCloseResponse,
  RequiredSlotsRequest,
  RequiredSlotsResponse,
  TodoBudgetLinkCreateRequest,
  TodoBudgetLinkResponse,
} from '~/types/shiftBudget'

/**
 * F08.7 シフト予算管理 API クライアント（Phase 10-γ）。
 *
 * <p>設計書 docs/features/F08.7_shift_budget_integration.md (v1.3) §6.1 / §6.2 に準拠。
 * 全 API は {@code X-Organization-Id} ヘッダで組織スコープを強制する（多テナント分離）ため、
 * 呼び出し側は {@code organizationId} を必ず指定する。逆算 API のみヘッダ不要。</p>
 *
 * <p>Phase 範囲:</p>
 * <ul>
 *   <li>Phase 9-α: 予算→必要シフト枠数の逆算</li>
 *   <li>Phase 9-β: 予算割当 CRUD（楽観ロック）</li>
 *   <li>Phase 9-γ: TODO/プロジェクト 予算紐付</li>
 *   <li>Phase 9-δ 第2段: 閾値超過警告 + 月次締め手動起動</li>
 *   <li>Phase 9-δ 第3段: 消化サマリ（BudgetView マスキング対応）</li>
 *   <li>Phase 10-β: 失敗イベント管理</li>
 * </ul>
 */
export function useShiftBudgetApi() {
  const api = useApi()
  const BASE = '/api/v1/shift-budget'
  const TODO_LINK_BASE = '/api/v1/todo-budget/links'

  /**
   * 共通: 組織スコープヘッダを生成。
   */
  function orgHeaders(organizationId: number): Record<string, string> {
    return { 'X-Organization-Id': String(organizationId) }
  }

  // ===== 逆算 API（Phase 9-α、ヘッダ不要） =====

  /**
   * 予算→必要シフト枠数を逆算する。
   * 設計書 §4.1 / §6.2.2。ステートレス計算（DB 書き込みなし）。
   */
  async function calculateRequiredSlots(
    request: RequiredSlotsRequest,
  ): Promise<RequiredSlotsResponse> {
    const res = await api<{ data: RequiredSlotsResponse }>(`${BASE}/calc/required-slots`, {
      method: 'POST',
      body: request,
    })
    return res.data
  }

  // ===== 割当 CRUD（Phase 9-β） =====

  /**
   * シフト予算割当一覧を取得する（ページング）。
   */
  async function listAllocations(
    organizationId: number,
    page = 0,
    size = 20,
  ): Promise<AllocationListResponse> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    const res = await api<{ data: AllocationListResponse }>(
      `${BASE}/allocations?${query.toString()}`,
      { headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * シフト予算割当を作成する（BUDGET_ADMIN 必須）。
   */
  async function createAllocation(
    organizationId: number,
    request: AllocationCreateRequest,
  ): Promise<AllocationResponse> {
    const res = await api<{ data: AllocationResponse }>(`${BASE}/allocations`, {
      method: 'POST',
      headers: orgHeaders(organizationId),
      body: request,
    })
    return res.data
  }

  /**
   * シフト予算割当の詳細を取得する。
   */
  async function getAllocation(
    organizationId: number,
    id: number,
  ): Promise<AllocationResponse> {
    const res = await api<{ data: AllocationResponse }>(`${BASE}/allocations/${id}`, {
      headers: orgHeaders(organizationId),
    })
    return res.data
  }

  /**
   * シフト予算割当を更新する（楽観ロック / BUDGET_ADMIN 必須）。
   */
  async function updateAllocation(
    organizationId: number,
    id: number,
    request: AllocationUpdateRequest,
  ): Promise<AllocationResponse> {
    const res = await api<{ data: AllocationResponse }>(`${BASE}/allocations/${id}`, {
      method: 'PUT',
      headers: orgHeaders(organizationId),
      body: request,
    })
    return res.data
  }

  /**
   * シフト予算割当を論理削除する（BUDGET_ADMIN 必須）。
   */
  async function deleteAllocation(organizationId: number, id: number): Promise<void> {
    await api(`${BASE}/allocations/${id}`, {
      method: 'DELETE',
      headers: orgHeaders(organizationId),
    })
  }

  /**
   * 割当の消化サマリを取得する。
   * BUDGET_ADMIN 保有時のみ {@code by_user} に実データが入る。
   */
  async function getConsumptionSummary(
    organizationId: number,
    allocationId: number,
  ): Promise<ConsumptionSummaryResponse> {
    const res = await api<{ data: ConsumptionSummaryResponse }>(
      `${BASE}/allocations/${allocationId}/consumption-summary`,
      { headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  // ===== TODO 紐付（Phase 9-γ） =====

  /**
   * TODO/プロジェクトと予算割当を紐付ける。
   */
  async function createTodoBudgetLink(
    organizationId: number,
    request: TodoBudgetLinkCreateRequest,
  ): Promise<TodoBudgetLinkResponse> {
    const res = await api<{ data: TodoBudgetLinkResponse }>(TODO_LINK_BASE, {
      method: 'POST',
      headers: orgHeaders(organizationId),
      body: request,
    })
    return res.data
  }

  /**
   * TODO/プロジェクトの予算紐付を削除する。
   */
  async function deleteTodoBudgetLink(organizationId: number, id: number): Promise<void> {
    await api(`${TODO_LINK_BASE}/${id}`, {
      method: 'DELETE',
      headers: orgHeaders(organizationId),
    })
  }

  // ===== 警告（Phase 9-δ 第2段） =====

  /**
   * 閾値超過警告の一覧を取得する（新しい順）。
   */
  async function listAlerts(
    organizationId: number,
    page = 0,
    size = 20,
  ): Promise<AlertResponse[]> {
    const query = new URLSearchParams()
    query.set('page', String(page))
    query.set('size', String(size))
    const res = await api<{ data: AlertResponse[] }>(`${BASE}/alerts?${query.toString()}`, {
      headers: orgHeaders(organizationId),
    })
    return res.data
  }

  /**
   * 警告に対して承認応答する（BUDGET_ADMIN 必須）。
   */
  async function acknowledgeAlert(
    organizationId: number,
    id: number,
    request?: AlertAcknowledgeRequest,
  ): Promise<AlertResponse> {
    const res = await api<{ data: AlertResponse }>(`${BASE}/alerts/${id}/acknowledge`, {
      method: 'POST',
      headers: orgHeaders(organizationId),
      body: request ?? {},
    })
    return res.data
  }

  // ===== 月次締め（Phase 9-δ 第2段 + 10-β 拡張） =====

  /**
   * 月次締めバッチを手動起動する（BUDGET_ADMIN 必須）。
   * 注意: このエンドポイントは X-Organization-Id ヘッダではなくボディ内 organization_id を使う。
   */
  async function executeMonthlyClose(
    request: MonthlyCloseRequest,
  ): Promise<MonthlyCloseResponse> {
    const res = await api<{ data: MonthlyCloseResponse }>(`${BASE}/monthly-close`, {
      method: 'POST',
      body: request,
    })
    return res.data
  }

  // ===== 失敗イベント（Phase 10-β） =====

  /**
   * 失敗イベント一覧を取得する（status で絞り込み可、新しい順）。
   */
  async function listFailedEvents(
    organizationId: number,
    status?: FailedEventStatus | null,
    page = 0,
    size = 20,
  ): Promise<FailedEventResponse[]> {
    const query = new URLSearchParams()
    if (status) query.set('status', status)
    query.set('page', String(page))
    query.set('size', String(size))
    const res = await api<{ data: FailedEventResponse[] }>(
      `${BASE}/failed-events?${query.toString()}`,
      { headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * 失敗イベントを手動で再実行する（BUDGET_ADMIN 必須、EXHAUSTED にも適用可）。
   */
  async function retryFailedEvent(
    organizationId: number,
    id: number,
  ): Promise<FailedEventResponse> {
    const res = await api<{ data: FailedEventResponse }>(
      `${BASE}/failed-events/${id}/retry`,
      { method: 'POST', headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * 失敗イベントを手動補正済としてマークする（BUDGET_ADMIN 必須）。
   */
  async function resolveFailedEvent(
    organizationId: number,
    id: number,
  ): Promise<FailedEventResponse> {
    const res = await api<{ data: FailedEventResponse }>(
      `${BASE}/failed-events/${id}/resolve`,
      { method: 'POST', headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  return {
    // 逆算
    calculateRequiredSlots,
    // 割当 CRUD
    listAllocations,
    createAllocation,
    getAllocation,
    updateAllocation,
    deleteAllocation,
    getConsumptionSummary,
    // TODO 紐付
    createTodoBudgetLink,
    deleteTodoBudgetLink,
    // 警告
    listAlerts,
    acknowledgeAlert,
    // 月次締め
    executeMonthlyClose,
    // 失敗イベント
    listFailedEvents,
    retryFailedEvent,
    resolveFailedEvent,
  }
}
