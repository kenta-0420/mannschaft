import { describe, expect, it } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import BroadcastAudienceSummary from './BroadcastAudienceSummary.vue'

/** AC-H17(UI): 送信履歴に「宛先: …（送信時 N チーム）」と「表示は現在の所属で判定されます」の注記が出る */

mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
}))

describe('BroadcastAudienceSummary', () => {
  it('送信時の名前の範囲と送信時チーム数を表示し、動的表示の注記を添える', async () => {
    const w = await mountSuspended(BroadcastAudienceSummary, {
      props: {
        audience: {
          mode: 'GROUPS',
          groups: [{ id: 'g1', name: '平成18年度' }, { id: 'g2', name: '平成20年度' }],
          range: { toGroupId: 'g2', toGroupName: '平成20年度' },
          includeUnassigned: false,
          teamCount: 12,
          directMemberCount: 3,
        },
      },
    })
    const summary = w.get('[data-testid="broadcast-audience-summary"]').text()
    expect(summary).toContain('announcement.audience_summary')
    expect(summary).toContain('announcement.target_range_up_to')
    expect(summary).toContain('平成20年度')
    expect(summary).toContain('"count":12')
    expect(w.get('[data-testid="broadcast-audience-display-note"]').text()).toBe('announcement.dynamic_display_note')
  })

  it('宛先指定の記録が無い（絞り込みなし）なら何も描画しない', async () => {
    const w = await mountSuspended(BroadcastAudienceSummary, { props: { audience: null } })
    expect(w.find('[data-testid="broadcast-audience-summary"]').exists()).toBe(false)
  })
})
