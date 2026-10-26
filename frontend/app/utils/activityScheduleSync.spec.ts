import { describe, expect, it } from 'vitest'
import { activitySyncConfirmation } from './activityScheduleSync'
import type { ActivitySyncPreview } from '~/types/activityScheduleSync'

const preview: ActivitySyncPreview = {
  expectedScheduleState: {
    updatedAt: '2026-10-07T10:00:00',
    title: '予定',
    startAt: '2026-10-07T10:00:00+09:00',
    endAt: '2026-10-08T12:00:00+09:00',
    allDay: false,
    status: 'PUBLISHED',
    schedules: [
      {
        id: 10,
        updatedAt: '2026-10-07T10:00:00',
        title: '予定',
        startAt: '2026-10-07T10:00:00+09:00',
        endAt: '2026-10-08T12:00:00+09:00',
        allDay: false,
        status: 'PUBLISHED',
      },
    ],
  },
  activities: [
    {
      id: 3,
      version: 7,
      status: 'DRAFT',
      changes: [
        { field: 'title', currentValue: '旧', scheduleValue: '新', automatic: true },
        {
          field: 'activityDate',
          currentValue: '2026-10-06',
          scheduleValue: '2026-10-07',
          automatic: false,
        },
        {
          field: 'activityEndDate',
          currentValue: null,
          scheduleValue: '2026-10-08',
          automatic: false,
        },
      ],
    },
  ],
}
describe('予定と活動記録の同期選択', () => {
  it('自動対象と選択した差分だけをversion付きで送る', () => {
    expect(activitySyncConfirmation(preview, { 3: ['activityEndDate'] }).activities).toEqual([
      { id: 3, version: 7, applyFields: ['title', 'activityEndDate'] },
    ])
  })
  it('予定のみ保存は自動対象を含む全項目を変更しない', () => {
    expect(activitySyncConfirmation(preview, { 3: ['activityDate'] }, true).activities).toEqual([
      { id: 3, version: 7, applyFields: [] },
    ])
  })
  it('未選択の手動差分は送らず最新stateをそのまま返す', () => {
    const confirmation = activitySyncConfirmation(preview, {})
    expect(confirmation.expectedScheduleState).toBe(preview.expectedScheduleState)
    expect(confirmation.activities[0]?.applyFields).toEqual(['title'])
  })
})
