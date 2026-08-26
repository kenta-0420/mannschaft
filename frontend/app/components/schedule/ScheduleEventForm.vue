<script setup lang="ts">
import dayjs from 'dayjs'
import type { RecurrenceEndType, RecurrenceType, ReminderFormEntry, ScheduleEventFormState, TimeHistoryEntry } from './event-form/types'
import type { ScheduleTargetMode } from '~/types/schedule'

interface ScopeOption {
  label: string
  value: string
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
}

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: string
  scheduleId?: number
  initialDate?: string
  visible: boolean
  isPersonal?: boolean
  scopeOptions?: ScopeOption[]
}>()

/** 実際に保存されたスコープ（フォーム内でスコープ変更が可能なため、呼び出し側の props とは食い違いうる）。 */
interface SavedScope {
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: string
}

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: [scope: SavedScope]
}>()

// スコープ選択（フォーム内で変更可能）
const selectedScopeKey = ref<string>(
  (props.isPersonal ?? false) ? 'personal' : `${props.scopeType}_${props.scopeId}`,
)

// ダイアログが開くたびにスコープキーを prop に合わせてリセット
watch(
  () => props.visible,
  (v) => {
    if (v) {
      selectedScopeKey.value = (props.isPersonal ?? false)
        ? 'personal'
        : `${props.scopeType}_${props.scopeId}`
    }
  },
)

// 実効スコープ（フォーム内選択 or props フォールバック）
const effectiveScope = computed(() => {
  if (props.scopeOptions && props.scopeOptions.length > 1) {
    const found = props.scopeOptions.find(o => o.value === selectedScopeKey.value)
    if (found) return found
  }
  return {
    isPersonal: props.isPersonal ?? false,
    scopeType: props.scopeType,
    scopeId: props.scopeId,
  }
})

const scheduleApi = useScheduleApi()
const notification = useNotification()
const { handleApiError, getFieldErrors } = useErrorHandler()
const { userTimezone, buildOffsetDateTimeStr } = useDatetime()
const { t } = useI18n()
const { googleSyncEnabled, fetchPersonalSyncStatus } = useGoogleCalendarApi()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.scheduleId)
const targetMode = ref<ScheduleTargetMode>('ALL_MEMBERS')
const targetUserIds = ref<number[]>([])
const targetValidationError = ref<string | null>(null)

// 15分刻みの時刻オプション生成（00:00〜23:45）
const timeOptions = Array.from({ length: 96 }, (_, i) => {
  const h = Math.floor(i / 4)
  const m = (i % 4) * 15
  const v = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
  return { label: v, value: v }
})

// 入力履歴（localStorage）
const HISTORY_KEY = 'schedule-time-history'

function loadTimeHistory(): TimeHistoryEntry[] {
  if (typeof localStorage === 'undefined') return []
  try {
    return JSON.parse(localStorage.getItem(HISTORY_KEY) ?? '[]') as TimeHistoryEntry[]
  } catch {
    // eslint-disable-next-line no-restricted-syntax -- localStorage の破損履歴に対する防御パース。空配列復帰が正しい（機能劣化なし）
    return []
  }
}

function saveTimeHistory(startTime: string, endTime: string) {
  if (typeof localStorage === 'undefined') return
  const history = loadTimeHistory().filter(
    h => !(h.startTime === startTime && h.endTime === endTime)
  )
  history.unshift({ startTime, endTime })
  localStorage.setItem(HISTORY_KEY, JSON.stringify(history.slice(0, 5)))
  timeHistory.value = loadTimeHistory()
}

const timeHistory = ref<TimeHistoryEntry[]>(loadTimeHistory())

