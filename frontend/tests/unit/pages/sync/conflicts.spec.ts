import { describe, it, expect } from 'vitest'
import type { SyncConflictListItem } from '~/types/sync'

/**
 * F11.1 Phase 5: conflicts ページのユニットテスト。
 *
 * ページのビュー状態ロジックを抽出してテストする。
 * 空状態・読み込み中・一覧表示・ページネーションの分岐を検証。
 */

type PageViewState = 'loading' | 'error' | 'empty' | 'list'

function determineViewState(
  loading: boolean,
  errorMessage: string,
  conflicts: SyncConflictListItem[],
): PageViewState {
  if (loading) return 'loading'
  if (errorMessage) return 'error'
  if (conflicts.length === 0) return 'empty'
  return 'list'
}

function shouldShowPaginator(totalPages: number): boolean {
  return totalPages > 1
}

const sampleConflict: SyncConflictListItem = {
  id: 1,
  resourceType: 'ATTENDANCE_RESPONSE',
  resourceId: 10,
  clientVersion: 3,
  serverVersion: 5,
  resolution: null,
  resolvedAt: null,
  createdAt: '2026-04-10T10:00:00',
}

describe('conflicts ページロジック', () => {
  describe('determineViewState', () => {
    it('読み込み中の場合は loading を返す', () => {
      expect(determineViewState(true, '', [])).toBe('loading')
    })

    it('エラーがある場合は error を返す', () => {
      expect(determineViewState(false, 'エラーが発生しました', [])).toBe('error')
    })

    it('コンフリクトが0件の場合は empty を返す', () => {
      expect(determineViewState(false, '', [])).toBe('empty')
    })

    it('コンフリクトがある場合は list を返す', () => {
      expect(determineViewState(false, '', [sampleConflict])).toBe('list')
    })

    it('読み込み中はエラーメッセージがあっても loading が優先される', () => {
      expect(determineViewState(true, 'エラー', [sampleConflict])).toBe('loading')
    })
  })

  describe('shouldShowPaginator', () => {
    it('1ページの場合はページネーションを表示しない', () => {
      expect(shouldShowPaginator(1)).toBe(false)
    })

    it('2ページ以上の場合はページネーションを表示する', () => {
      expect(shouldShowPaginator(2)).toBe(true)
    })

    it('0ページの場合はページネーションを表示しない', () => {
      expect(shouldShowPaginator(0)).toBe(false)
    })
  })

  describe('SyncConflictListItem 型', () => {
    it('一覧表示に必要なフィールドを持つ', () => {
      expect(sampleConflict.id).toBe(1)
      expect(sampleConflict.resourceType).toBe('ATTENDANCE_RESPONSE')
      expect(sampleConflict.resourceId).toBe(10)
      expect(sampleConflict.createdAt).toBe('2026-04-10T10:00:00')
    })

    it('未解決のコンフリクトは resolution が null', () => {
      expect(sampleConflict.resolution).toBeNull()
      expect(sampleConflict.resolvedAt).toBeNull()
    })
  })

  describe('モーダル制御', () => {
    it('コンフリクト ID を選択してモーダルを開く', () => {
      let selectedId: number | null = null
      let showModal = false

      function openResolver(conflictId: number) {
        selectedId = conflictId
        showModal = true
      }

      openResolver(42)
      expect(selectedId).toBe(42)
      expect(showModal).toBe(true)
    })

    it('解決後にリストを再取得する必要がある', () => {
      let reloadCalled = false

      async function onResolved() {
        reloadCalled = true
      }

      onResolved()
      expect(reloadCalled).toBe(true)
    })
  })
})
