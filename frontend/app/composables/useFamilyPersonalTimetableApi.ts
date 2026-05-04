import type {
  FamilyPersonalTimetable,
  FamilyWeeklyView,
} from '~/types/personal-timetable'

/**
 * F03.15 Phase 5 家族チームメンバーの個人時間割閲覧 API クライアント。
 *
 * - メモ・添付・チームリンク先などはサーバ側 DTO で除外済み
 * - 共有設定が無いリソースは 404
 */
export function useFamilyPersonalTimetableApi() {
  const api = useApi()

  function base(teamId: number, userId: number) {
    return `/api/v1/families/${teamId}/members/${userId}/personal-timetables`
  }

  async function list(teamId: number, userId: number) {
    const res = await api<{ data: FamilyPersonalTimetable[] }>(base(teamId, userId))
    return res.data
  }

  async function getWeekly(
    teamId: number,
    userId: number,
    personalTimetableId: number,
    weekOf?: string,
  ) {
    const qs = weekOf ? `?week_of=${weekOf}` : ''
    const res = await api<{ data: FamilyWeeklyView }>(
      `${base(teamId, userId)}/${personalTimetableId}/weekly${qs}`,
    )
    return res.data
  }

  return { list, getWeekly }
}