const form = ref<ScheduleEventFormState>({
  title: '',
  description: '',
  location: '',
  startDate: null,
  startTime: '',
  endDate: null,
  endTime: '',
  allDay: false,
  color: '#22c55e',
  attendanceRequired: false,
  recurrence: false,
  recurrenceType: 'WEEKLY',
  recurrenceInterval: 1,
  recurrenceDaysOfWeek: [],
  recurrenceEndType: 'NEVER',
  recurrenceEndDate: null,
  recurrenceCount: 10,
  allowProxyAttendance: false,
  isProxyAutoAccept: false,
  teamBreakdownEnabled: false,
  reminders: [],
  scheduledSurvey: {
    enabled: false,
    scheduledAt: null,
    title: '',
    isAnonymous: false,
    resultsVisibility: 'PUBLIC',
    questions: [],
  },
  scheduledAttendance: {
    enabled: false,
    scheduledAt: null,
    attendanceDeadline: null,
    commentOption: 'OPTIONAL',
    minResponseRole: '',
  },
})

// 開始時刻が変わったら終了時刻を1時間後に自動設定
watch(
  () => form.value.startTime,
  (newTime) => {
    if (!newTime || form.value.allDay) return
    const parts = newTime.split(':').map(Number)
    const h = parts[0] ?? 0
    const m = parts[1] ?? 0
    const endH = h + 1
    if (endH >= 24) {
      form.value.endTime = `${String(endH - 24).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      if (form.value.startDate) {
        const d = new Date(form.value.startDate)
        d.setDate(d.getDate() + 1)
        form.value.endDate = d
      }
    } else {
      form.value.endTime = `${String(endH).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      if (form.value.startDate && !form.value.endDate) {
        form.value.endDate = new Date(form.value.startDate)
      }
    }
  },
)

// 開始日が変わったら終了日を開始日に合わせる（未設定 or 開始日より前の場合）
watch(
  () => form.value.startDate,
  (newDate) => {
    if (!newDate) return
    if (!form.value.endDate || form.value.endDate < newDate) {
      form.value.endDate = new Date(newDate)
    }
  },
)

// ダイアログが開くたびに Google 連携ステータスを取得する
watch(
  () => props.visible,
  (v) => {
    if (v) fetchPersonalSyncStatus()
  },
)

watch(
  () => [props.visible, props.scheduleId],
  async ([visible, scheduleId]) => {
    if (visible && scheduleId) {
      try {
        const res = effectiveScope.value.isPersonal
          ? await scheduleApi.getMyScheduleDetail(scheduleId as number)
          : await scheduleApi.getSchedule(effectiveScope.value.scopeType, effectiveScope.value.scopeId, scheduleId as number)
        const data = (res as { data: Record<string, unknown> }).data as Record<string, unknown>
        if (effectiveScope.value.isPersonal) {
          const content = (data.content as Record<string, unknown>) ?? {}
          const time = (data.time as Record<string, unknown>) ?? {}
          form.value.title = (content.title as string) ?? ''
          form.value.description = (content.description as string) ?? ''
          form.value.location = (content.location as string) ?? ''
          form.value.allDay = (time.allDay as boolean) ?? false
          form.value.attendanceRequired = false
          form.value.allowProxyAttendance = false
          form.value.isProxyAutoAccept = false
          if (time.startAt) {
            const start = new Date(time.startAt as string)
            form.value.startDate = start
            form.value.startTime = start.toTimeString().slice(0, 5)
          }
          if (time.endAt) {
            const end = new Date(time.endAt as string)
            form.value.endDate = end
            form.value.endTime = end.toTimeString().slice(0, 5)
          }
          // 個人予定: detailedReminders からリマインダーフォーム状態を復元する
          const detailedReminders = (data.detailedReminders as Array<Record<string, unknown>> | null) ?? []
          if (detailedReminders.length > 0) {
            form.value.reminders = detailedReminders.map(reminderResponseToFormEntry)
          } else {
            // detailedReminders が未 populate の場合は後方互換の reminders(number[]) から相対リマインダーを復元
            const legacyReminders = (data.reminders as number[] | null) ?? []
            form.value.reminders = legacyReminders.map((minutes) => ({
              key: `rem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
              kind: 'RELATIVE' as const,
              relativeValue: minutes,
              relativeUnit: 'MINUTES' as const,
              absoluteAt: null,
            }))
          }
          // 個人予定: status.recurrenceRule から繰り返し設定をフォームに復元する
          const status = (data.status as Record<string, unknown>) ?? {}
          const recurrenceRule = status.recurrenceRule as Record<string, unknown> | null
          if (recurrenceRule && typeof recurrenceRule === 'object') {
            form.value.recurrence = true
            form.value.recurrenceType = ((recurrenceRule.type as string) ?? 'WEEKLY') as RecurrenceType
            form.value.recurrenceInterval = (recurrenceRule.interval as number) ?? 1
            form.value.recurrenceDaysOfWeek = (recurrenceRule.daysOfWeek as string[]) ?? []
            form.value.recurrenceEndType = ((recurrenceRule.endType as string) ?? 'NEVER') as RecurrenceEndType
            if (recurrenceRule.endDate) {
              form.value.recurrenceEndDate = new Date(recurrenceRule.endDate as string)
            }
            if (recurrenceRule.count != null) {
              form.value.recurrenceCount = recurrenceRule.count as number
            }
          } else {
            form.value.recurrence = false
          }
        }
        else {
          form.value.title = (data.title as string) ?? ''
          form.value.description = (data.description as string) ?? ''
          form.value.location = (data.location as string) ?? ''
          form.value.allDay = (data.allDay as boolean) ?? false
          form.value.attendanceRequired = (data.attendanceRequired as boolean) ?? false
          form.value.allowProxyAttendance = (data.allowProxyAttendance as boolean) ?? false
          form.value.isProxyAutoAccept = (data.isProxyAutoAccept as boolean) ?? false
          form.value.teamBreakdownEnabled = (data.teamBreakdownEnabled as boolean) ?? false
          targetMode.value = (data.targetMode as ScheduleTargetMode) ?? 'ALL_MEMBERS'
          targetUserIds.value = ((data.targets as Array<{ userId: number }> | undefined) ?? [])
            .map(target => target.userId)
          if (data.startAt) {
            const start = new Date(data.startAt as string)
            form.value.startDate = start
            form.value.startTime = start.toTimeString().slice(0, 5)
          }
          if (data.endAt) {
            const end = new Date(data.endAt as string)
            form.value.endDate = end
            form.value.endTime = end.toTimeString().slice(0, 5)
          }
          // 共有予定: reminders からリマインダーフォーム状態を復元する
          const reminders = (data.reminders as Array<Record<string, unknown>> | null) ?? []
          form.value.reminders = reminders.map(reminderResponseToFormEntry)
          // 共有予定: scheduledTasks の PENDING タスクを scheduledSurvey / scheduledAttendance に変換する
          const scheduledTasks = (data.scheduledTasks as Array<Record<string, unknown>> | null) ?? []
          for (const task of scheduledTasks) {
            if (task.status !== 'PENDING') continue
            if (task.taskType === 'SURVEY') {
              form.value.scheduledSurvey = {
                ...form.value.scheduledSurvey,
                enabled: true,
                scheduledAt: task.scheduledAt ? new Date(task.scheduledAt as string) : null,
              }
            } else if (task.taskType === 'ATTENDANCE') {
              form.value.scheduledAttendance = {
                ...form.value.scheduledAttendance,
                enabled: true,
                scheduledAt: task.scheduledAt ? new Date(task.scheduledAt as string) : null,
              }
            }
          }
        }
      } catch {
        notification.error(t('schedule.error_load_event'))
      }
    } else if (visible && !scheduleId) {
      resetForm()
      if (props.initialDate) {
        form.value.startDate = new Date(props.initialDate)
        form.value.endDate = new Date(props.initialDate)
      }
    }
  },
)

// ユーザーのタイムゾーン設定に基づいてDateをYYYY-MM-DD文字列に変換する。
// recurrenceRule.endDate など「日付のみ」フィールドに使用する。
function toLocalDateStr(date: Date): string {
  return dayjs(date).tz(userTimezone.value).format('YYYY-MM-DD')
}

// ReminderResponse（BE）を ReminderFormEntry（フォーム状態）に変換する。
// reminderKind が RELATIVE の場合は remindBeforeMinutes から relativeValue/relativeUnit を逆算する。
function reminderResponseToFormEntry(r: Record<string, unknown>): ReminderFormEntry {
  const key = `rem-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
  if (r.reminderKind === 'ABSOLUTE') {
    return {
      key,
      kind: 'ABSOLUTE',
      relativeValue: 30,
      relativeUnit: 'MINUTES',
      absoluteAt: r.remindAt ? new Date(r.remindAt as string) : null,
    }
  }
  // RELATIVE: remindBeforeMinutes → 値と単位に逆算
  const minutes = (r.remindBeforeMinutes as number) ?? 30
  let relativeValue = minutes
  let relativeUnit: 'MINUTES' | 'HOURS' | 'DAYS' = 'MINUTES'
  if (minutes > 0 && minutes % (60 * 24) === 0) {
    relativeValue = minutes / (60 * 24)
    relativeUnit = 'DAYS'
  } else if (minutes > 0 && minutes % 60 === 0) {
    relativeValue = minutes / 60
    relativeUnit = 'HOURS'
  }
  return {
    key,
    kind: 'RELATIVE',
    relativeValue,
    relativeUnit,
    absoluteAt: null,
  }
}

// 相対リマインダーの値・単位を分に正規化する。
function relativeReminderToMinutes(value: number, unit: 'MINUTES' | 'HOURS' | 'DAYS'): number {
  if (unit === 'HOURS') return value * 60
  if (unit === 'DAYS') return value * 60 * 24
  return value
}

// 共有予定（team/org）向けのリマインダーペイロード（CreateReminderRequest[]）を組み立てる。
// 絶対リマインダーの remindAt は OffsetDateTime 形式（TZオフセット付き）で送信する。
function buildSharedReminders(): Array<Record<string, unknown>> {
  return form.value.reminders.map((r) => {
    if (r.kind === 'ABSOLUTE') {
      return { reminderKind: 'ABSOLUTE', remindAt: buildOffsetDateTimeStr(r.absoluteAt) }
    }
    return {
      reminderKind: 'RELATIVE',
      remindBeforeMinutes: relativeReminderToMinutes(r.relativeValue, r.relativeUnit),
    }
  })
}

// 予約系入力のバリデーション（過去日時拒否・未入力検出）。
// 問題があればエラーメッセージを返し、なければ null を返す。
function validateScheduledInputs(): string | null {
  const now = Date.now()
  // 絶対リマインダーは未来日時必須
  for (const r of form.value.reminders) {
    if (r.kind === 'ABSOLUTE') {
      if (!r.absoluteAt) return t('schedule.reminder.error_absolute_required')
      if (r.absoluteAt.getTime() <= now) return t('schedule.reminder.error_past')
    }
  }
  // 共有スコープのみ予約アンケート・予約出欠を検証
  if (!effectiveScope.value.isPersonal) {
    if (form.value.scheduledSurvey.enabled) {
      if (!form.value.scheduledSurvey.scheduledAt) {
        return t('schedule.scheduled_survey.error_scheduled_at_required')
      }
      if (form.value.scheduledSurvey.scheduledAt.getTime() <= now) {
        return t('schedule.scheduled_survey.error_past')
      }
    }
    if (form.value.scheduledAttendance.enabled) {
      if (!form.value.scheduledAttendance.scheduledAt) {
        return t('schedule.scheduled_attendance.error_scheduled_at_required')
      }
      if (form.value.scheduledAttendance.scheduledAt.getTime() <= now) {
        return t('schedule.scheduled_attendance.error_past')
      }
    }
  }
  return null
}

async function submit() {
  if (!form.value.title.trim()) {
    fieldErrors.value = { title: t('schedule.error_title_required') }
    return
  }
  if (!effectiveScope.value.isPersonal && targetValidationError.value) {
    notification.error(targetValidationError.value)
    return
  }
  const scheduledError = validateScheduledInputs()
  if (scheduledError) {
    notification.error(scheduledError)
    return
  }
  submitting.value = true
  fieldErrors.value = {}

  const body: Record<string, unknown> = {
    title: form.value.title.trim(),
    description: form.value.description.trim() || undefined,
    location: form.value.location.trim() || undefined,
    allDay: form.value.allDay,
    startAt: buildOffsetDateTimeStr(form.value.startDate, form.value.allDay ? '' : form.value.startTime) ?? undefined,
    endAt: (() => {
      if (form.value.allDay && form.value.endDate) {
        const d = new Date(form.value.endDate)
        d.setDate(d.getDate() + 1)
        return buildOffsetDateTimeStr(d, '') ?? undefined
      }
      return buildOffsetDateTimeStr(form.value.endDate, form.value.allDay ? '' : form.value.endTime) ?? undefined
    })(),
  }
  if (effectiveScope.value.isPersonal) {
    body.color = form.value.color
  } else {
    body.eventType = 'OTHER'
    body.attendanceRequired = form.value.attendanceRequired
    body.allow_proxy_attendance = form.value.allowProxyAttendance
    body.is_proxy_auto_accept = form.value.allowProxyAttendance ? form.value.isProxyAutoAccept : false
    body.targetMode = targetMode.value
    body.targetUserIds = targetMode.value === 'SELECTED_MEMBERS' ? targetUserIds.value : []
    // F03.1 (B) チーム別内訳トグルは組織スコープ + 出欠ありのときのみ送る
    if (effectiveScope.value.scopeType === 'organization' && form.value.attendanceRequired) {
      body.teamBreakdownEnabled = form.value.teamBreakdownEnabled
    }
  }

  if (form.value.recurrence) {
    body.recurrenceRule = {
      type: form.value.recurrenceType,
      interval: form.value.recurrenceInterval,
      daysOfWeek: form.value.recurrenceType === 'WEEKLY'
        ? form.value.recurrenceDaysOfWeek
        : undefined,
      endType: form.value.recurrenceEndType,
      endDate: form.value.recurrenceEndType === 'DATE' && form.value.recurrenceEndDate
        ? toLocalDateStr(form.value.recurrenceEndDate)
        : undefined,
      count: form.value.recurrenceEndType === 'COUNT'
        ? form.value.recurrenceCount
        : undefined,
    }
  } else if (isEdit.value && effectiveScope.value.isPersonal) {
    // 個人予定の編集で繰り返しを OFF にした場合は null を明示送信してクリアする
    body.recurrenceRule = null
  }

  // === 機能55: リマインダー ===
  if (effectiveScope.value.isPersonal) {
    // 個人予定: 相対は reminders(number[])、絶対は absoluteReminders(string[]) に振り分け
    // 編集時も作成時も同じペイロード形式で送信する（空配列＝全削除）
    const relativeMinutes = form.value.reminders
      .filter(r => r.kind === 'RELATIVE')
      .map(r => relativeReminderToMinutes(r.relativeValue, r.relativeUnit))
    // 絶対リマインダーは OffsetDateTime 形式（TZオフセット付き）で送信する
    const absolute = form.value.reminders
      .filter(r => r.kind === 'ABSOLUTE')
      .map(r => buildOffsetDateTimeStr(r.absoluteAt))
      .filter((s): s is string => s !== null)
    body.reminders = relativeMinutes
    if (absolute.length > 0) body.absoluteReminders = absolute
  } else {
    // 共有予定: リマインダーは編集時も送信する（空配列＝全削除）
    body.reminders = buildSharedReminders()
  }

  // === 機能55: 予約アンケート・予約出欠（team/org のみ。作成時・編集時ともに送信） ===
  if (!effectiveScope.value.isPersonal) {
    if (form.value.scheduledSurvey.enabled) {
      const s = form.value.scheduledSurvey
      body.scheduledSurveys = [
        {
          scheduledAt: buildOffsetDateTimeStr(s.scheduledAt),
          survey: {
            title: s.title.trim() || undefined,
            isAnonymous: s.isAnonymous,
            allowMultipleSubmissions: false,
            resultsVisibility: s.resultsVisibility,
            distributionMode: 'ALL',
            questions: s.questions.map((q, qi) => ({
              questionType: q.questionType,
              questionText: q.questionText.trim(),
              isRequired: q.isRequired,
              displayOrder: qi,
              options: q.options
                .filter(o => o.optionText.trim() !== '')
                .map((o, oi) => ({ optionText: o.optionText.trim(), displayOrder: oi })),
            })),
          },
        },
      ]
    }
    if (form.value.scheduledAttendance.enabled) {
      const a = form.value.scheduledAttendance
      body.scheduledAttendance = {
        scheduledAt: buildOffsetDateTimeStr(a.scheduledAt),
        attendanceDeadline: buildOffsetDateTimeStr(a.attendanceDeadline) ?? undefined,
        commentOption: a.commentOption,
        minResponseRole: a.minResponseRole.trim() || undefined,
      }
    }
  }

  try {
    if (effectiveScope.value.isPersonal) {
      if (isEdit.value && props.scheduleId) {
        await scheduleApi.updatePersonalSchedule(props.scheduleId, body)
      } else {
        await scheduleApi.createPersonalSchedule(body)
      }
    } else {
      if (isEdit.value && props.scheduleId) {
        await scheduleApi.updateSchedule(effectiveScope.value.scopeType, effectiveScope.value.scopeId, props.scheduleId, body)
      } else {
        await scheduleApi.createSchedule(effectiveScope.value.scopeType, effectiveScope.value.scopeId, body)
      }
    }
    const successMsg = effectiveScope.value.isPersonal
      ? isEdit.value
        ? t('schedule.success_update_personal')
        : t('schedule.success_create_personal')
      : isEdit.value
        ? t('schedule.success_update')
        : t('schedule.success_create')
    if (!form.value.allDay && form.value.startTime && form.value.endTime) {
      saveTimeHistory(form.value.startTime, form.value.endTime)
    }
    notification.success(successMsg)
    // 実際に保存されたスコープを渡す（フォーム内でスコープを変更できるため、呼び出し側が
    // 開いた時点の props とは食い違いうる。§5.4/AC-11b: 呼び出し側の「どのレイヤーへ作成したか」
    // 判定はこの値を正とする）。
    emit('saved', {
      isPersonal: effectiveScope.value.isPersonal,
      scopeType: effectiveScope.value.scopeType,
      scopeId: effectiveScope.value.scopeId,
    })
    close()
  } catch (error) {
    fieldErrors.value = getFieldErrors(error)
    if (Object.keys(fieldErrors.value).length === 0)
      handleApiError(error, isEdit.value ? 'スケジュール更新' : 'スケジュール作成')
  } finally {
    submitting.value = false
  }
}

function resetForm() {
  form.value = {
    title: '',
    description: '',
    location: '',
    startDate: null,
    startTime: '09:00',
    endDate: null,
    endTime: '10:00',
    allDay: false,
    color: '#22c55e',
    attendanceRequired: false,
    recurrence: false,
    recurrenceType: 'WEEKLY',
    recurrenceInterval: 1,
    recurrenceDaysOfWeek: [],
    recurrenceEndType: 'NEVER',
    recurrenceEndDate: null,
    recurrenceCount: 10,
    allowProxyAttendance: false,
    isProxyAutoAccept: false,
    teamBreakdownEnabled: false,
    reminders: [],
    scheduledSurvey: {
      enabled: false,
      scheduledAt: null,
      title: '',
      isAnonymous: false,
      resultsVisibility: 'PUBLIC',
      questions: [],
    },
    scheduledAttendance: {
      enabled: false,
      scheduledAt: null,
      attendanceDeadline: null,
      commentOption: 'OPTIONAL',
      minResponseRole: '',
    },
  }
  targetMode.value = 'ALL_MEMBERS'
  targetUserIds.value = []
  targetValidationError.value = null
  fieldErrors.value = {}
}

function close() {
  emit('update:visible', false)
}
</script>

<template>
  <Dialog
    :visible="visible"
    :header="
      effectiveScope.isPersonal
        ? isEdit
          ? '予定を編集'
          : '予定を追加'
        : isEdit
          ? 'イベントを編集'
          : 'イベントを作成'
    "
    :style="{ width: '500px' }"
    modal
    @update:visible="close"
    @hide="resetForm"
  >
    <div class="flex flex-col gap-4">
      <!-- スコープ選択（複数スコープがある場合のみ表示） -->
      <ScheduleEventScopeSelector
        v-if="props.scopeOptions && props.scopeOptions.length > 1"
        v-model:selected-scope-key="selectedScopeKey"
        :scope-options="props.scopeOptions"
      />
      <ScheduleEventBasicFields
        v-model:form="form"
        :field-errors="fieldErrors"
        :is-personal-scope="effectiveScope.isPersonal"
        :time-history="timeHistory"
        :time-options="timeOptions"
      />
      <ScheduleTargetPicker
        v-if="!effectiveScope.isPersonal"
        v-model:target-mode="targetMode"
        v-model:target-user-ids="targetUserIds"
        :scope-type="effectiveScope.scopeType"
        :scope-id="effectiveScope.scopeId"
        @invalid="targetValidationError = $event"
      />
      <!-- F03.1 (B) チーム別内訳トグル（組織スコープ + 出欠ありのときのみ） -->
      <div
        v-if="effectiveScope.scopeType === 'organization' && form.attendanceRequired"
        class="flex flex-col gap-1 rounded-lg bg-surface-50 p-3 dark:bg-surface-800"
      >
        <div class="flex items-center gap-3">
          <Checkbox
            v-model="form.teamBreakdownEnabled"
            :binary="true"
            input-id="scheduleTeamBreakdown"
            data-testid="schedule-team-breakdown-toggle"
          />
          <label for="scheduleTeamBreakdown" class="text-sm text-gray-700 dark:text-gray-300">
            {{ $t('schedule.attendanceTeamBreakdown.toggleLabel') }}
          </label>
        </div>
        <p class="ml-6 text-xs text-gray-500 dark:text-gray-400">
          {{ $t('schedule.attendanceTeamBreakdown.toggleHint') }}
        </p>
      </div>

      <!-- 代理出席設定（チームまたは組織スコープのみ） -->
      <div v-if="!effectiveScope.isPersonal" class="flex flex-col gap-2">
        <div class="flex items-center gap-3">
          <Checkbox
            v-model="form.allowProxyAttendance"
            :binary="true"
            input-id="scheduleAllowProxy"
          />
          <label for="scheduleAllowProxy" class="text-sm text-gray-700 dark:text-gray-300">
            {{ $t('proxy.delegation.allow_proxy') }}
          </label>
        </div>
        <div v-if="form.allowProxyAttendance" class="ml-6 flex flex-col gap-1">
          <div class="flex items-center gap-3">
            <Checkbox
              v-model="form.isProxyAutoAccept"
              :binary="true"
              input-id="scheduleAutoAccept"
            />
            <label for="scheduleAutoAccept" class="text-sm text-gray-700 dark:text-gray-300">
              {{ $t('proxy.delegation.auto_accept') }}
            </label>
          </div>
          <p class="text-xs text-gray-500 dark:text-gray-400">
            {{ $t('proxy.delegation.auto_accept_note') }}
          </p>
        </div>
      </div>

      <ScheduleEventRecurrenceInput v-model:form="form" />

      <!-- 機能55: リマインダー入力（全スコープ） -->
      <ScheduleEventReminderInput v-model:form="form" />

      <!-- 機能55: 予約アンケート・予約出欠（team/org のみ。編集時も表示） -->
      <ScheduleEventScheduledAttachmentInput
        v-if="!effectiveScope.isPersonal"
        v-model:form="form"
      />

      <div>
        <label class="mb-1 block text-sm font-medium">{{ $t('schedule.description_label') }}</label>
        <Textarea v-model="form.description" rows="3" class="w-full" />
      </div>
      <ScheduleEventColorPicker
        v-if="effectiveScope.isPersonal"
        v-model:color="form.color"
      />

      <!-- Google カレンダー連携中の注意書き -->
      <div
        v-if="googleSyncEnabled"
        class="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-200"
      >
        <i class="pi pi-info-circle mr-1" aria-hidden="true" />
        {{ $t('schedule.google_sync_notice') }}
      </div>
    </div>
    <template #footer>
      <Button label="キャンセル" text @click="close" />
      <Button
        :label="isEdit ? '更新' : '作成'"
        icon="pi pi-check"
        :loading="submitting"
        @click="submit"
      />
    </template>
  </Dialog>
</template>
