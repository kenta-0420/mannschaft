import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * useCorkboardApi ユニットテスト。
 *
 * <p>コルクボード API 呼び出しが正しいパス・メソッド・ボディで実行されることを検証する。</p>
 *
 * モック方針:
 *  - `useApi` を vi.mock でスタブ化し、mockFetch（関数）を差し込む。
 *  - useCorkboardApi の各メソッドが mockFetch を期待するシグネチャで呼び出すことを
 *    expect(mockFetch).toHaveBeenCalledWith(...) で検証する。
 *
 * テストケース一覧:
 *  CORK-API-001: getBoardDetail — PERSONAL scope で /users/me/corkboards/{boardId} を呼ぶ
 *  CORK-API-002: getBoardDetail — TEAM scope で /teams/{scopeId}/corkboards/{boardId} を呼ぶ
 *  CORK-API-003: getBoardDetail — ORGANIZATION scope で /organizations/{scopeId}/corkboards/{boardId} を呼ぶ
 *  CORK-API-004: getBoardDetail — TEAM scope で scopeId=null のときエラーをスロー
 *  CORK-API-005: getBoardDetail — ORGANIZATION scope で scopeId=null のときエラーをスロー
 *  CORK-API-006: getBoardDetailByBoardId — /api/v1/corkboards/{boardId} を呼ぶ
 *  CORK-API-007: createCard — boardId + payload が正しく POST される
 *  CORK-API-008: updateCard — boardId + cardId + payload が正しく PUT される
 *  CORK-API-009: deleteCard — boardId + cardId が正しく DELETE される
 *  CORK-API-010: archiveCard（archived=true）— クエリなしで PATCH される
 *  CORK-API-011: archiveCard（archived=false）— ?archived=false クエリ付きで PATCH される
 *  CORK-API-012: batchUpdateCardPositions — positions 配列が body で送信される
 *  CORK-API-013: togglePinCard（ピン止め）— isPinned=true で PATCH される
 *  CORK-API-014: togglePinCard（ピン解除）— isPinned=false で PATCH される
 *  CORK-API-015: togglePinCard（付箋メモ付き）— userNote + noteColor が body に含まれる
 *  CORK-API-016: togglePinCard（userNote=undefined）— body に userNote が含まれない
 *  CORK-API-017: createGroup — boardId + body が POST される
 *  CORK-API-018: updateGroup — boardId + groupId + body が PUT される
 *  CORK-API-019: deleteGroup — boardId + groupId が DELETE される
 *  CORK-API-020: addCardToGroup — boardId + groupId + cardId が POST される
 *  CORK-API-021: removeCardFromGroup — boardId + groupId + cardId が DELETE される
 *  CORK-API-022: getMyBoards — /api/v1/users/me/corkboards を GET する
 *  CORK-API-023: createMyBoard — body が POST される
 *  CORK-API-024: deleteMyBoard — boardId が正しく DELETE される
 *  CORK-API-025: getTeamBoards — teamId が含まれるパスを GET する
 *  CORK-API-026: createTeamBoard — teamId + body が POST される
 *  CORK-API-027: getMyBoard — 個人ボード詳細パスを GET する
 *  CORK-API-028: updateMyBoard — boardId + body が PUT される
 */

const mockFetch = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

const { useCorkboardApi } = await import('~/composables/useCorkboardApi')

