import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import CalendarGrid from '~/components/schedule/CalendarGrid.vue'

mockNuxtImport('useDatetime', () => () => ({ userTimezone: { value: 'Asia/Tokyo' } }))
mockNuxtImport('useHolidays', () => () => ({ getHoliday: () => null }))

const AudienceStub = defineComponent({
  props: {
    compact: Boolean,
    targetMode: String,
    targetCount: Number,
    targets: Array,
  },
  template: '<span data-testid="audience" :data-compact="compact" :data-count="targetCount" />',
})

describe('CalendarGrid', () => {
  it('複数日barにもcompact対象者表示を描画する', async () => {
    const wrapper = await mountSuspended(CalendarGrid, {
      props: {
        year: 2026,
        month: 7,
        events: [{
          id: 10,
          uniqueKey: '10',
          title: '家族旅行',
          startAt: '2026-07-06T00:00:00+09:00',
          endAt: '2026-07-08T23:59:59+09:00',
          allDay: true,
          color: '#2563EB',
          isPersonal: false,
          scopeType: 'TEAM',
          scopeName: '家族',
          scopeIconUrl: null,
          targetMode: 'SELECTED_MEMBERS',
          targetCount: 2,
          targets: [
            { userId: 1, displayName: '父', avatarUrl: null, calendarColor: '#2563EB' },
            { userId: 2, displayName: '母', avatarUrl: null, calendarColor: '#DC2626' },
          ],
        }],
      },
      global: {
        stubs: {
          Button: true,
          ScheduleTargetAudience: AudienceStub,
        },
      },
    })

    const audience = wrapper.get('[data-testid="audience"]')
    expect(audience.attributes('data-compact')).toBe('true')
    expect(audience.attributes('data-count')).toBe('2')
  })
})
