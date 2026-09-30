import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Select from 'primevue/select'
import DailyRollCallSheet from '~/components/school/DailyRollCallSheet.vue'

/**
 * CMP-260930-1532: 欠席理由の選択肢が BE の AbsenceReason 8値と一致すること。
 */
const BE_ABSENCE_REASONS = [
  'SICK',
  'INJURY',
  'FAMILY_REASON',
  'BEREAVEMENT',
  'INFECTIOUS_DISEASE',
  'MENTAL_HEALTH',
  'OFFICIAL_BUSINESS',
  'OTHER',
]

describe('DailyRollCallSheet 欠席理由の選択肢', () => {
  it('ABSENT 行の理由 Select が BE の8値を持ち、ラベルは文字列で解決される', async () => {
    const wrapper = await mountSuspended(DailyRollCallSheet, {
      props: {
        date: '2026-09-30',
        entries: [{ studentUserId: 1, displayName: '1', status: 'ABSENT' }],
      },
    })
    const select = wrapper.findComponent(Select)
    expect(select.exists()).toBe(true)
    const options = select.props('options') as Array<{ value: string; label: unknown }>
    expect(options.map((o) => o.value).sort()).toEqual([...BE_ABSENCE_REASONS].sort())
    for (const o of options) {
      expect(typeof o.label).toBe('string')
    }
  })
})