describe('useCorkboardApi', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  // ============================================================
  // getBoardDetail — scope 分岐
  // ============================================================

  describe('getBoardDetail()', () => {
    it('CORK-API-001: PERSONAL scope — /users/me/corkboards/{boardId} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.getBoardDetail('PERSONAL', null, 42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/users/me/corkboards/42')
    })

    it('CORK-API-002: TEAM scope — /teams/{scopeId}/corkboards/{boardId} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.getBoardDetail('TEAM', 10, 42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/10/corkboards/42')
    })

    it('CORK-API-003: ORGANIZATION scope — /organizations/{scopeId}/corkboards/{boardId} を呼ぶ', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.getBoardDetail('ORGANIZATION', 99, 42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/organizations/99/corkboards/42')
    })

    it('CORK-API-004: TEAM scope で scopeId=null のとき Error をスロー', async () => {
      const api = useCorkboardApi()

      await expect(api.getBoardDetail('TEAM', null, 42)).rejects.toThrow(
        'TEAM scope requires scopeId',
      )
      expect(mockFetch).not.toHaveBeenCalled()
    })

    it('CORK-API-005: ORGANIZATION scope で scopeId=null のとき Error をスロー', async () => {
      const api = useCorkboardApi()

      await expect(api.getBoardDetail('ORGANIZATION', null, 42)).rejects.toThrow(
        'ORGANIZATION scope requires scopeId',
      )
      expect(mockFetch).not.toHaveBeenCalled()
    })
  })

  // ============================================================
  // getBoardDetailByBoardId
  // ============================================================

  describe('getBoardDetailByBoardId()', () => {
    it('CORK-API-006: /api/v1/corkboards/{boardId} を GET する', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.getBoardDetailByBoardId(55)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/corkboards/55')
    })
  })

  // ============================================================
  // カード CRUD
  // ============================================================

  describe('createCard()', () => {
    it('CORK-API-007: boardId + payload が /corkboards/{boardId}/cards に POST される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
      const api = useCorkboardApi()
      const payload = {
        cardType: 'MEMO' as const,
        title: 'テストカード',
        positionX: 100,
        positionY: 200,
      }

      await api.createCard(42, payload)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards',
        expect.objectContaining({
          method: 'POST',
          body: payload,
        }),
      )
    })
  })

  describe('updateCard()', () => {
    it('CORK-API-008: boardId + cardId + payload が /corkboards/{boardId}/cards/{cardId} に PUT される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 7 } })
      const api = useCorkboardApi()
      const payload = { title: '更新後タイトル', body: '内容変更' }

      await api.updateCard(42, 7, payload)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7',
        expect.objectContaining({
          method: 'PUT',
          body: payload,
        }),
      )
    })
  })

  describe('deleteCard()', () => {
    it('CORK-API-009: boardId + cardId が /corkboards/{boardId}/cards/{cardId} に DELETE される', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useCorkboardApi()

      await api.deleteCard(42, 7)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  describe('archiveCard()', () => {
    it('CORK-API-010: archived=true のとき ?archived=false クエリなしで PATCH される', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.archiveCard(42, 7, true)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7/archive',
        expect.objectContaining({ method: 'PATCH' }),
      )
    })

    it('CORK-API-011: archived=false のとき ?archived=false クエリ付きで PATCH される', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()

      await api.archiveCard(42, 7, false)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7/archive?archived=false',
        expect.objectContaining({ method: 'PATCH' }),
      )
    })
  })

  describe('batchUpdateCardPositions()', () => {
    it('CORK-API-012: positions 配列が /corkboards/{boardId}/cards/batch-position に PATCH される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { updatedCount: 3 } })
      const api = useCorkboardApi()
      const positions = [
        { cardId: 1, positionX: 10, positionY: 20, zIndex: 1 },
        { cardId: 2, positionX: 30, positionY: 40, zIndex: 2 },
        { cardId: 3, positionX: 50, positionY: 60, zIndex: 3 },
      ]

      const result = await api.batchUpdateCardPositions(42, positions)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/batch-position',
        expect.objectContaining({
          method: 'PATCH',
          body: { positions },
        }),
      )
      expect(result).toEqual({ data: { updatedCount: 3 } })
    })
  })

  // ============================================================
  // ピン止め
  // ============================================================

  describe('togglePinCard()', () => {
    it('CORK-API-013: isPinned=true でピン止め PATCH される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 7, isPinned: true, pinnedAt: '2026-01-01T00:00:00' } })
      const api = useCorkboardApi()

      await api.togglePinCard(42, 7, true)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7/pin',
        expect.objectContaining({
          method: 'PATCH',
          body: { isPinned: true },
        }),
      )
    })

    it('CORK-API-014: isPinned=false でピン解除 PATCH される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 7, isPinned: false, pinnedAt: null } })
      const api = useCorkboardApi()

      await api.togglePinCard(42, 7, false)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7/pin',
        expect.objectContaining({
          method: 'PATCH',
          body: { isPinned: false },
        }),
      )
    })

    it('CORK-API-015: userNote + noteColor が指定されたとき body に含まれる', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 7, isPinned: true, pinnedAt: '2026-01-01T00:00:00', userNote: '重要メモ', noteColor: 'YELLOW' } })
      const api = useCorkboardApi()

      await api.togglePinCard(42, 7, true, '重要メモ', 'YELLOW')

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/cards/7/pin',
        expect.objectContaining({
          method: 'PATCH',
          body: {
            isPinned: true,
            userNote: '重要メモ',
            noteColor: 'YELLOW',
          },
        }),
      )
    })

    it('CORK-API-016: userNote=undefined のとき body に userNote キーが含まれない', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 7, isPinned: true, pinnedAt: null } })
      const api = useCorkboardApi()

      await api.togglePinCard(42, 7, true, undefined, undefined)

      const callBody = mockFetch.mock.calls[0]?.[1]?.body as Record<string, unknown>
      expect(callBody).toBeDefined()
      expect('userNote' in callBody).toBe(false)
      expect('noteColor' in callBody).toBe(false)
    })
  })

  // ============================================================
  // セクション CRUD
  // ============================================================

  describe('createGroup()', () => {
    it('CORK-API-017: boardId + body が /corkboards/{boardId}/groups に POST される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
      const api = useCorkboardApi()
      const body = { name: 'セクションA', positionX: 0, positionY: 0 }

      await api.createGroup(42, body)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/groups',
        expect.objectContaining({
          method: 'POST',
          body,
        }),
      )
    })
  })

  describe('updateGroup()', () => {
    it('CORK-API-018: boardId + groupId + body が /corkboards/{boardId}/groups/{groupId} に PUT される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 5 } })
      const api = useCorkboardApi()
      const body = { name: '更新後セクション名' }

      await api.updateGroup(42, 5, body)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/groups/5',
        expect.objectContaining({
          method: 'PUT',
          body,
        }),
      )
    })
  })

  describe('deleteGroup()', () => {
    it('CORK-API-019: boardId + groupId が /corkboards/{boardId}/groups/{groupId} に DELETE される', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useCorkboardApi()

      await api.deleteGroup(42, 5)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/groups/5',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  describe('addCardToGroup()', () => {
    it('CORK-API-020: boardId + groupId + cardId が /corkboards/{boardId}/groups/{groupId}/cards/{cardId} に POST される', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useCorkboardApi()

      await api.addCardToGroup(42, 5, 7)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/groups/5/cards/7',
        expect.objectContaining({ method: 'POST' }),
      )
    })
  })

  describe('removeCardFromGroup()', () => {
    it('CORK-API-021: boardId + groupId + cardId が /corkboards/{boardId}/groups/{groupId}/cards/{cardId} に DELETE される', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useCorkboardApi()

      await api.removeCardFromGroup(42, 5, 7)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/corkboards/42/groups/5/cards/7',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  // ============================================================
  // マイボード CRUD
  // ============================================================

  describe('getMyBoards()', () => {
    it('CORK-API-022: /api/v1/users/me/corkboards を GET する', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useCorkboardApi()

      await api.getMyBoards()

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/users/me/corkboards')
    })
  })

  describe('createMyBoard()', () => {
    it('CORK-API-023: body が /api/v1/users/me/corkboards に POST される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 1 } })
      const api = useCorkboardApi()
      const body = { name: '新しい個人ボード', backgroundStyle: 'CORK' }

      await api.createMyBoard(body)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/users/me/corkboards',
        expect.objectContaining({
          method: 'POST',
          body,
        }),
      )
    })
  })

  describe('deleteMyBoard()', () => {
    it('CORK-API-024: boardId が含まれるパスが DELETE される', async () => {
      mockFetch.mockResolvedValueOnce(undefined)
      const api = useCorkboardApi()

      await api.deleteMyBoard(33)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/users/me/corkboards/33',
        expect.objectContaining({ method: 'DELETE' }),
      )
    })
  })

  // ============================================================
  // チームボード
  // ============================================================

  describe('getTeamBoards()', () => {
    it('CORK-API-025: teamId が含まれる /api/v1/teams/{teamId}/corkboards を GET する', async () => {
      mockFetch.mockResolvedValueOnce({ data: [] })
      const api = useCorkboardApi()

      await api.getTeamBoards(10)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/teams/10/corkboards')
    })
  })

  describe('createTeamBoard()', () => {
    it('CORK-API-026: teamId + body が /api/v1/teams/{teamId}/corkboards に POST される', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 2 } })
      const api = useCorkboardApi()
      const body = { name: 'チームボード', editPolicy: 'ALL_MEMBERS' }

      await api.createTeamBoard(10, body)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/teams/10/corkboards',
        expect.objectContaining({
          method: 'POST',
          body,
        }),
      )
    })
  })

  describe('getMyBoard()', () => {
    it('CORK-API-027: /api/v1/users/me/corkboards/{id} を GET する', async () => {
      mockFetch.mockResolvedValueOnce({ data: { id: 42, cards: [] } })
      const api = useCorkboardApi()

      await api.getMyBoard(42)

      expect(mockFetch).toHaveBeenCalledWith('/api/v1/users/me/corkboards/42')
    })
  })

  describe('updateMyBoard()', () => {
    it('CORK-API-028: boardId + body が /api/v1/users/me/corkboards/{id} に PUT される', async () => {
      mockFetch.mockResolvedValueOnce({ data: {} })
      const api = useCorkboardApi()
      const body = { name: 'ボード名変更', backgroundStyle: 'WHITE' }

      await api.updateMyBoard(42, body)

      expect(mockFetch).toHaveBeenCalledWith(
        '/api/v1/users/me/corkboards/42',
        expect.objectContaining({
          method: 'PUT',
          body,
        }),
      )
    })
  })
})
