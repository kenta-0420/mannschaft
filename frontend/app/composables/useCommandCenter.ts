import dayjs from 'dayjs'
import type { Dayjs } from 'dayjs'
import type { PersonalActionItem } from '~/composables/usePersonalActionRequired'
import type { CirculationActionItem, SurveyActionItem, AttendanceActionItem } from '~/types/dashboard-scope'

/**
 * ダッシュボード司令塔ウィジェット（WidgetCommandCenter）向けの合流・分類ロジック。
 *
 * ADHD-UX戦役 第四陣: 固定パネル「今やること」。
 * usePersonalActionRequired（回覧板/アンケート/出席確認・全スコープ横断）と
 * GET /api/v1/dashboard/todos（本人の期限切れTODO）を1つのリストに合流する。
 *
 * 設計思想: 「数字＋文脈＋次の行動」を1枚に集約する。
 * ヘッダーの内訳チップとリスト行数は必ず一致させる（chip count === list count）ため、
 * カウントは常に合流後の items から導出する（別経路の二重集計をしない）。
 */

/** 司令塔アイテムの種別。 */
export type CommandCenterItemKind = 'CIRCULATION' | 'SURVEY' | 'ATTENDANCE' | 'TODO'

/** 期限分類。「今日締切」は当日23:59まで「期限切れ」に含めない（AC-9）。 */
export type DeadlineLabel = 'overdue' | 'today' | 'upcoming' | 'none'

/** GET /api/v1/dashboard/todos の 1 アイテム（snake_case のまま最小限のフィールドを保持）。 */
export interface CommandCenterTodoRawItem {
  id: number
  title: string
  status: string
  priority: string
  due_date: string | null
}

export interface CommandCenterTodoRawResponse {
  items: CommandCenterTodoRawItem[]
  overdue_count: number
  total_incomplete: number
}

/** 合流後の司令塔アイテム。 */
export interface CommandCenterItem {
  /** v-for / TransitionGroup 用の一意キー。 */
  key: string
  kind: CommandCenterItemKind
  title: string
  /** 所属スコープ名（TODO は個人のため null）。 */
  scopeName: string | null
  /** 元の期限文字列（ISO日時 or yyyy-MM-dd）。 */
  deadline: string | null
  deadlineLabel: DeadlineLabel
  /** CIRCULATION/SURVEY/ATTENDANCE のときのみ設定。モーダルに渡す元データ。 */
  actionItem?: PersonalActionItem
  /** TODO のときのみ設定。 */
  todoId?: number
}

export interface CommandCenterCounts {
  circulation: number
  survey: number
  attendance: number
  overdueTodo: number
  total: number
}

export interface CommandCenterMergeResult {
  items: CommandCenterItem[]
  counts: CommandCenterCounts
  /** usePersonalActionRequired の取得が失敗したか。 */
  actionRequiredFailed: boolean
  /** /dashboard/todos の取得が失敗したか。 */
  todoFailed: boolean
  isEmpty: boolean
}

/**
 * 期限文字列を「期限切れ / 今日 / 今後 / なし」に分類する。
 *
 * 「今日締切」は当日23:59:59まで「期限切れ」ではなく「今日」に分類する（AC-9）。
 * userTimezone を基準に判定する（WidgetPersonalTodo.vue の既存ロジックを踏襲）。
 */
export function classifyDeadline(
  deadline: string | null | undefined,
  now: Dayjs,
  timezone: string,
): DeadlineLabel {
  if (!deadline) return 'none'
  const targetDate = dayjs.tz(deadline, timezone)
  if (!targetDate.isValid()) return 'none'
  const todayStart = now.tz(timezone).startOf('day')
  const todayEnd = now.tz(timezone).endOf('day')
  if (targetDate.isBefore(todayStart)) return 'overdue'
  if (!targetDate.isAfter(todayEnd)) return 'today'
  return 'upcoming'
}

/** items から内訳カウントを導出する（表示中の行数と必ず一致させるため唯一の集計経路とする）。 */
export function computeCommandCenterCounts(items: CommandCenterItem[]): CommandCenterCounts {
  return {
    circulation: items.filter((i) => i.kind === 'CIRCULATION').length,
    survey: items.filter((i) => i.kind === 'SURVEY').length,
    attendance: items.filter((i) => i.kind === 'ATTENDANCE').length,
    overdueTodo: items.filter((i) => i.kind === 'TODO').length,
    total: items.length,
  }
}

