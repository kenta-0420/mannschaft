import { useShiftHourlyRateApi } from '~/composables/shift/useShiftHourlyRateApi'
import { useTeamMembers } from '~/composables/team/useTeamMembers'
import type { MemberResponse } from '~/types/member'
import type { ShiftHourlyRateResponse } from '~/types/shift'

/** 時給設定画面の 1 行（メンバーと、その時点で有効な時給）。 */
export interface MemberRateRow {
  member: MemberResponse
  rate: ShiftHourlyRateResponse | null
}

/** 1 ページぶんの取得結果。 */
export interface HourlyRateMemberPage {
  rows: MemberRateRow[]
  totalElements: number
  totalPages: number
}

/**
 * 1 ページあたりの時給取得を何件ずつ束ねるか。
 * 全員ぶんを同時に投げるとブラウザの同時接続数を食い潰すため区切る。
 */
const RATE_FETCH_CHUNK_SIZE = 20

/**
 * BE の `spring.data.web.pageable.max-page-size`。これを超える size を送っても
 * サーバ側で丸められるので、実際に返る件数と一致する値を使う。
 */
export const MEMBER_PAGE_SIZE = 100

/**
 * 時給設定画面のデータ取得（CMP-260910-1555 / 検分3巡目の是正）。
 *
 * <h2>なぜ「全ページ一括取得」をやめたか</h2>
 * `TeamService#getMembers` はスコープの所属情報（user_roles + memberships）を
 * **1 ページ要求ごとに全件走査**して重複排除と優先度解決を行う。したがって画面側が
 * 全ページをまとめて取りにいくと、総処理行数はメンバー数 N に対して
 * `N × ceil(N / ページサイズ)` となり二乗のままで、しかも並列要求だと負荷が一度に集中する。
 * 画面側で並列を直列に変えてもページサイズを変えても、この総量は減らない。
 *
 * そこで **画面を 1 ページずつ表示する形（ページング UI）に改めた**。
 * 1 回の表示で発行するメンバー一覧リクエストは常に **1 回だけ**であり、
 * ページ数に比例して増えることはない。
 *
 * <h2>小さなチームは 1 リクエストで完結する</h2>
 * `meta.totalPages` が 1 なら最初の 1 リクエストで全員が揃う。多くのチームはこちらに入り、
 * 「時給未設定 N 名」をチーム全体の正確な件数として出せる（{@link HourlyRateMemberPage.totalPages} で判別する）。
 * 1 ページに収まらないチームでは、未設定の件数は表示中のページに限った数として扱う。
 */
/**
 * ページャーを表示すべきか（CMP-260910-1555）。
 *
 * 1 ページに収まらないなら表示する。ここを誤ると 101 人目以降のメンバーに
 * 画面から到達できなくなり、そのメンバーの時給を設定できない。
 * 判定を純粋関数として切り出してあるのは、この 1 行をテストで直接押さえるため。
 *
 * @param totalElements チームの総メンバー数
 */
export function shouldShowPaginator(totalElements: number): boolean {
  return totalElements > MEMBER_PAGE_SIZE
}

export function useHourlyRateMemberPage() {
  const { getMembers } = useTeamMembers()
  const { getHourlyRate } = useShiftHourlyRateApi()

  /**
   * 指定ページのメンバーと、その基準日時点の時給を取得する。
   *
   * メンバー一覧の取得は **1 リクエストのみ**。時給は表示するメンバーぶんだけ引く。
   *
   * @param teamSlug チームの slug（メンバー一覧 API 用）
   * @param teamId   数値のチームID（時給 API 用）
   * @param page     0 始まりのページ番号
   * @param date     基準日（YYYY-MM-DD）
   */
  async function loadPage(
    teamSlug: string,
    teamId: string,
    page: number,
    date: string,
  ): Promise<HourlyRateMemberPage> {
    const res = await getMembers(teamSlug, { page, size: MEMBER_PAGE_SIZE })
    const members = res.data
    const rows: MemberRateRow[] = []

    for (let i = 0; i < members.length; i += RATE_FETCH_CHUNK_SIZE) {
      const chunk = members.slice(i, i + RATE_FETCH_CHUNK_SIZE)
      const resolved = await Promise.all(
        chunk.map(async (member): Promise<MemberRateRow> => {
          const rates = await getHourlyRate(teamId, member.userId, date)
          return { member, rate: rates[0] ?? null }
        }),
      )
      rows.push(...resolved)
    }

    return {
      rows,
      // BE の `PagedResponse.PageMeta` が送る総件数フィールドは `total`。
      // `totalElements` は型にだけ存在して BE は送らないため、そちらを先に読むと
      // 常に undefined になり、ページャーの表示判定が永久に偽になる（101 人目以降へ到達できない）。
      // 互換のため total が無いときだけ totalElements を見る。
      totalElements: res.meta?.total ?? res.meta?.totalElements ?? members.length,
      totalPages: res.meta?.totalPages ?? 1,
    }
  }

  return { loadPage }
}
