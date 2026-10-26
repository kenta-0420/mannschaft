export type ActivitySyncField =
  | 'title'
  | 'activityDate'
  | 'activityEndDate'
  | 'activityTimeStart'
  | 'activityTimeEnd'
export interface ActivitySyncPreview {
  expectedScheduleState: {
    updatedAt: string
    title: string
    startAt: string
    endAt: string | null
    allDay: boolean
    status: string
    schedules: Array<{
      id: number
      updatedAt: string
      title: string
      startAt: string
      endAt: string | null
      allDay: boolean
      status: string
    }>
  }
  activities: Array<{
    id: number
    version: number
    status: string
    changes: Array<{
      field: ActivitySyncField
      currentValue: string | null
      scheduleValue: string | null
      automatic: boolean
    }>
  }>
}
export interface ActivitySyncConfirmation {
  expectedScheduleState: ActivitySyncPreview['expectedScheduleState']
  activities: Array<{ id: number; version: number; applyFields: ActivitySyncField[] }>
}
