import { defineComponent, h } from 'vue'
import { beforeAll, describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import DashboardSwipeWidgetGrid from '~/components/dashboard/DashboardSwipeWidgetGrid.vue'
import DashboardScopeListWidget from '~/components/dashboard/DashboardScopeListWidget.vue'
import type { TeamDashboardResponse, OrgDashboardResponse } from '~/types/dashboard-scope'

mockNuxtImport('useDatetime', () => () => ({
  formatDate: (value: string) => value,
  formatDateTime: (value: string) => value,
}))

// 共通mount helperの初回準備を、予定導線の描画検証から分離する。
beforeAll(async () => {
  const wrapper = await mountSuspended(defineComponent({ render: () => h('div') }))
  wrapper.unmount()
})

const Card = defineComponent({
  setup(_, { slots }) {
    return () => h('section', slots.default?.())
  },
})

describe('スコープの今後の予定から実予定一覧への導線', () => {
  for (const scopeType of ['TEAM', 'ORGANIZATION'] as const) {
    it(`${scopeType}の予定データを予定一覧へつなぐ`, async () => {
      const event = { id: 71, title: '予定導線の検証', start_at: '2026-10-18T09:00:00+09:00', all_day: false }
      const data = (scopeType === 'TEAM'
        ? { teamUpcomingEvents: [event] }
        : { orgUpcomingEvents: [event] }) as unknown as TeamDashboardResponse | OrgDashboardResponse
      const wrapper = await mountSuspended(DashboardSwipeWidgetGrid, {
        props: { scopeType, scopeId: 'owned-scope', data },
        global: {
          stubs: {
            DashboardWidgetCard: Card,
            DashboardScopeListWidget: false,
            TimelineFeed: true,
            ScopeActionRequiredWidget: true,
          },
        },
      })
      const upcoming = wrapper.findAllComponents(DashboardScopeListWidget)[0]!
      expect(upcoming.text()).toContain('予定導線の検証')
      expect(upcoming.get('a').attributes('href')).toBe(
        scopeType === 'TEAM' ? '/teams/owned-scope/schedule' : '/organizations/owned-scope/schedule',
      )
    })
  }
})
