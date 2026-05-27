<script setup lang="ts">
import dayjs from 'dayjs'
import type { ScheduleEventFormState, TimeHistoryEntry } from './event-form/types'

interface ScopeOption {
  label: string
  value: string
  isPersonal: boolean
  scopeType: 'team' | 'organization'
  scopeId: number
}

const props = defineProps<{
  scopeType: 'team' | 'organization'
  scopeId: number
  scheduleId?: number
  initialDate?: string
  visible: boolean
  isPersonal?: boolean
  scopeOptions?: ScopeOption[]
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
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
const { userTimezone } = useDatetime()

const submitting = ref(false)
const fieldErrors = ref<Record<string, string>>({})
const isEdit = computed(() => !!props.scheduleId)

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

watch(
  () => [props.visible, props.scheduleId],
  async ([visible, scheduleId]) => {
    if (visible && scheduleId) {
      try {
        const res = effectiveScope.value.isPersonal
          ? await scheduleApi.getMyScheduleDetail(scheduleId as number)
          : await scheduleApi.getSchedule(effectiveScope.value.scopeType, effectiveScope.value.scopeId, scheduleId as number)
        const data = (res as { data: Record<string, unknown> }).data as Record<string, unknown>
        form.value.title = (data.title as string) ?? ''
        form.value.description = (data.description as string) ?? ''
        form.value.location = (data.location as string) ?? ''
        form.value.allDay = (data.allDay as boolean) ?? false
        form.value.attendanceRequired = (data.attendanceRequired as boolean) ?? false
        form.value.allowProxyAttendance = (data.allowProxyAttendance as boolean) ?? false
        form.value.isProxyAutoAccept = (data.isProxyAutoAccept as boolean) ?? false
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
      } catch {
        notification.error('イベント情報の取得に失敗しました')
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
function toLocalDateStr(date: Date): string {
  return dayjs(date).tz(userTimezone.value).format('YYYY-MM-DD')
}

function buildDateTimeStr(date: Date | null, time: string): string | null {
  if (!date) return null
  const dateStr = toLocalDateStr(date)
  return time ? `${dateStr}T${time}:00` : `${dateStr}T00:00:00`
}

async function submit() {
  if (!form.value.title.trim()) {
    fieldErrors.value = { title: 'タイトルは必須です' }
    return
  }
  submitting.value = true
  fieldErrors.value = {}

  const body: Record<string, unknown> = {
    title: form.value.title.trim(),
    description: form.value.description.trim() || undefined,
    location: form.value.location.trim() || undefined,
    allDay: form.value.allDay,
    startAt: buildDateTimeStr(form.value.startDate, form.value.allDay ? '' : form.value.startTime) ?? undefined,
    endAt: (() => {
      if (form.value.allDay && form.value.endDate) {
        const d = new Date(form.value.endDate)
        d.setDate(d.getDate() + 1)
        return buildDateTimeStr(d, '') ?? undefined
      }
      return buildDateTimeStr(form.value.endDate, form.value.allDay ? '' : form.value.endTime) ?? undefined
    })(),
  }
  if (effectiveScope.value.isPersonal) {
    body.color = form.value.color
  } else {
    body.eventType = 'OTHER'
    body.attendanceRequired = form.value.attendanceRequired
    body.allow_proxy_attendance = form.value.allowProxyAttendance
    body.is_proxy_auto_accept = form.value.allowProxyAttendance ? form.value.isProxyAutoAccept : false
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
        ? '予定を更新しました'
        : '予定を追加しました'
      : isEdit.value
        ? 'イベントを更新しました'
        : 'イベントを作成しました'
    if (!form.value.allDay && form.value.startTime && form.value.endTime) {
      saveTimeHistory(form.value.startTime, form.value.endTime)
    }
    notification.success(successMsg)
    emit('saved')
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
  }
  fieldErrors.value = {}
}

function close() {
  emit('update:visible', false)
  resetForm()
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
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" rows="3" class="w-full" />
      </div>
      <ScheduleEventColorPicker
        v-if="effectiveScope.isPersonal"
        v-model:color="form.color"
      />
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
