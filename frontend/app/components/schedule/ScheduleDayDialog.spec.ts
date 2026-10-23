import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import ScheduleDayDialog from './ScheduleDayDialog.vue'
import type { CalendarEventItem } from '~/composables/useCalendarEvents'

const DialogStub = defineComponent({
  props: ['visible'],
  setup(props, { slots }) {
    return () => props.visible ? h('div', [slots.default?.(), slots.footer?.()]) : null
  },
})
const RowStub = defineComponent({
  props: ['event'], emits: ['open'],
  setup(props, { emit }) {
    return () => h('button', { onClick: () => emit('open') }, props.event.title)
  },
})
const event = (id: number, startAt: string, endAt: string): CalendarEventItem => ({
  id, uniqueKey: String(id), title: `予定${id}`, startAt, endAt,
  allDay: false, isPersonal: false, color: null, attendanceRequired: true,
})

describe('ScheduleDayDialog', () => {
  it('複数日と月境界を含め当日を占有する行だけ表示し、行内出欠を無効にして元の行を通知する', async () => {
    const events = [
      event(1, '2026-07-31T22:00:00', '2026-08-02T00:00:00'),
      event(2, '2026-07-31T22:00:00', '2026-08-01T00:00:00'),
      event(3, '2026-08-01T12:00:00', '2026-08-01T13:00:00'),
    ]
    const wrapper = await mountSuspended(ScheduleDayDialog, {
      props: { visible: true, date: '2026-08-01', events, canCreate: true },
      global: { stubs: { Dialog: DialogStub, ScheduleListRow: RowStub } },
    })

    const rows = wrapper.findAllComponents(RowStub)
    expect(rows.map(row => row.text())).toEqual(['予定1', '予定3'])
    expect(rows[0]!.props('event').attendanceRequired).toBe(false)
    await rows[0]!.trigger('click')
    expect(wrapper.emitted('rowOpen')?.[0]).toEqual([events[0]])
    await wrapper.get('[data-testid="schedule-day-create"]').trigger('click')
    expect(wrapper.emitted('create')).toHaveLength(1)
  })

  it('権限なし0件では空状態を表示し、新規作成ボタンを表示しない', async () => {
    const wrapper = await mountSuspended(ScheduleDayDialog, {
      props: { visible: true, date: '2026-08-01', events: [], canCreate: false },
      global: { stubs: { Dialog: DialogStub } },
    })

    expect(wrapper.text()).toContain('この日の予定はありません')
    expect(wrapper.find('[data-testid="schedule-day-create"]').exists()).toBe(false)
  })
})
