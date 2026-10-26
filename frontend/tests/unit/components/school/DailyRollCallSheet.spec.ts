import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import Select from 'primevue/select'
import DailyRollCallSheet from '~/components/school/DailyRollCallSheet.vue'
import PeriodAttendanceSheet from '~/components/school/PeriodAttendanceSheet.vue'
import FamilyAbsenceNoticeForm from '~/components/school/FamilyAbsenceNoticeForm.vue'
import { readBeAbsenceReasons } from '../../helpers/beAbsenceReasons'

/**
 * CMP-260930-1532: 欠席理由の選択肢が BE の AbsenceReason（正本を直接読む）と一致すること。
 * 日次点呼・時限点呼・保護者連絡フォームの3画面すべてを検証する。
 */
const BE_ABSENCE_REASONS = readBeAbsenceReasons()

function expectBeReasons(options: Array<{ value: string; label: unknown }>): void {
  expect(BE_ABSENCE_REASONS.length).toBeGreaterThan(0)
  expect(options.map((o) => o.value).sort()).toEqual([...BE_ABSENCE_REASONS].sort())
  for (const o of options) {
    expect(typeof o.label).toBe('string')
  }
}

describe('欠席理由の選択肢', () => {
  it('DailyRollCallSheet: ABSENT 行の理由 Select が BE の値を持つ', async () => {
    const wrapper = await mountSuspended(DailyRollCallSheet, {
      props: {
        date: '2026-09-30',
        entries: [{ studentUserId: 1, displayName: '1', status: 'ABSENT' }],
      },
    })
    const select = wrapper.findComponent(Select)
    expect(select.exists()).toBe(true)
    expectBeReasons(select.props('options') as Array<{ value: string; label: unknown }>)
  })

  it('PeriodAttendanceSheet: 欠席理由の選択 UI が無い（時限点呼に absence_reason 列は無い）', async () => {
    const wrapper = await mountSuspended(PeriodAttendanceSheet, {
      props: {
        periodNumber: 1,
        date: '2026-09-30',
        candidates: [],
        entries: [
          { studentUserId: 1, displayName: '1', status: 'ABSENT', dailyStatus: 'ATTENDING' },
          { studentUserId: 2, displayName: '2', status: 'PARTIAL', dailyStatus: 'ATTENDING' },
        ],
      },
    })
    expect(wrapper.find('[data-testid="period-row-1-absent"]').exists()).toBe(true)
    expect(wrapper.findAllComponents(Select)).toHaveLength(0)
  })

  it('PeriodAttendanceSheet: 状態変更で emit される entries に absenceReason が含まれない', async () => {
    const wrapper = await mountSuspended(PeriodAttendanceSheet, {
      props: {
        periodNumber: 1,
        date: '2026-09-30',
        candidates: [],
        entries: [
          { studentUserId: 1, displayName: '1', status: 'UNDECIDED', dailyStatus: 'ATTENDING' },
        ],
      },
    })
    await wrapper.find('[data-testid="period-row-1-absent"]').trigger('click')
    const emitted = wrapper.emitted('change')
    expect(emitted).toBeTruthy()
    const entries = emitted![0]![0] as Array<Record<string, unknown>>
    expect(entries[0]!.status).toBe('ABSENT')
    expect('absenceReason' in entries[0]!).toBe(false)
  })

  it('FamilyAbsenceNoticeForm: 提出中は提出ボタンが disabled になる', async () => {
    const wrapper = await mountSuspended(FamilyAbsenceNoticeForm, {
      props: { teamId: 't1', studentUserId: 1, submitting: true },
    })
    const button = wrapper.find('[data-testid="family-notice-submit"]')
    expect(button.exists()).toBe(true)
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('FamilyAbsenceNoticeForm: 理由 Select が BE の値を持つ', async () => {
    const wrapper = await mountSuspended(FamilyAbsenceNoticeForm, {
      props: { teamId: 't1', studentUserId: 1 },
    })
    const select = wrapper
      .findAllComponents(Select)
      .find((c) => (c.props('options') as Array<{ value: string }>).some((o) => o.value === 'SICK'))
    expect(select).toBeDefined()
    expectBeReasons(select!.props('options') as Array<{ value: string; label: unknown }>)
  })
})
