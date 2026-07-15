import { describe, it, expect } from 'vitest'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import {
  classifyDeadline,
  computeCommandCenterCounts,
  mergeCommandCenterData,
  toCirculationActionItem,
  toSurveyActionItem,
  toAttendanceActionItem,
  type CommandCenterItem,
  type CommandCenterTodoRawResponse,
} from '~/composables/useCommandCenter'
import type { PersonalActionItem } from '~/composables/usePersonalActionRequired'

dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * useCommandCenter ユニットテスト。
 *
 * ダッシュボード司令塔「今やること」パネルの合流・分類ロジックを検証する。
 * 検証観点:
 *   CC-001〜004: classifyDeadline の境界（今日23:59 / 昨日 / 明日 / null）
 *   CC-005〜007: mergeCommandCenterData の片系失敗時の縮退（AC-3）
 *   CC-008: 0件判定（AC-2）
 *   CC-009: TODO は overdue のもののみ合流する
 *   CC-010: チップ件数とリスト件数が必ず一致する（computeCommandCenterCounts は items 由来）
 *   CC-011〜013: モーダル向け変換関数
 */

const TZ = 'Asia/Tokyo'

function actionItem(overrides: Partial<PersonalActionItem> = {}): PersonalActionItem {
  return {
    itemType: 'CIRCULATION',
    scopeType: 'TEAM',
    scopeId: 1,
    scopeSlug: 'team-a',
    scopeName: 'チームA',
    itemId: '100',
    title: '回覧板タイトル',
    circulatedAt: null,
    deadline: null,
    startsAt: null,
    ...overrides,
  }
}

describe('classifyDeadline', () => {
  const now = dayjs.tz('2026-07-14T10:00:00', TZ)

  it('CC-001: 当日23:59は「今日」に分類される（期限切れではない）', () => {
    expect(classifyDeadline('2026-07-14T23:59:00', now, TZ)).toBe('today')
  })

  it('CC-002: 昨日は「期限切れ」に分類される', () => {
    expect(classifyDeadline('2026-07-13T23:59:00', now, TZ)).toBe('overdue')
  })

  it('CC-003: 明日は「今後」に分類される', () => {
    expect(classifyDeadline('2026-07-15T00:00:00', now, TZ)).toBe('upcoming')
  })

  it('CC-004: nullは「なし」に分類される', () => {
    expect(classifyDeadline(null, now, TZ)).toBe('none')
  })

  it('CC-004b: 当日00:00（日付のみのTODO期限相当）は「今日」に分類される', () => {
    expect(classifyDeadline('2026-07-14', now, TZ)).toBe('today')
  })
})