const DEADLINE_LABEL_ORDER: Record<DeadlineLabel, number> = {
  overdue: 0,
  today: 1,
  upcoming: 2,
  none: 3,
}

/**
 * usePersonalActionRequired と /dashboard/todos の結果を合流する。
 *
 * 片方が失敗（PromiseSettledResult.status === 'rejected'）してももう片方は表示する（AC-3）。
 * TODO は overdue（期限切れ）のもののみ合流する（BE の overdue_count 判定と揃えるため、
 * カウントは判定ロジックが露見するテスト可能な classifyDeadline を用いて FE 側で再判定する）。
 */
export function mergeCommandCenterData(
  actionSettled: PromiseSettledResult<{ items: PersonalActionItem[]; totalCount: number }>,
  todoSettled: PromiseSettledResult<CommandCenterTodoRawResponse>,
  now: Dayjs,
  timezone: string,
): CommandCenterMergeResult {
  const items: CommandCenterItem[] = []

  const actionRequiredFailed = actionSettled.status === 'rejected'
  const todoFailed = todoSettled.status === 'rejected'

  if (actionSettled.status === 'fulfilled') {
    for (const a of actionSettled.value.items) {
      const deadline = a.itemType === 'ATTENDANCE' ? a.startsAt : a.deadline
      items.push({
        key: `${a.itemType}-${a.itemId}-${a.scopeType}-${a.scopeId}`,
        kind: a.itemType as CommandCenterItemKind,
        title: a.title,
        scopeName: a.scopeName,
        deadline,
        deadlineLabel: classifyDeadline(deadline, now, timezone),
        actionItem: a,
      })
    }
  }

  if (todoSettled.status === 'fulfilled') {
    for (const t of todoSettled.value.items) {
      const label = classifyDeadline(t.due_date, now, timezone)
      if (label !== 'overdue') continue
      items.push({
        key: `TODO-${t.id}`,
        kind: 'TODO',
        title: t.title,
        scopeName: null,
        deadline: t.due_date,
        deadlineLabel: label,
        todoId: t.id,
      })
    }
  }

  items.sort((x, y) => {
    const order = DEADLINE_LABEL_ORDER[x.deadlineLabel] - DEADLINE_LABEL_ORDER[y.deadlineLabel]
    if (order !== 0) return order
    if (x.deadline && y.deadline) return x.deadline.localeCompare(y.deadline)
    return 0
  })

  const counts = computeCommandCenterCounts(items)

  return {
    items,
    counts,
    actionRequiredFailed,
    todoFailed,
    isEmpty: items.length === 0,
  }
}

/** PersonalActionItem → CirculationConfirmModal 用の CirculationActionItem に変換する。 */
export function toCirculationActionItem(item: PersonalActionItem): CirculationActionItem {
  return {
    id: item.itemId,
    title: item.title,
    // 個人横断集計 API（/dashboard/action-required）は circulated_at を保持しないため空文字で埋める。
    // CirculationConfirmModal の formatDate は falsy 値を '-' 表示するため実害はない
    // （実データが必要なら BE 側に circulated_at 追加が必要・本戦役は FE のみのスコープ）。
    circulatedAt: '',
    deadline: item.deadline,
  }
}

/** PersonalActionItem → SurveyAnswerModal 用の SurveyActionItem に変換する。 */
export function toSurveyActionItem(item: PersonalActionItem): SurveyActionItem {
  return {
    id: Number(item.itemId),
    title: item.title,
    deadline: item.deadline,
  }
}

/** PersonalActionItem → AttendanceQuickModal 用の AttendanceActionItem に変換する。 */
export function toAttendanceActionItem(item: PersonalActionItem): AttendanceActionItem {
  return {
    scheduleId: Number(item.itemId),
    eventTitle: item.title,
    startsAt: item.startsAt ?? '',
  }
}

export function useCommandCenter() {
  return {
    classifyDeadline,
    computeCommandCenterCounts,
    mergeCommandCenterData,
    toCirculationActionItem,
    toSurveyActionItem,
    toAttendanceActionItem,
  }
}
