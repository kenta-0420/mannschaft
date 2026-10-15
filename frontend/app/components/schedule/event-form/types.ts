// ScheduleEventForm の子コンポーネント間で共有するフォーム状態の型定義
// ロジック・振る舞いは本体 ScheduleEventForm.vue に集約し、子は presentation のみ担う

export type RecurrenceType = 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY'
export type RecurrenceEndType = 'DATE' | 'COUNT' | 'NEVER'

// リマインダー入力の種別
//  - RELATIVE: 予定開始の N 分前（remindBeforeMinutes）
//  - ABSOLUTE: 絶対日時に通知（remindAt）
export type ReminderKind = 'RELATIVE' | 'ABSOLUTE'

// 相対リマインダーの単位（UI 上の入力補助。送信時は分に正規化する）
export type RelativeReminderUnit = 'MINUTES' | 'HOURS' | 'DAYS'

// リマインダー入力 1 行ぶんの状態。
export interface ReminderFormEntry {
  // 行の安定キー（v-for の :key 用。送信ペイロードには含めない）
  key: string
  kind: ReminderKind
  // 相対指定: 値と単位（kind === 'RELATIVE' のとき有効）
  relativeValue: number
  relativeUnit: RelativeReminderUnit
  // 絶対指定: 日時（kind === 'ABSOLUTE' のとき有効）
  absoluteAt: Date | null
}

// 予約アンケートの設問選択肢
export interface ScheduledSurveyOptionDraft {
  key: string
  optionText: string
}

// 予約アンケートの設問
export interface ScheduledSurveyQuestionDraft {
  key: string
  questionText: string
  // 最小ビルダーでは単一選択 / 複数選択のみ対応する
  questionType: 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE'
  isRequired: boolean
  options: ScheduledSurveyOptionDraft[]
}

// 予約アンケート作成の入力状態
export interface ScheduledSurveyDraft {
  enabled: boolean
  scheduledAt: Date | null
  title: string
  isAnonymous: boolean
  // 結果公開範囲（CreateSurveyRequest.resultsVisibility）
  resultsVisibility: 'PUBLIC' | 'OWNER_ONLY' | 'RESPONDENTS'
  questions: ScheduledSurveyQuestionDraft[]
}

// 予約出欠募集作成の入力状態
export interface ScheduledAttendanceDraft {
  enabled: boolean
  scheduledAt: Date | null
  attendanceDeadline: Date | null
  // コメント可否（CreateScheduleRequest 系の commentOption と同値）
  commentOption: 'NONE' | 'OPTIONAL' | 'REQUIRED'
  minResponseRole: string
}

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
  // F03.1 (B) 組織出欠のチーム別内訳を有効にするか（組織スコープのみ・既定 false）
  teamBreakdownEnabled: boolean
  // 機能55: リマインダー（全スコープ共通。最大5件）
  reminders: ReminderFormEntry[]
  // 機能55: 予約アンケート作成（team/org のみ）
  scheduledSurvey: ScheduledSurveyDraft
  // 機能55: 予約出欠募集作成（team/org のみ）
  scheduledAttendance: ScheduledAttendanceDraft
}

export interface TimeHistoryEntry {
  startTime: string
  endTime: string
}

export interface TimeOption {
  label: string
  value: string
}
