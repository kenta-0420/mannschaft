/**
 * F08.10 選手グリッド（04_frontend_and_ux.md §G.1c / §G.15a・sports/01_soccer.md §8.5）。
 *
 * 取得源は 3 段フォールバック:
 *   1. roster（大会・F08.7.1 tournament_match_rosters 由来の先発リスト）
 *   2. チームメンバー一覧（useTeamMembers().getMembers・練習試合の既定）
 *   3. 手入力追加（上記に無い未登録選手・player_name 直接入力）
 *
 * さらに:
 *   - 「全員先発」ワンタップ（表示中メンバーを一括 STARTER 化）
 *   - 「前回先発コピー」（直近試合の STARTER appearance を新試合の先発候補にコピー＝§G.15a）
 *
 * 本 composable は「選手候補リスト・先発フラグ・GK/DF/MF/FW 分類」を司る。出場記録（appearance）の
 * POST は呼び出し側が STARTER/SUB_IN イベントとして送る（イベント駆動・01 §B.3）。
 */
import type { SoccerPosition, PlayerAppearanceResponse } from '~/types/match'
import type { MemberResponse } from '~/types/member'
import { useTeamMembers } from '~/composables/team/useTeamMembers'

/** グリッドに並ぶ選手 1 人分。 */
export interface GridPlayer {
  /** 登録ユーザー ID（未登録の手入力選手は null）。 */
  userId: number | null
  /** 表示名。 */
  name: string
  /** 背番号（任意）。 */
  jerseyNumber: number | null
  /** ポジション大分類（任意・未設定は null）。 */
  position: SoccerPosition | null
  /** 先発フラグ（true=上段スタメン / false=控え下段）。 */
  starter: boolean
}

/** ポジションの表示順（先発配置の既定・§7）。 */
export const POSITION_ORDER: readonly SoccerPosition[] = ['GK', 'DF', 'MF', 'FW'] as const

export function useMatchPlayerGrid() {
  const teamMembers = useTeamMembers()

  /** グリッドの選手候補（先発・控えを含む全候補）。 */
  const players = ref<GridPlayer[]>([])
  /** 取得源（フォールバックのどの段で埋まったか・UI 表示／テスト用）。 */
  const source = ref<'roster' | 'members' | 'empty'>('empty')

  /**
   * 選手候補をロードする（3 段フォールバック）。
   * roster が渡されればそれを最優先（段 1）、無ければチームメンバー（段 2）。
   * いずれも空なら段 3（手入力）に委ねる（players は空のまま）。
   *
   * @param teamIdStr   チーム slug（文字列・`/teams/[slug]` の slug）
   * @param roster      大会の先発 roster（任意・段 1）。GridPlayer 互換で渡す。
   */
  async function loadPlayers(
    teamIdStr: string,
    roster?: GridPlayer[] | null,
  ): Promise<void> {
    if (roster && roster.length > 0) {
      players.value = roster.map((p) => ({ ...p }))
      source.value = 'roster'
      return
    }
    // 段 2: チームメンバー一覧
    const res = await teamMembers.getMembers(teamIdStr, { page: 0, size: 100 })
    const members = res.data ?? []
    players.value = members.map((m: MemberResponse) => ({
      userId: m.userId,
      name: m.displayName,
      jerseyNumber: null,
      position: null,
      starter: false,
    }))
    source.value = players.value.length > 0 ? 'members' : 'empty'
  }

  /** 手入力で未登録選手を追加する（段 3）。 */
  function addManualPlayer(name: string, jerseyNumber?: number | null): GridPlayer | null {
    const trimmed = name.trim()
    if (!trimmed) return null
    const player: GridPlayer = {
      userId: null,
      name: trimmed,
      jerseyNumber: jerseyNumber ?? null,
      position: null,
      starter: false,
    }
    players.value = [...players.value, player]
    return player
  }

  /** 1 選手の先発フラグをトグルする（インデックス指定）。 */
  function toggleStarter(index: number): void {
    const p = players.value[index]
    if (!p) return
    p.starter = !p.starter
  }

  /** 表示中の全選手を一括先発化する（「全員先発」ワンタップ・§G.1c）。 */
  function setAllStarters(): void {
    for (const p of players.value) p.starter = true
  }

  /** 全選手の先発フラグを外す。 */
  function clearStarters(): void {
    for (const p of players.value) p.starter = false
  }

  /**
   * 直近試合の STARTER appearance を新試合の先発候補にコピーする（§G.15a 前回先発コピー）。
   * userId 一致を優先し、未登録選手（userId=null）は player_name 一致で突合する。
   * 突合した候補の starter を true・position/jerseyNumber を引き継ぐ。
   * 候補に存在しない先発（前回限りのゲスト等）は手入力選手として追加する。
   */
  function copyPreviousStarters(previous: PlayerAppearanceResponse[]): void {
    const starters = previous.filter((a) => a.starter)
    for (const a of starters) {
      const match = players.value.find((p) =>
        a.playerUserId != null
          ? p.userId === a.playerUserId
          : p.userId === null && p.name === a.playerName,
      )
      if (match) {
        match.starter = true
        if (a.position) match.position = normalizePosition(a.position)
        if (typeof a.jerseyNumber === 'number') match.jerseyNumber = a.jerseyNumber
      } else if (a.playerName || a.playerUserId != null) {
        players.value = [
          ...players.value,
          {
            userId: a.playerUserId ?? null,
            name: a.playerName ?? '',
            jerseyNumber: typeof a.jerseyNumber === 'number' ? a.jerseyNumber : null,
            position: a.position ? normalizePosition(a.position) : null,
            starter: true,
          },
        ]
      }
    }
  }

  /** 先発（上段）リスト。 */
  const starters = computed(() => players.value.filter((p) => p.starter))
  /** 控え（下段）リスト。 */
  const bench = computed(() => players.value.filter((p) => !p.starter))

  /** ポジション大分類でグルーピングした先発（GK/DF/MF/FW の順）。 */
  const startersByPosition = computed(() => {
    const grouped: Record<SoccerPosition, GridPlayer[]> = { GK: [], DF: [], MF: [], FW: [] }
    const unknown: GridPlayer[] = []
    for (const p of starters.value) {
      if (p.position && p.position in grouped) grouped[p.position].push(p)
      else unknown.push(p)
    }
    return { grouped, unknown }
  })

  return {
    players,
    source,
    starters,
    bench,
    startersByPosition,
    loadPlayers,
    addManualPlayer,
    toggleStarter,
    setAllStarters,
    clearStarters,
    copyPreviousStarters,
  }
}

/** 自由文字列 position を大分類（GK/DF/MF/FW）に正規化する（細分は先頭一致で束ねる）。 */
export function normalizePosition(raw: string): SoccerPosition | null {
  const v = raw.trim().toUpperCase()
  if (v === 'GK' || v.startsWith('GK')) return 'GK'
  if (v === 'DF' || v === 'CB' || v === 'SB' || v === 'LB' || v === 'RB') return 'DF'
  if (
    v === 'MF' ||
    v === 'DMF' ||
    v === 'CMF' ||
    v === 'OMF' ||
    v === 'SH' ||
    v === 'CM' ||
    v === 'DM' ||
    v === 'AM'
  ) {
    return 'MF'
  }
  if (v === 'FW' || v === 'CF' || v === 'WG' || v === 'ST' || v === 'LW' || v === 'RW') return 'FW'
  // 先頭文字での緩い分類
  if (v.startsWith('D')) return 'DF'
  if (v.startsWith('M')) return 'MF'
  if (v.startsWith('F') || v.startsWith('W') || v.startsWith('C')) return 'FW'
  return null
}
