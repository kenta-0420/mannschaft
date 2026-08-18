import type { MyCalendarTodo } from '~/types/todo'
import {
  shouldDisplayMyCalendarTodo,
  shouldDisplayScheduleForCurrentUser,
  shouldNotifyTodoLoadFailure,
} from '~/composables/useMyCalendarData'

const baseTodo: MyCalendarTodo = {
  id: 10,
  title: '担当TODO',
  startDate: null,
  dueDate: '2026-08-31',
  dueTime: null,
  status: 'OPEN',
  priority: 'MEDIUM',
  linkedScheduleId: null,
  scope: { scopeType: 'TEAM', scopeId: 'family', scopeName: '家族', scopeIconUrl: null },
}

describe('useMyCalendarData の自己担当TODO契約', () => {
  it('TEAM/ORGANIZATION を含む期限のみの未完了TODOを表示する', () => {
    expect(shouldDisplayMyCalendarTodo(baseTodo, new Set())).toBe(true)
    expect(shouldDisplayMyCalendarTodo({
      ...baseTodo,
      scope: { ...baseTodo.scope, scopeType: 'ORGANIZATION' },
    }, new Set())).toBe(true)
  })

  it('完了済みTODOと可視予定に連携済みのTODOを表示しない', () => {
    expect(shouldDisplayMyCalendarTodo({ ...baseTodo, status: 'COMPLETED' }, new Set())).toBe(false)
    expect(shouldDisplayMyCalendarTodo({ ...baseTodo, linkedScheduleId: 99 }, new Set([99]))).toBe(false)
  })

  it('選択対象予定は本人のみを防御的に表示し、ユーザー未初期化時はBackendの結果を尊重する', () => {
    const targets = [{ userId: 1, displayName: '母', avatarUrl: null, calendarColor: '#2563EB' }]
    expect(shouldDisplayScheduleForCurrentUser('ALL_MEMBERS', targets, 2)).toBe(true)
    expect(shouldDisplayScheduleForCurrentUser('SELECTED_MEMBERS', targets, 1)).toBe(true)
    expect(shouldDisplayScheduleForCurrentUser('SELECTED_MEMBERS', targets, 2)).toBe(false)
    expect(shouldDisplayScheduleForCurrentUser('SELECTED_MEMBERS', targets, null)).toBe(true)
    // 外部閲覧時のマスク、または旧APIでtargetsが来ない場合は、正本であるBackendの可視性結果を消さない。
    expect(shouldDisplayScheduleForCurrentUser('SELECTED_MEMBERS', undefined, 2)).toBe(true)
    expect(shouldDisplayScheduleForCurrentUser('SELECTED_MEMBERS', [], 2)).toBe(true)
  })

  it('TODOのみの部分失敗では、共通エラートーストと重複しない', () => {
    expect(shouldNotifyTodoLoadFailure(500)).toBe(false)
    expect(shouldNotifyTodoLoadFailure(429)).toBe(false)
    expect(shouldNotifyTodoLoadFailure(403)).toBe(true)
    expect(shouldNotifyTodoLoadFailure(undefined)).toBe(true)
  })
})
