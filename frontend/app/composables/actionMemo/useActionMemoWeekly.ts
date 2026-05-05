/**
 * F02.5 行動メモ — Weekly Summary（週次まとめ）ドメイン。
 *
 * <p>Phase 3 リファクタにて {@code useActionMemoApi.ts} から分離。
 * fetchWeeklySummaries / getWeeklySummary の 2 関数を提供する。</p>
 *
 * <p>設計書 §11 #11: 新規 API は作らない。{@code GET /api/v1/blog/posts} の
 * ページネーション付きレスポンスを利用し、タイトルプレフィックスでクライアント側フィルタする。</p>
 */
import type {
  WeeklySummary,
  WeeklySummaryListResponse,
} from '~/types/actionMemo'
import { rethrow } from './shared/normalize'

export function useActionMemoWeekly() {
  const api = useApi()

  /**
   * F06.1 BlogPost API のレスポンス型（週次まとめ取得に必要な最小サブセット）。
   */
  type RawBlogPost = {
    id: number
    title: string
    body: string | null
    publishedAt: string | null
    visibility: string | null
  }

  type RawBlogPagedResponse = {
    data: RawBlogPost[]
    meta: {
      page: number
      size: number
      totalElements: number
      totalPages: number
    }
  }

  /** 週次ふりかえりタイトルのプレフィックス */
  const WEEKLY_TITLE_PREFIX = '週次ふりかえり: '

  /**
   * タイトルから対象期間（from / to）を抽出する。
   * 期待フォーマット: "週次ふりかえり: YYYY-MM-DD 〜 YYYY-MM-DD"
   */
  function parsePeriodFromTitle(title: string): { from: string; to: string } {
    const datePattern = /(\d{4}-\d{2}-\d{2})\s*[〜~]\s*(\d{4}-\d{2}-\d{2})/
    const match = title.match(datePattern)
    if (match) {
      return { from: match[1]!, to: match[2]! }
    }
    return { from: '', to: '' }
  }

  function normalizeBlogPostToWeeklySummary(raw: RawBlogPost): WeeklySummary {
    return {
      id: raw.id,
      title: raw.title,
      body: raw.body ?? '',
      publishedAt: raw.publishedAt ?? null,
      period: parsePeriodFromTitle(raw.title),
    }
  }

  /**
   * 週次まとめ一覧を取得する。
   *
   * <p>F06.1 BlogPost API（{@code GET /api/v1/blog/posts?visibility=PRIVATE}）を呼び、
   * タイトルが「週次ふりかえり: 」で始まるブログ記事だけをクライアント側フィルタして返す。</p>
   *
   * @param params.page ページ番号（0始まり）。デフォルト 0
   * @param params.size 1ページあたりの件数。デフォルト 20
   */
  async function fetchWeeklySummaries(params?: {
    page?: number
    size?: number
  }): Promise<WeeklySummaryListResponse> {
    const query = new URLSearchParams()
    query.set('visibility', 'PRIVATE')
    if (params?.page !== undefined) query.set('page', String(params.page))
    if (params?.size !== undefined) query.set('size', String(params.size))

    try {
      const res = await api<RawBlogPagedResponse>(`/api/v1/blog/posts?${query.toString()}`)
      const filtered = (res.data ?? [])
        .filter((post: RawBlogPost) => post.title.startsWith(WEEKLY_TITLE_PREFIX))
        .map(normalizeBlogPostToWeeklySummary)
      return {
        data: filtered,
        page: res.meta?.page ?? 0,
        totalPages: res.meta?.totalPages ?? 1,
      }
    } catch (error) {
      rethrow(error)
    }
  }

  /**
   * 週次まとめ詳細を取得する。
   *
   * <p>{@code GET /api/v1/blog/posts/{slug}} を呼ぶ。
   * 週次まとめブログの slug は Backend が自動生成するため、ID ベースではなく
   * slug ベースで取得する。ただし一覧から ID を持っている場合が多いので、
   * 一覧の body をそのまま使えば追加リクエスト不要。</p>
   */
  async function getWeeklySummary(slug: string): Promise<WeeklySummary> {
    try {
      const res = await api<{ data: RawBlogPost }>(`/api/v1/blog/posts/${slug}`)
      return normalizeBlogPostToWeeklySummary(res.data)
    } catch (error) {
      rethrow(error)
    }
  }

  return {
    fetchWeeklySummaries,
    getWeeklySummary,
  }
}
