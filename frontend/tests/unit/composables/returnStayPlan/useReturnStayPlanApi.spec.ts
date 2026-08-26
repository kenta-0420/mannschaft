import { describe, expect, it } from 'vitest'
import { getOwnReturnStayPlanStatus, todayInTimeZone } from '~/composables/returnStayPlan/useReturnStayPlanApi'

describe('return stay plan timezone status', () => {
  const boundary = new Date('2026-08-18T06:30:00.000Z')

  it('同じ時刻でもロサンゼルスでは前日として判定する', () => {
    expect(todayInTimeZone('America/Los_Angeles', boundary)).toBe('2026-08-17')
    expect(getOwnReturnStayPlanStatus({ timezone: 'America/Los_Angeles', startDate: '2026-08-18', endDate: '2026-08-20' }, todayInTimeZone('America/Los_Angeles', boundary))).toBe('UPCOMING')
  })

  it('日本時間では開始日をACTIVEとして判定する', () => {
    expect(todayInTimeZone('Asia/Tokyo', boundary)).toBe('2026-08-18')
    expect(getOwnReturnStayPlanStatus({ timezone: 'Asia/Tokyo', startDate: '2026-08-18', endDate: '2026-08-20' }, todayInTimeZone('Asia/Tokyo', boundary))).toBe('ACTIVE')
  })
})
