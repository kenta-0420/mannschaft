import { describe, it, expect, beforeEach, vi } from 'vitest'

/**
 * F02.5 Phase 3 useActionMemoApi の週次まとめ関連メソッドのユニットテスト。
 *
 * - fetchWeeklySummaries: BlogPost API レスポンスをタイトルプレフィックスでフィルタ
 * - fetchWeeklySummaries: フィルタ後の正規化（WeeklySummary 型への変換）
 * - fetchWeeklySummaries: 週次まとめ以外の PRIVATE 記事が除外される
 * - fetchWeeklySummaries: 空レスポンスの場合
 * - getWeeklySummary: slug で詳細取得
 */

// useApi のモック
const mockFetch = vi.fn()
vi.mock('~/composables/useApi', () => ({
  useApi: () => mockFetch,
}))

// テスト対象を動的 import（モック設定後に import する必要がある）
const { useActionMemoApi } = await import('~/composables/useActionMemoApi')

function makeBlogPost(overrides: Record<string, unknown> = {}) {
  return {
    id: 1,
    title: '週次ふりかえり: 2026-03-30 〜 2026-04-05',
    body: '# 週次ふりかえり\n\nメモ件数: 15件',
    publishedAt: '2026-04-05T21:00:00',
    visibility: 'PRIVATE',
    ...overrides,
  }
}

function makePagedResponse(posts: unknown[], meta?: Record<string, unknown>) {
  return {
    data: posts,
    meta: {
      page: 0,
      size: 20,
      totalElements: posts.length,
      totalPages: 1,
      ...meta,
    },
  }
}

describe('useActionMemoApi: 週次まとめ (Phase 3)', () => {
  beforeEach(() => {
    mockFetch.mockReset()
  })

  describe('fetchWeeklySummaries', () => {
    it('タイトルが「週次ふりかえり: 」で始まる記事だけを返す', async () => {
      const weeklyPost = makeBlogPost({ id: 10 })
      const normalPost = makeBlogPost({
        id: 20,
        title: '通常のブログ記事',
        body: 'これは普通の非公開記事です',
      })
      mockFetch.mockResolvedValueOnce(makePagedResponse([weeklyPost, normalPost]))

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries()

      expect(result.data).toHaveLength(1)
      expect(result.data[0]!.id).toBe(10)
      expect(result.data[0]!.title).toBe('週次ふりかえり: 2026-03-30 〜 2026-04-05')
    })

    it('タイトルから期間（from / to）を抽出する', async () => {
      const post = makeBlogPost({
        id: 1,
        title: '週次ふりかえり: 2026-04-06 〜 2026-04-12',
      })
      mockFetch.mockResolvedValueOnce(makePagedResponse([post]))

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries()

      expect(result.data[0]!.period).toEqual({
        from: '2026-04-06',
        to: '2026-04-12',
      })
    })

    it('body が null の場合は空文字列に正規化する', async () => {
      const post = makeBlogPost({ id: 1, body: null })
      mockFetch.mockResolvedValueOnce(makePagedResponse([post]))

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries()

      expect(result.data[0]!.body).toBe('')
    })

    it('空レスポンスの場合は空配列を返す', async () => {
      mockFetch.mockResolvedValueOnce(makePagedResponse([]))

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries()

      expect(result.data).toEqual([])
      expect(result.page).toBe(0)
      expect(result.totalPages).toBe(1)
    })

    it('ページネーション情報を正しく返す', async () => {
      const post = makeBlogPost({ id: 1 })
      mockFetch.mockResolvedValueOnce(
        makePagedResponse([post], { page: 2, totalPages: 5 }),
      )

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries({ page: 2, size: 10 })

      expect(result.page).toBe(2)
      expect(result.totalPages).toBe(5)
    })

    it('visibility=PRIVATE クエリパラメータを送信する', async () => {
      mockFetch.mockResolvedValueOnce(makePagedResponse([]))

      const api = useActionMemoApi()
      await api.fetchWeeklySummaries({ page: 0, size: 20 })

      expect(mockFetch).toHaveBeenCalledTimes(1)
      const calledUrl = mockFetch.mock.calls[0]![0] as string
      expect(calledUrl).toContain('visibility=PRIVATE')
      expect(calledUrl).toContain('page=0')
      expect(calledUrl).toContain('size=20')
    })

    it('複数の週次まとめを新しい順で返す', async () => {
      const posts = [
        makeBlogPost({ id: 3, title: '週次ふりかえり: 2026-04-06 〜 2026-04-12' }),
        makeBlogPost({ id: 2, title: '週次ふりかえり: 2026-03-30 〜 2026-04-05' }),
        makeBlogPost({ id: 1, title: '週次ふりかえり: 2026-03-23 〜 2026-03-29' }),
      ]
      mockFetch.mockResolvedValueOnce(makePagedResponse(posts))

      const api = useActionMemoApi()
      const result = await api.fetchWeeklySummaries()

      expect(result.data).toHaveLength(3)
      expect(result.data[0]!.id).toBe(3)
      expect(result.data[2]!.id).toBe(1)
    })
  })

  describe('getWeeklySummary', () => {
    it('slug で詳細を取得し WeeklySummary に正規化する', async () => {
      const post = makeBlogPost({
        id: 42,
        title: '週次ふりかえり: 2026-04-06 〜 2026-04-12',
        body: '# 本文全体',
      })
      mockFetch.mockResolvedValueOnce({ data: post })

      const api = useActionMemoApi()
      const result = await api.getWeeklySummary('weekly-2026-04-06')

      expect(result.id).toBe(42)
      expect(result.body).toBe('# 本文全体')
      expect(result.period.from).toBe('2026-04-06')
      expect(result.period.to).toBe('2026-04-12')
    })
  })
})
