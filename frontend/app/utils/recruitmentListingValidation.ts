export type RecruitmentListingValidationKey =
  | 'recruitment.validation.eventTimeRange'
  | 'recruitment.validation.applicationDeadline'
  | 'recruitment.validation.autoCancelAt'
  | 'recruitment.validation.capacity'

interface RecruitmentListingSchedule {
  startAt: string
  endAt: string
  applicationDeadline: string
  autoCancelAt: string
  capacity: number
  minCapacity: number
}

export function getRecruitmentListingValidationKey(
  value: RecruitmentListingSchedule,
): RecruitmentListingValidationKey | null {
  const start = new Date(value.startAt).getTime()
  const end = new Date(value.endAt).getTime()
  const deadline = new Date(value.applicationDeadline).getTime()
  const autoCancel = new Date(value.autoCancelAt).getTime()

  if (end <= start) return 'recruitment.validation.eventTimeRange'
  if (deadline >= start) return 'recruitment.validation.applicationDeadline'
  if (autoCancel > deadline) return 'recruitment.validation.autoCancelAt'
  if (value.minCapacity > value.capacity) return 'recruitment.validation.capacity'
  return null
}
