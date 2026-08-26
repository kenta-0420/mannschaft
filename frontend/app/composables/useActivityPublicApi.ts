import { ofetch, type FetchError } from 'ofetch'
import type { PublicActivityResponse, PublicActivitySummaryResponse } from '~/types/activity'
import { resolveApiBaseUrl } from '~/composables/useApiBaseUrl'

/**
 * 公開活動記録 API Composable
 * 認証不要の公開エンドポイント /api/v1/public/activities/{id} 系を呼ぶ。
 *
 * 第三陣（公開ページタブ新設）で一覧2本（チーム/組織）を追加した。
 * 単票 {@link fetchPublicActivity} と同じ薄いラッパー方針を踏襲する
 * （baseURL 解決・404→null 正規化を共通化。usePublicApi.ts / useActivityApi.ts
 * どちらにも活動記録の公開系は存在しないため、本 composable に寄せた。
 * 判断理由は PR 本文を参照）。
 */
export function useActivityPublicApi() {
  const config = useRuntimeConfig()

  /**
   * 公開活動記録を取得する
   * @param id 活動記録 ID
   * @returns 公開活動記録、または 404 の場合は null
   * @throws 404 以外のエラーの場合はスロー
   */
  async function fetchPublicActivity(id: number): Promise<PublicActivityResponse | null> {
    try {
      const res = await ofetch<{ data: PublicActivityResponse }>(
        `/api/v1/public/activities/${id}`,
        {
          // SSR 時は NUXT_INTERNAL_API_BASE（絶対 URL）を優先する。
          // 本番 NUXT_PUBLIC_API_BASE='' では Nitro サーバーが相対パスで BE に到達できないため。
          // 詳細: docs/security/03_security_headers_and_csp.md §4.1
          baseURL: resolveApiBaseUrl(config),
        },
      )
      return res.data
    } catch (error) {
      const fetchError = error as FetchError
      if (fetchError?.response?.status === 404) {
        // PUBLIC 以外の活動記録、または存在しない ID の場合は null を返す
        return null
      }
      // 404 以外のエラー（5xx 等）はスロー
      throw error
    }
  }

  /**
   * チーム配下の公開活動記録一覧を取得する。
   *
   * BE は `List<PublicActivitySummary>` を返す（`SpringPage` ではない）ため、
   * 総件数・総ページ数は分からない。ページ送りは「返却件数 === limit なら次ページが
   * あるかもしれない」というヒューリスティックで行う（他の公開一覧のような厳密な
   * totalPages は BE 側の改修 [AC-29 相当の総件数近似問題] が解消するまで持てない）。
   *
   * @param teamId 対象チーム ID
   * @param page   ページ番号（0始まり）
   * @param limit  取得件数（BE 側で 1〜100 に丸められる）
   * @returns 公開活動記録一覧、または親チームが非公開/不在の場合は null
   */
  async function fetchPublicTeamActivities(
    teamId: string,
    page = 0,
    limit = 20,
  ): Promise<PublicActivitySummaryResponse[] | null> {
    return fetchPublicActivityList(`/api/v1/public/teams/${teamId}/activities`, page, limit)
  }

  /**
   * 組織配下の公開活動記録一覧を取得する。挙動は {@link fetchPublicTeamActivities} と同じ。
   */
  async function fetchPublicOrgActivities(
    orgId: string,
    page = 0,
    limit = 20,
  ): Promise<PublicActivitySummaryResponse[] | null> {
    return fetchPublicActivityList(`/api/v1/public/organizations/${orgId}/activities`, page, limit)
  }

  async function fetchPublicActivityList(
    path: string,
    page: number,
    limit: number,
  ): Promise<PublicActivitySummaryResponse[] | null> {
    try {
      const query = new URLSearchParams()
      query.set('page', String(page))
      query.set('limit', String(limit))
      const res = await ofetch<{ data: PublicActivitySummaryResponse[] }>(
        `${path}?${query.toString()}`,
        { baseURL: resolveApiBaseUrl(config) },
      )
      return res.data
    } catch (error) {
      const fetchError = error as FetchError
      if (fetchError?.response?.status === 404) {
        // 親スコープが非公開 / 不在（存在秘匿のため理由は区別しない）
        return null
      }
      throw error
    }
  }

  return { fetchPublicActivity, fetchPublicTeamActivities, fetchPublicOrgActivities }
}
