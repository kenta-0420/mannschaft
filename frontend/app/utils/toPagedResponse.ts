import type { PagedResponse, SpringPage } from '~/types/api'

/** Spring Page を既存画面用の配列とページ情報へ変換する。 */
export function toPagedResponse<T>(page: SpringPage<T>): PagedResponse<T> {
  return {
    data: page.content,
    meta: {
      page: page.number,
      size: page.size,
      total: page.totalElements,
      totalPages: page.totalPages,
    },
  }
}
