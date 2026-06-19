import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ScheduleEventReminderInput from '~/components/schedule/event-form/ScheduleEventReminderInput.vue'
import type { ScheduleEventFormState } from '~/components/schedule/event-form/types'

/**
 * 機能55 ScheduleEventReminderInput.vue ユニットテスト
 *
 * 観点:
 *   REM-001: 初期状態ではリマインダー0件・追加ボタンが表示される
 *   REM-002: 追加ボタンでリマインダーが1件増える
 *   REM-003: 最大5件に達すると追加ボタンが消え上限メッセージが出る
 */
function baseForm(reminders: ScheduleEventFormState['reminders'] = []): ScheduleEventFormState {
  return {
    title: '',
    description: '',
    location: '',
    startDate: null,
    startTime: '',
    endDate: null,
    endTime: '',
    allDay: false,
    color: '#22c55e',
    attendanceRequired: false,
    recurrence: false,
    recurrenceType: 'WEEKLY',
    recurrenceInterval: 1,
    recurrenceDaysOfWeek: [],
    recurrenceEndType: 'NEVER',
    recurrenceEndDate: null,
    recurrenceCount: 10,
    allowProxyAttendance: false,
    isProxyAutoAccept: false,
    teamBreakdownEnabled: false,
    reminders,
    scheduledSurvey: {
      enabled: false,
      scheduledAt: null,
      title: '',
      isAnonymous: false,
      resultsVisibility: 'PUBLIC',
      questions: [],
    },
    scheduledAttendance: {
      enabled: false,
      scheduledAt: null,
      attendanceDeadline: null,
      commentOption: 'OPTIONAL',
      minResponseRole: '',
    },
  }
}

function makeReminder(index: number): ScheduleEventFormState['reminders'][number] {
  return {
    key: `rem-${index}`,
    kind: 'RELATIVE',
    relativeValue: 30,
    relativeUnit: 'MINUTES',
    absoluteAt: null,
  }
}

describe('ScheduleEventReminderInput.vue', () => {
  it('REM-001: 初期状態でリマインダー0件・追加ボタンが描画される', async () => {
    const wrapper = await mountSuspended(ScheduleEventReminderInput, {
      props: { form: baseForm() },
    })
    // 件数カウンタに 0 / 5 が含まれる
    expect(wrapper.text()).toContain('0 / 5')
  })

  it('REM-002: 追加ボタンのクリックでリマインダーが1件増える', async () => {
    const form = baseForm()
    const wrapper = await mountSuspended(ScheduleEventReminderInput, {
      props: { form },
    })
    // 追加ボタン（pi-plus アイコン付き）を探してクリックする
    const addBtn = wrapper.findAll('button').find(b => b.html().includes('pi-plus'))
    expect(addBtn).toBeTruthy()
    await addBtn!.trigger('click')
    expect(form.reminders.length).toBe(1)
    expect(form.reminders[0]?.kind).toBe('RELATIVE')
  })

  it('REM-003: 上限5件では追加ボタンが描画されず上限メッセージが出る', async () => {
    const form = baseForm([0, 1, 2, 3, 4].map(makeReminder))
    const wrapper = await mountSuspended(ScheduleEventReminderInput, {
      props: { form },
    })
    const addBtn = wrapper.findAll('button').find(b => b.html().includes('pi-plus'))
    expect(addBtn).toBeFalsy()
    expect(wrapper.text()).toContain('5 / 5')
  })
})
