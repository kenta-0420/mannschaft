import type {
  CorkboardResponse,
  CorkboardCard,
  CorkboardCardDetail,
  CorkboardDetail,
  CorkboardGroupDetail,
  CorkboardScope,
  CreateCardRequest,
  CreateGroupRequest,
  UpdateCardRequest,
  UpdateGroupRequest,
} from '~/types/corkboard'

export function useCorkboardApi() {
  const api = useApi()

  /**
   * F09.8 Phase B: スコープに応じたボード詳細取得を一本化するヘルパ。
   * バックエンドはスコープ別パスでのみ詳細 API を提供しているため、
   * 呼び出し側で scope/scopeId を渡してもらいルーティングする。
   *
   * - PERSONAL: GET /api/v1/users/me/corkboards/{boardId}
   * - TEAM:     GET /api/v1/teams/{scopeId}/corkboards/{boardId}
   * - ORGANIZATION: GET /api/v1/organizations/{scopeId}/corkboards/{boardId} （Phase A 未実装）
   */
  async function getBoardDetail(
    scope: CorkboardScope,
    scopeId: number | null,
    boardId: number,
  ) {
    if (scope === 'PERSONAL') {
      return api<{ data: CorkboardDetail }>(
        `/api/v1/users/me/corkboards/${boardId}`,
      )
    }
    if (scope === 'TEAM') {
      if (scopeId == null) throw new Error('TEAM scope requires scopeId')
      return api<{ data: CorkboardDetail }>(
        `/api/v1/teams/${scopeId}/corkboards/${boardId}`,
      )
    }
    // ORGANIZATION
    if (scopeId == null) throw new Error('ORGANIZATION scope requires scopeId')
    return api<{ data: CorkboardDetail }>(
      `/api/v1/organizations/${scopeId}/corkboards/${boardId}`,
    )
  }

  /**
   * F09.8 Phase A2: scope を意識せず boardId だけでボード詳細を取得する。
   * バックエンド `CorkboardLookupController#getBoardDetail` (`GET /api/v1/corkboards/{boardId}`)
   * が呼び出し元ユーザーの scope/権限を解決して詳細を返す。
   *
   * 一覧画面 (`/corkboard`) からの遷移は scope クエリを付けずに `/corkboard/{id}` に
   * 飛ぶため、詳細ページ側はこのメソッドを使えば scope 情報なしで詳細取得できる。
   */
  async function getBoardDetailByBoardId(boardId: number) {
    return api<{ data: CorkboardDetail }>(`/api/v1/corkboards/${boardId}`)
  }

  // === Personal Corkboards ===
  async function getMyBoards() {
    return api<{ data: CorkboardResponse[] }>('/api/v1/users/me/corkboards')
  }
  async function createMyBoard(body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>('/api/v1/users/me/corkboards', { method: 'POST', body })
  }
  async function getMyBoard(id: number) {
    return api<{ data: CorkboardResponse & { cards: CorkboardCard[] } }>(
      `/api/v1/users/me/corkboards/${id}`,
    )
  }
  async function updateMyBoard(id: number, body: Record<string, unknown>) {
    return api(`/api/v1/users/me/corkboards/${id}`, { method: 'PUT', body })
  }
  async function deleteMyBoard(id: number) {
    return api(`/api/v1/users/me/corkboards/${id}`, { method: 'DELETE' })
  }

  // === Team Corkboards ===
  async function getTeamBoards(teamId: number) {
    return api<{ data: CorkboardResponse[] }>(`/api/v1/teams/${teamId}/corkboards`)
  }
  async function createTeamBoard(teamId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>(`/api/v1/teams/${teamId}/corkboards`, {
      method: 'POST',
      body,
    })
  }
  async function getTeamBoard(teamId: number, id: number) {
    return api<{ data: CorkboardResponse & { cards: CorkboardCard[] } }>(
      `/api/v1/teams/${teamId}/corkboards/${id}`,
    )
  }
  async function updateTeamBoard(teamId: number, id: number, body: Record<string, unknown>) {
    return api(`/api/v1/teams/${teamId}/corkboards/${id}`, { method: 'PUT', body })
  }
  async function deleteTeamBoard(teamId: number, id: number) {
    return api(`/api/v1/teams/${teamId}/corkboards/${id}`, { method: 'DELETE' })
  }

  // === Organization Corkboards ===
  async function getOrgBoards(orgId: number) {
    return api<{ data: CorkboardResponse[] }>(`/api/v1/organizations/${orgId}/corkboards`)
  }
  async function createOrgBoard(orgId: number, body: Record<string, unknown>) {
    return api<{ data: CorkboardResponse }>(`/api/v1/organizations/${orgId}/corkboards`, {
      method: 'POST',
      body,
    })
  }

  // === Cards ===
  /**
   * カードを新規作成する。
   *
   * - リクエストは {@link CreateCardRequest} (camelCase) でバックエンド
   *   `CreateCardRequest.java` と完全整合。
   * - レスポンスは {@link CorkboardCardDetail} (Phase A 以降の DTO)。
   */
  async function createCard(boardId: number, body: CreateCardRequest) {
    return api<{ data: CorkboardCardDetail }>(`/api/v1/corkboards/${boardId}/cards`, {
      method: 'POST',
      body,
    })
  }
  /**
   * カードを更新する（部分更新）。
   *
   * - 受け付けるフィールドは {@link UpdateCardRequest} を参照。
   * - カード種別 (`cardType`) と参照先 (`referenceType` / `referenceId`) は変更不可。
   */
  async function updateCard(boardId: number, cardId: number, body: UpdateCardRequest) {
    return api<{ data: CorkboardCardDetail }>(
      `/api/v1/corkboards/${boardId}/cards/${cardId}`,
      { method: 'PUT', body },
    )
  }
  async function deleteCard(boardId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/cards/${cardId}`, { method: 'DELETE' })
  }
  /**
   * カードのアーカイブ状態を切り替える。
   *
   * - `archived = true` でアーカイブ、`false` でアンアーカイブ。
   * - バックエンド `CorkboardCardController#archiveCard` は
   *   `?archived=true|false` のクエリ引数を取り、デフォルトは `true`。
   */
  async function archiveCard(boardId: number, cardId: number, archived = true) {
    const q = archived ? '' : '?archived=false'
    return api<{ data: CorkboardCardDetail }>(
      `/api/v1/corkboards/${boardId}/cards/${cardId}/archive${q}`,
      { method: 'PATCH' },
    )
  }
  async function batchUpdateCardPositions(
    boardId: number,
    positions: Array<{ cardId: number; x: number; y: number }>,
  ) {
    return api(`/api/v1/corkboards/${boardId}/cards/batch-position`, {
      method: 'PATCH',
      body: { positions },
    })
  }
  /**
   * F09.8.1: カードのピン止め状態を切り替える。
   *
   * - `isPinned = true` でピン止め、`false` でピン止め解除。
   * - 個人ボードの所有者のみ操作可能（バックエンド側で 403 検証）。
   * - 上限超過時は 409 `CORKBOARD_013` が返る。
   */
  async function togglePinCard(boardId: number, cardId: number, isPinned: boolean) {
    return api<{ data: { id: number; isPinned: boolean; pinnedAt: string | null } }>(
      `/api/v1/corkboards/${boardId}/cards/${cardId}/pin`,
      { method: 'PATCH', body: { isPinned } },
    )
  }

  // === Groups (F09.8 Phase E) ===
  /**
   * セクション (group) を新規作成する。
   *
   * - リクエストは {@link CreateGroupRequest} で
   *   バックエンド `CreateGroupRequest.java` と完全整合（camelCase）。
   * - レスポンスは {@link CorkboardGroupDetail}。
   */
  async function createGroup(boardId: number, body: CreateGroupRequest) {
    return api<{ data: CorkboardGroupDetail }>(`/api/v1/corkboards/${boardId}/groups`, {
      method: 'POST',
      body,
    })
  }
  /**
   * セクションを更新する。
   *
   * - リクエストは {@link UpdateGroupRequest}（`name` 必須）。
   * - レスポンスは {@link CorkboardGroupDetail}。
   */
  async function updateGroup(boardId: number, groupId: number, body: UpdateGroupRequest) {
    return api<{ data: CorkboardGroupDetail }>(
      `/api/v1/corkboards/${boardId}/groups/${groupId}`,
      { method: 'PUT', body },
    )
  }
  /**
   * セクションを削除する。所属カード自体は残り、`corkboard_card_groups` 中間レコードのみ消える。
   */
  async function deleteGroup(boardId: number, groupId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}`, { method: 'DELETE' })
  }
  /**
   * カードをセクションに追加する（`corkboard_card_groups` レコード作成）。
   *
   * 既に所属していた場合のバックエンド挙動は冪等（重複は弾く or 既存維持）に依存する。
   */
  async function addCardToGroup(boardId: number, groupId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}/cards/${cardId}`, {
      method: 'POST',
    })
  }
  /**
   * カードをセクションから外す（`corkboard_card_groups` レコード削除）。
   */
  async function removeCardFromGroup(boardId: number, groupId: number, cardId: number) {
    return api(`/api/v1/corkboards/${boardId}/groups/${groupId}/cards/${cardId}`, {
      method: 'DELETE',
    })
  }

  return {
    getMyBoards,
    createMyBoard,
    getMyBoard,
    updateMyBoard,
    deleteMyBoard,
    getTeamBoards,
    createTeamBoard,
    getTeamBoard,
    updateTeamBoard,
    deleteTeamBoard,
    getOrgBoards,
    createOrgBoard,
    getBoardDetail,
    getBoardDetailByBoardId,
    createCard,
    updateCard,
    deleteCard,
    archiveCard,
    batchUpdateCardPositions,
    togglePinCard,
    createGroup,
    updateGroup,
    deleteGroup,
    addCardToGroup,
    removeCardFromGroup,
  }
}
