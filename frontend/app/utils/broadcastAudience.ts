import type {
  BroadcastGroupRange,
  BroadcastTargetAudience,
  WizardFormState,
} from '~/types/announcement_broadcast'

/**
 * F01.2.1 8-E: 告知ウィザードの宛先指定まわりの純粋関数。
 *
 * - 宛先の選び方（すべて / チームを選ぶ / チームグループで選ぶ）をリクエスト項目へ変換する
 * - 「チームを選ぶ」と「チームグループで選ぶ」は併用できない（BE が 400 BROADCAST_011）ため、
 *   選んでいないモードの項目は送らない
 */

/** broadcast / audience-preview のリクエストに載せる宛先項目 */
export interface AudienceRequestFields {
  targetTeamIds: string[] | null
  targetGroupIds?: string[]
  targetGroupRange?: BroadcastGroupRange
  includeUnassigned?: boolean
}

/** ウィザードの状態から宛先項目を作る（選んでいないモードの項目は含めない） */
export function buildAudienceFields(state: WizardFormState): AudienceRequestFields {
  if (state.targetMode === 'TEAMS') {
    return { targetTeamIds: state.targetTeamIds ?? [] }
  }
  if (state.targetMode === 'GROUPS') {
    const fields: AudienceRequestFields = { targetTeamIds: null }
    if (state.targetGroupIds.length > 0) fields.targetGroupIds = state.targetGroupIds
    if (state.targetGroupRange) fields.targetGroupRange = state.targetGroupRange
    if (state.includeUnassigned) fields.includeUnassigned = true
    return fields
  }
  return { targetTeamIds: null }
}

/** 宛先を絞るモードで、何か1つでも選ばれているか（未選択なら 0 件確定なので次へを押させない） */
export function hasAudienceSelection(state: WizardFormState): boolean {
  if (state.targetMode === 'TEAMS') return (state.targetTeamIds?.length ?? 0) > 0
  if (state.targetMode === 'GROUPS') {
    return state.targetGroupIds.length > 0 || state.targetGroupRange !== null || state.includeUnassigned
  }
  return true
}

type Translate = (key: string, params?: Record<string, unknown>) => string

/**
 * 範囲に含まれるグループの ID 集合。groups は送信時に並び順で展開された記録なので、
 * 範囲の端（無ければ先頭／末尾）の間にある連続区間が範囲の分になる。端が記録に無ければ null（区別できない）。
 */
function groupIdsInRange(audience: BroadcastTargetAudience): Set<string> | null {
  const range = audience.range
  const groups = audience.groups ?? []
  if (!range) return null
  const ids = groups.map(g => g.id ?? '')
  const from = range.fromGroupId ? ids.indexOf(range.fromGroupId) : 0
  const to = range.toGroupId ? ids.lastIndexOf(range.toGroupId) : ids.length - 1
  if (from < 0 || to < 0 || from > to) return null
  return new Set(ids.slice(from, to + 1))
}

/**
 * 送信履歴の宛先ラベル（送信時の名前で残る）。
 * 範囲があれば範囲の言い方（「A 以前」「A 以降」「A 〜 B」）に、範囲の外にある個別グループ名と未分類を足した和集合を表す。
 * 範囲が無ければ個別グループ名を並べる。
 */
export function formatAudienceLabel(audience: BroadcastTargetAudience, t: Translate): string {
  if (audience.mode === 'TEAMS') return t('announcement.target_mode_teams')

  const parts: string[] = []
  const range = audience.range
  const inRange = groupIdsInRange(audience)
  if (range) {
    if (range.fromGroupId && range.toGroupId) {
      parts.push(t('announcement.target_range_between', { from: range.fromGroupName ?? '', to: range.toGroupName ?? '' }))
    }
    else if (range.toGroupId) {
      parts.push(t('announcement.target_range_up_to', { name: range.toGroupName ?? '' }))
    }
    else if (range.fromGroupId) {
      parts.push(t('announcement.target_range_from', { name: range.fromGroupName ?? '' }))
    }
  }
  // 範囲の外にある個別グループ（範囲が無ければ全グループ）
  const allGroups = audience.groups ?? []
  const extras = inRange ? allGroups.filter(g => !inRange.has(g.id ?? '')) : range ? [] : allGroups
  if (extras.length > 0) parts.push(extras.map(g => g.name ?? '').join('、'))
  if (audience.includeUnassigned) parts.push(t('announcement.target_include_unassigned'))
  return parts.join('、')
}