describe('mergeCommandCenterData', () => {
  const now = dayjs.tz('2026-07-14T10:00:00', TZ)

  it('CC-005: 両系とも成功時、回覧板・アンケート・出席確認・期限切れTODOが合流する', () => {
    const actionResult = {
      status: 'fulfilled' as const,
      value: {
        items: [
          actionItem({ itemType: 'CIRCULATION', itemId: '1', title: '回覧1' }),
          actionItem({ itemType: 'SURVEY', itemId: '2', title: 'アンケート1' }),
          actionItem({
            itemType: 'ATTENDANCE',
            itemId: '3',
            title: '出欠1',
            startsAt: '2026-07-20T10:00:00',
          }),
        ],
        totalCount: 3,
      },
    }
    const todoResult: PromiseSettledResult<CommandCenterTodoRawResponse> = {
      status: 'fulfilled',
      value: {
        items: [
          { id: 10, title: '期限切れTODO', status: 'OPEN', priority: 'HIGH', due_date: '2026-07-10' },
        ],
        overdue_count: 1,
        total_incomplete: 1,
      },
    }

    const result = mergeCommandCenterData(actionResult, todoResult, now, TZ)

    expect(result.items).toHaveLength(4)
    expect(result.counts).toEqual({ circulation: 1, survey: 1, attendance: 1, overdueTodo: 1, total: 4 })
    expect(result.actionRequiredFailed).toBe(false)
    expect(result.todoFailed).toBe(false)
    expect(result.isEmpty).toBe(false)
  })

  it('CC-006: action-requiredが失敗してもtodoは表示される（縮退・AC-3）', () => {
    const actionResult: PromiseSettledResult<{ items: PersonalActionItem[]; totalCount: number }> = {
      status: 'rejected',
      reason: new Error('network error'),
    }
    const todoResult: PromiseSettledResult<CommandCenterTodoRawResponse> = {
      status: 'fulfilled',
      value: {
        items: [
          { id: 10, title: '期限切れTODO', status: 'OPEN', priority: 'HIGH', due_date: '2026-07-10' },
        ],
        overdue_count: 1,
        total_incomplete: 1,
      },
    }

    const result = mergeCommandCenterData(actionResult, todoResult, now, TZ)

    expect(result.actionRequiredFailed).toBe(true)
    expect(result.todoFailed).toBe(false)
    expect(result.items).toHaveLength(1)
    expect(result.items[0]!.kind).toBe('TODO')
  })

  it('CC-007: todoが失敗してもaction-requiredは表示される（縮退・AC-3）', () => {
    const actionResult = {
      status: 'fulfilled' as const,
      value: {
        items: [actionItem({ itemType: 'SURVEY', itemId: '2', title: 'アンケート1' })],
        totalCount: 1,
      },
    }
    const todoResult: PromiseSettledResult<CommandCenterTodoRawResponse> = {
      status: 'rejected',
      reason: new Error('network error'),
    }

    const result = mergeCommandCenterData(actionResult, todoResult, now, TZ)

    expect(result.actionRequiredFailed).toBe(false)
    expect(result.todoFailed).toBe(true)
    expect(result.items).toHaveLength(1)
    expect(result.items[0]!.kind).toBe('SURVEY')
  })

  it('CC-008: 両系とも0件のときisEmpty=true（AC-2）', () => {
    const actionResult = { status: 'fulfilled' as const, value: { items: [], totalCount: 0 } }
    const todoResult: PromiseSettledResult<CommandCenterTodoRawResponse> = {
      status: 'fulfilled',
      value: { items: [], overdue_count: 0, total_incomplete: 0 },
    }

    const result = mergeCommandCenterData(actionResult, todoResult, now, TZ)

    expect(result.isEmpty).toBe(true)
    expect(result.counts.total).toBe(0)
  })

  it('CC-009: TODOは期限切れのもののみ合流し、今日/今後のTODOは含まれない', () => {
    const actionResult = { status: 'fulfilled' as const, value: { items: [], totalCount: 0 } }
    const todoResult: PromiseSettledResult<CommandCenterTodoRawResponse> = {
      status: 'fulfilled',
      value: {
        items: [
          { id: 1, title: '期限切れ', status: 'OPEN', priority: 'HIGH', due_date: '2026-07-10' },
          { id: 2, title: '今日締切', status: 'OPEN', priority: 'HIGH', due_date: '2026-07-14' },
          { id: 3, title: '明日締切', status: 'OPEN', priority: 'HIGH', due_date: '2026-07-15' },
          { id: 4, title: '期限なし', status: 'OPEN', priority: 'LOW', due_date: null },
        ],
        overdue_count: 1,
        total_incomplete: 4,
      },
    }

    const result = mergeCommandCenterData(actionResult, todoResult, now, TZ)

    expect(result.items).toHaveLength(1)
    expect(result.items[0]!.title).toBe('期限切れ')
    expect(result.counts.overdueTodo).toBe(1)
  })

  it('CC-010: チップ件数(counts)はitemsから導出され常に一致する', () => {
    const items: CommandCenterItem[] = [
      { key: 'a', kind: 'CIRCULATION', title: '', scopeName: null, deadline: null, deadlineLabel: 'none' },
      { key: 'b', kind: 'CIRCULATION', title: '', scopeName: null, deadline: null, deadlineLabel: 'none' },
      { key: 'c', kind: 'TODO', title: '', scopeName: null, deadline: null, deadlineLabel: 'overdue' },
    ]
    const counts = computeCommandCenterCounts(items)
    expect(counts).toEqual({ circulation: 2, survey: 0, attendance: 0, overdueTodo: 1, total: 3 })
  })
})

describe('モーダル変換関数', () => {
  it('CC-011: toCirculationActionItemは実データのcirculatedAtをそのまま渡す', () => {
    const item = actionItem({
      itemType: 'CIRCULATION',
      itemId: '42',
      title: '回覧',
      circulatedAt: '2026-07-01T09:00:00',
      deadline: '2026-07-20T00:00:00',
    })
    const result = toCirculationActionItem(item)
    expect(result).toEqual({ id: '42', title: '回覧', circulatedAt: '2026-07-01T09:00:00', deadline: '2026-07-20T00:00:00' })
  })

  it('CC-011b: toCirculationActionItemはcirculatedAtがnullのとき空文字にフォールバックする', () => {
    const item = actionItem({ itemType: 'CIRCULATION', itemId: '42', title: '回覧', circulatedAt: null, deadline: '2026-07-20T00:00:00' })
    const result = toCirculationActionItem(item)
    expect(result).toEqual({ id: '42', title: '回覧', circulatedAt: '', deadline: '2026-07-20T00:00:00' })
  })

  it('CC-012: toSurveyActionItemはidを数値化する', () => {
    const item = actionItem({ itemType: 'SURVEY', itemId: '7', title: 'アンケート', deadline: null })
    const result = toSurveyActionItem(item)
    expect(result).toEqual({ id: 7, title: 'アンケート', deadline: null })
  })

  it('CC-013: toAttendanceActionItemはscheduleIdを数値化しstartsAtのnullを空文字にする', () => {
    const item = actionItem({ itemType: 'ATTENDANCE', itemId: '9', title: '出欠', startsAt: null })
    const result = toAttendanceActionItem(item)
    expect(result).toEqual({ scheduleId: 9, eventTitle: '出欠', startsAt: '' })
  })
})
