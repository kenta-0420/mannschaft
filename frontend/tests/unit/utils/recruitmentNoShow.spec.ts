import { describe, expect, it } from 'vitest'
import type { RecruitmentNoShowRecordResponse } from '~/types/recruitment'
import { canDisputeNoShow, isNoShowDisputeExpired } from '~/utils/recruitmentNoShow'

const record = (overrides: Partial<RecruitmentNoShowRecordResponse> = {}): RecruitmentNoShowRecordResponse => ({
  id: 1,
  participantId: 2,
  listingId: 3,
  userId: 4,
  reason: 'ADMIN_MARKED',
  confirmed: false,
  recordedAt: '2026-09-01T10:00:00Z',
  disputeDeadlineAt: '2026-10-01T10:00:00Z',
  recordedBy: 5,
  disputed: false,
  disputeResolution: null,
  createdAt: '2026-09-01T10:00:00Z',
  ...overrides,
})

describe('recruitment no-show dispute eligibility', () => {
  const now = Date.parse('2026-09-15T10:00:00Z')

  it('allows a temporary mark to be disputed before its server-provided deadline', () => {
    const pendingRecord = record()

    expect(pendingRecord.confirmed).toBe(false)
    expect(canDisputeNoShow(pendingRecord, now)).toBe(true)
    expect(isNoShowDisputeExpired(pendingRecord, now)).toBe(false)
  })

  it('continues to allow a confirmed record before its deadline', () => {
    expect(canDisputeNoShow(record({ confirmed: true }), now)).toBe(true)
  })

  it('hides the dispute action at and after the deadline', () => {
    const expiredRecord = record({ disputeDeadlineAt: '2026-09-15T10:00:00Z' })

    expect(canDisputeNoShow(expiredRecord, now)).toBe(false)
    expect(isNoShowDisputeExpired(expiredRecord, now)).toBe(true)
  })

  it('does not prompt an already disputed record', () => {
    const disputedRecord = record({ disputed: true })

    expect(canDisputeNoShow(disputedRecord, now)).toBe(false)
    expect(isNoShowDisputeExpired(disputedRecord, now)).toBe(false)
  })

  it('fails closed when the server deadline is absent or invalid', () => {
    expect(canDisputeNoShow(record({ disputeDeadlineAt: null }), now)).toBe(false)
    expect(canDisputeNoShow(record({ disputeDeadlineAt: 'invalid' }), now)).toBe(false)
    expect(isNoShowDisputeExpired(record({ disputeDeadlineAt: 'invalid' }), now)).toBe(false)
  })
})
