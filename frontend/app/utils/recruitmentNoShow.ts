import type { RecruitmentNoShowRecordResponse } from '~/types/recruitment'

export function canDisputeNoShow(record: RecruitmentNoShowRecordResponse, now = Date.now()): boolean {
  if (record.disputed || !record.disputeDeadlineAt) return false

  const deadline = Date.parse(record.disputeDeadlineAt)
  return Number.isFinite(deadline) && now < deadline
}

export function isNoShowDisputeExpired(record: RecruitmentNoShowRecordResponse, now = Date.now()): boolean {
  if (record.disputed || !record.disputeDeadlineAt) return false

  const deadline = Date.parse(record.disputeDeadlineAt)
  return Number.isFinite(deadline) && now >= deadline
}
