// ScheduleEventForm の子コンポーネント間で共有するフォーム状態の型定義
// ロジック・振る舞いは本体 ScheduleEventForm.vue に集約し、子は presentation のみ担う

export type RecurrenceType = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'
export type RecurrenceEndType = 'DATE' | 'COUNT' | 'NEVER'

export interface ScheduleEventFormState {
  title: string
  description: string
  location: string
  startDate: Date | null
  startTime: string
  endDate: Date | null
  endTime: string
  allDay: boolean
  color: string
  attendanceRequired: boolean
  recurrence: boolean
  recurrenceType: RecurrenceType
  recurrenceInterval: number
  recurrenceDaysOfWeek: string[]
  recurrenceEndType: RecurrenceEndType
  recurrenceEndDate: Date | null
  recurrenceCount: number
  allowProxyAttendance: boolean   // F03.10 代理出席を許可するか
  isProxyAutoAccept: boolean      // F03.10 代理委任を自動承認するか
}

export interface TimeHistoryEntry {
  startTime: string
  endTime: string
}

export interface TimeOption {
  label: string
  value: string
}
