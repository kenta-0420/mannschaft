import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
import type { WeeklySummary } from '~/types/actionMemo'

/**
 * F02.5 Phase 3 週次まとめ閲覧画面のストアレベルテスト。
 *
 * - fetchWeeklySummaries: マウント時に fetch が呼ばれる
 * - fetchWeeklySummaries: 成功時に weeklySummaries が更新される
 * - fetchWeeklySummaries: 失敗時に weeklyError が設定される
 * - fetchWeeklySummaries: ページ追加読み込み（page > 0）で追記される
 * - 空状態: weeklySummaries が空のまま weeklyLoading = false で空状態表示の条件が満たされる
 */

const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  getMemo: vi.fn(),
  updateMemo: vi.fn(),
  deleteMemo: vi.fn(),
  linkTodo: vi.fn(),
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  publishDaily: vi.fn(),
  fetchWeeklySummaries: vi.fn(),
  getWeeklySummary: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

function makeWeeklySummary(overrides: Partial<WeeklySummary> = {}): WeeklySummary {
  return {
    id: 1,
    title: '週次ふりかえり: 2026-04-06 〜 2026-04-12',
    body: '# 週次ふりかえり\n\nメモ件数: 15件\n投稿日数: 6/7日',
    publishedAt: '2026-04-12T21:00:00',
    period: { from: '2026-04-06', to: '2026-04-12' },
    ...overrides,
  }
}

describe('useActionMemoStore: 週次まとめ (Phase 3)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.fetchWeeklySummaries.mockReset()
    apiMock.getWeeklySummary.mockReset()
  })

  describe('fetchWeeklySummaries', () => {
    it('正常系: weeklySummaries にデータが格納される', async () => {
      const summaries = [
        makeWeeklySummary({ id: 3 }),
        makeWeeklySummary({ id: 2, title: '週次ふりかえり: 2026-03-30 〜 2026-04-05' }),
      ]
      apiMock.fetchWeeklySummaries.mockResolvedValueOnce({
        data: summaries,
        page: 0,
        totalPages: 1,
      })

      const store = useActionMemoStore()
      await store.fetchWeeklySummaries(0)

      expect(store.weeklySummaries).toHaveLength(2)
      expect(store.weeklySummaries[0]!.id).toBe(3)
      expect(store.weeklyLoading).toBe(false)
      expect(store.weeklyError).toBeNull()
      expect(store.weeklyPage).toBe(0)
      expect(store.weeklyTotalPages).toBe(1)
    })

    it('失敗時: weeklyError が設定される', async () => {
      apiMock.fetchWeeklySummaries.mockRejectedValueOnce(new Error('network error'))

      const store = useActionMemoStore()
      await store.fetchWeeklySummaries(0)

      expect(store.weeklySummaries).toEqual([])
      expect(store.weeklyError).toBe('action_memo.weekly.error')
      expect(store.weeklyLoading).toBe(false)
    })

    it('空状態: weeklySummaries が空配列のまま', async () => {
      apiMock.fetchWeeklySummaries.mockResolvedValueOnce({
        data: [],
        page: 0,
        totalPages: 0,
      })

      const store = useActionMemoStore()
      await store.fetchWeeklySummaries(0)

      expect(store.weeklySummaries).toEqual([])
      expect(store.weeklyLoading).toBe(false)
      expect(store.weeklyError).toBeNull()
    })

    it('ページ追加読み込み: 既存データに追記される', async () => {
      const store = useActionMemoStore()

      // 1ページ目
      apiMock.fetchWeeklySummaries.mockResolvedValueOnce({
        data: [makeWeeklySummary({ id: 3 })],
        page: 0,
        totalPages: 2,
      })
      await store.fetchWeeklySummaries(0)
      expect(store.weeklySummaries).toHaveLength(1)

      // 2ページ目
      apiMock.fetchWeeklySummaries.mockResolvedValueOnce({
        data: [makeWeeklySummary({ id: 1 })],
        page: 1,
        totalPages: 2,
      })
      await store.fetchWeeklySummaries(1)
      expect(store.weeklySummaries).toHaveLength(2)
      expect(store.weeklySummaries[0]!.id).toBe(3)
      expect(store.weeklySummaries[1]!.id).toBe(1)
      expect(store.weeklyPage).toBe(1)
    })

    it('ロード中フラグが正しく切り替わる', async () => {
      apiMock.fetchWeeklySummaries.mockImplementationOnce(
        () => new Promise((resolve) => setTimeout(() => resolve({
          data: [],
          page: 0,
          totalPages: 0,
        }), 10)),
      )

      const store = useActionMemoStore()
      const promise = store.fetchWeeklySummaries(0)
      expect(store.weeklyLoading).toBe(true)

      await promise
      expect(store.weeklyLoading).toBe(false)
    })
  })
})
