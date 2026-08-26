import type {
  AddCardRequest,
  CreateKanbanRequest,
  MoveCardRequest,
  QuoteCard,
  QuoteKanban,
  UpdateKanbanRequest,
} from '~/types/repairPlanKanban'

/**
 * F08.8 相見積もりカンバン API クライアント（Phase 4）。
 *
 * <p>設計書 docs/features/F08.8_repair_longterm_dashboard.md Phase 4 に準拠。
 * 全 API は {@code X-Organization-Id} ヘッダで組織スコープを強制する（多テナント分離）。</p>
 */
export function useRepairPlanKanbanApi() {
  const api = useApi()

  /**
   * 共通: 組織スコープヘッダを生成。
   */
  function orgHeaders(organizationId: number): Record<string, string> {
    return { 'X-Organization-Id': String(organizationId) }
  }

  /**
   * カンバン一覧取得。
   */
  async function listKanbans(
    scope: string,
    scopeId: string,
    organizationId: number,
  ): Promise<QuoteKanban[]> {
    const res = await api<{ data: QuoteKanban[] }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-kanbans`,
      { headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * カンバン作成。
   */
  async function createKanban(
    scope: string,
    scopeId: string,
    organizationId: number,
    body: CreateKanbanRequest,
  ): Promise<QuoteKanban> {
    const res = await api<{ data: QuoteKanban }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-kanbans`,
      { method: 'POST', body, headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * カンバン詳細取得。
   */
  async function getKanban(
    scope: string,
    scopeId: string,
    kanbanId: string,
    organizationId: number,
  ): Promise<QuoteKanban> {
    const res = await api<{ data: QuoteKanban }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-kanbans/${kanbanId}`,
      { headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * カンバン更新。
   */
  async function updateKanban(
    scope: string,
    scopeId: string,
    kanbanId: string,
    organizationId: number,
    body: UpdateKanbanRequest,
  ): Promise<QuoteKanban> {
    const res = await api<{ data: QuoteKanban }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-kanbans/${kanbanId}`,
      { method: 'PATCH', body, headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * カードを追加。
   */
  async function addCard(
    scope: string,
    scopeId: string,
    kanbanId: string,
    organizationId: number,
    body: AddCardRequest,
  ): Promise<QuoteCard> {
    const res = await api<{ data: QuoteCard }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-kanbans/${kanbanId}/cards`,
      { method: 'POST', body, headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  /**
   * カードのステージを遷移する。
   */
  async function moveCard(
    scope: string,
    scopeId: string,
    cardId: string,
    organizationId: number,
    body: MoveCardRequest,
  ): Promise<QuoteCard> {
    const res = await api<{ data: QuoteCard }>(
      `/api/v1/${scope}/${scopeId}/repair-plan/quote-cards/${cardId}/move`,
      { method: 'POST', body, headers: orgHeaders(organizationId) },
    )
    return res.data
  }

  return { listKanbans, createKanban, getKanban, updateKanban, addCard, moveCard }
}
