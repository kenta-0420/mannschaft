import { describe, expect, it } from 'vitest'
import { getRecruitmentListingValidationKey } from './recruitmentListingValidation'

const valid = {
  startAt: '2026-09-02T10:00',
  endAt: '2026-09-02T12:00',
  applicationDeadline: '2026-09-02T09:00',
  autoCancelAt: '2026-09-02T08:00',
  capacity: 5,
  minCapacity: 1,
}

describe('getRecruitmentListingValidationKey', () => {
  it('有効な日時と定員を許可する', () => {
    expect(getRecruitmentListingValidationKey(valid)).toBeNull()
  })

  it.each([
    [{ ...valid, endAt: valid.startAt }, 'recruitment.validation.eventTimeRange'],
    [{ ...valid, applicationDeadline: valid.startAt }, 'recruitment.validation.applicationDeadline'],
    [{ ...valid, autoCancelAt: '2026-09-02T09:01' }, 'recruitment.validation.autoCancelAt'],
    [{ ...valid, minCapacity: 6 }, 'recruitment.validation.capacity'],
  ] as const)('不正値を送信前に拒否する', (value, expected) => {
    expect(getRecruitmentListingValidationKey(value)).toBe(expected)
  })

  it('自動キャンセル時刻と応募締切が同時刻の境界を許可する', () => {
    expect(getRecruitmentListingValidationKey({
      ...valid,
      autoCancelAt: valid.applicationDeadline,
    })).toBeNull()
  })
})
