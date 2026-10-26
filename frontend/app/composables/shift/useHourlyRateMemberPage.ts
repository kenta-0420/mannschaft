import { useShiftHourlyRateApi } from '~/composables/shift/useShiftHourlyRateApi'
import { useTeamMembers } from '~/composables/team/useTeamMembers'
import type { MemberResponse } from '~/types/member'
import type { ShiftHourlyRateResponse } from '~/types/shift'

/** 時給設定画面の 1 行（メンバーと、その時点で有効な時給）。 */
export interface MemberRateRow {
  member: MemberResponse
  rate: ShiftHourlyRateResponse | null
}

/** 取得結果（チーム全員ぶん）。 */
export interface HourlyRateMemberList {
  rows: MemberRateRow[]
  totalElements: number
}

/**
 * 画面の表（DataTable）が 1 ページに並べる行数。
 *
 * これは**表示の都合だけ**の値であり、サーバーへの要求回数には影響しない
 * （ページ送りはクライアント側で取得済み配列を切り出すだけ）。
 */
export const MEMBER_PAGE_SIZE = 100

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

/**
 * 時給設定画面のデータ取得（CMP-260912-1525）。
 *
 * <h2>総処理量を人数に比例させる</h2>
 * 以前は 1 ページずつサーバーに要求していた。`TeamService#getMembers` は
 * **1 ページ要求ごとに所属情報（user_roles + memberships）を全件走査**するため、
 * 管理者が全ページをめくり切ると総走査量が `N × ページ数` になる
 * （ページサイズを変えても並列を直列にしても、この総量は変わらない）。
 *
 * 現在は一括取得の経路を使う:
 * - メンバー: `GET /teams/{slug}/members/all` … 走査 1 回
 * - 時給: `GET /shifts/hourly-rates` … 1 クエリで全員ぶんの有効時給
 *
 * 画面がどれだけページを送っても**サーバーへの要求は合計 2 回**であり、
 * サーバー側の処理量は人数 N に比例する。画面内のページ送りは、取得済みの
 * 配列をクライアント側で切り出すだけなので追加の要求を発生させない。
 *
 * 全員が 1 度に揃うので、「時給未設定 N 名」は常にチーム全体の件数として出せる。
 */
export function useHourlyRateMemberPage() {
  const { getAllMembers } = useTeamMembers()
  const { getTeamEffectiveRates } = useShiftHourlyRateApi()

  /**
   * チーム全員と、その基準日時点の時給を取得する。
   *
   * @param teamSlug チームの slug（メンバー一覧 API 用）
   * @param teamId   数値のチームID（時給 API 用）
   * @param date     基準日（YYYY-MM-DD）
   */
  async function loadAll(
    teamSlug: string,
    teamId: string,
    date: string,
  ): Promise<HourlyRateMemberList> {
    const [membersRes, rates] = await Promise.all([
      getAllMembers(teamSlug),
      getTeamEffectiveRates(teamId, date),
    ])
    const members = membersRes.data
    // 時給が未設定のメンバーは時給 API の結果に含まれないため、突き合わせは Map で行う。
    const rateByUserId = new Map<number, ShiftHourlyRateResponse>()
    for (const rate of rates) {
      rateByUserId.set(rate.userId, rate)
    }

    const rows: MemberRateRow[] = members.map(member => ({
      member,
      rate: rateByUserId.get(member.userId) ?? null,
    }))

    return { rows, totalElements: rows.length }
  }

  return { loadAll }
}
