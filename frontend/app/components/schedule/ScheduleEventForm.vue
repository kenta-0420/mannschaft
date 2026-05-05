<script setup lang="ts">
const { t } = useI18n()

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

const SCOPE_OVERFLOW = 5

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

interface TimeHistoryEntry {
  startTime: string
  endTime: string
}

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

const form = ref({
  title: '',
  description: '',
  location: '',
  startDate: null as Date | null,
  startTime: '',
  endDate: null as Date | null,
  endTime: '',
  allDay: false,
  color: '#22c55e',
  attendanceRequired: false,
  recurrence: false,
  recurrenceType: 'WEEKLY' as 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY',
  recurrenceInterval: 1,
  recurrenceDaysOfWeek: [] as string[],
  recurrenceEndType: 'NEVER' as 'DATE' | 'COUNT' | 'NEVER',
  recurrenceEndDate: null as Date | null,
  recurrenceCount: 10,
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

function buildDateTimeStr(date: Date | null, time: string): string {
  if (!date) return ''
  const dateStr = date.toISOString().split('T')[0]
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
    startAt: buildDateTimeStr(form.value.startDate, form.value.allDay ? '' : form.value.startTime),
    endAt: buildDateTimeStr(form.value.endDate, form.value.allDay ? '' : form.value.endTime),
  }
  if (effectiveScope.value.isPersonal) {
    body.color = form.value.color
  } else {
    body.eventType = 'OTHER'
    body.attendanceRequired = form.value.attendanceRequired
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
        ? form.value.recurrenceEndDate.toISOString().split('T')[0]
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
  }
  fieldErrors.value = {}
}

function toggleDay(day: string) {
  const idx = form.value.recurrenceDaysOfWeek.indexOf(day)
  if (idx >= 0) {
    form.value.recurrenceDaysOfWeek.splice(idx, 1)
  } else {
    form.value.recurrenceDaysOfWeek.push(day)
  }
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
      <div v-if="props.scopeOptions && props.scopeOptions.length > 1" class="mb-4">
        <label class="mb-2 block text-sm font-medium text-surface-600 dark:text-surface-300">作成先</label>

        <!-- ≤5件: 横並びボタン -->
        <div v-if="props.scopeOptions.length <= SCOPE_OVERFLOW" class="flex flex-wrap gap-2">
          <button
            v-for="opt in props.scopeOptions"
            :key="opt.value"
            type="button"
            class="rounded-lg border px-3 py-1.5 text-sm font-medium transition-colors"
            :class="selectedScopeKey === opt.value
              ? 'border-primary bg-primary/10 text-primary'
              : 'border-surface-300 text-surface-500 hover:border-surface-400 dark:border-surface-600 dark:text-surface-400'"
            @click="selectedScopeKey = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>

        <!-- 6件以上: Select ドロップダウン（単一選択） -->
        <Select
          v-else
          v-model="selectedScopeKey"
          :options="props.scopeOptions"
          option-label="label"
          option-value="value"
          class="w-full"
          :placeholder="t('schedule.filter.selectScope')"
        />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium"
          >タイトル <span class="text-red-500">*</span></label
        >
        <InputText
          v-model="form.title"
          class="w-full"
          :class="{ 'p-invalid': fieldErrors.title }"
        />
        <small v-if="fieldErrors.title" class="text-red-500">{{ fieldErrors.title }}</small>
      </div>
      <div class="flex items-center gap-4">
        <div v-if="!effectiveScope.isPersonal" class="flex items-center gap-2">
          <Checkbox v-model="form.attendanceRequired" input-id="attendance-required" :binary="true" />
          <label for="attendance-required" class="text-sm cursor-pointer">出欠確認する</label>
        </div>
        <div class="flex items-center gap-2">
          <ToggleSwitch v-model="form.allDay" />
          <label class="text-sm">終日</label>
        </div>
      </div>
      <!-- よく使う時間（履歴クイック選択） -->
      <div v-if="timeHistory.length > 0 && !form.allDay" class="flex flex-wrap gap-1.5 mb-1">
        <span class="text-xs text-surface-400 self-center">履歴:</span>
        <button
          v-for="h in timeHistory"
          :key="`${h.startTime}-${h.endTime}`"
          type="button"
          class="text-xs px-2 py-0.5 rounded-full bg-surface-100 hover:bg-surface-200 dark:bg-surface-700 dark:hover:bg-surface-600 border border-surface-200 dark:border-surface-600"
          @click="form.startTime = h.startTime; form.endTime = h.endTime"
        >
          {{ h.startTime }}〜{{ h.endTime }}
        </button>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="schedule-start-date" class="mb-1 block text-sm font-medium">開始日</label>
          <DatePicker v-model="form.startDate" input-id="schedule-start-date" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">開始時刻</label>
          <Select
            v-model="form.startTime"
            :options="timeOptions"
            option-label="label"
            option-value="value"
            filter
            class="w-full"
          />
        </div>
      </div>
      <div class="grid grid-cols-2 gap-3">
        <div>
          <label for="schedule-end-date" class="mb-1 block text-sm font-medium">終了日</label>
          <DatePicker v-model="form.endDate" input-id="schedule-end-date" date-format="yy/mm/dd" class="w-full" show-icon />
        </div>
        <div v-if="!form.allDay">
          <label class="mb-1 block text-sm font-medium">終了時刻</label>
          <Select
            v-model="form.endTime"
            :options="timeOptions"
            option-label="label"
            option-value="value"
            filter
            class="w-full"
          />
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">場所</label>
        <InputText v-model="form.location" class="w-full" placeholder="場所（任意）" />
      </div>
      <!-- 繰り返し -->
      <div class="flex flex-col gap-3 rounded-lg border border-surface-200 dark:border-surface-600 p-3">
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">繰り返し</label>
          <ToggleSwitch v-model="form.recurrence" />
        </div>

        <template v-if="form.recurrence">
          <!-- 種別 + 間隔 -->
          <div class="flex items-center gap-2">
            <InputNumber
              v-model="form.recurrenceInterval"
              :min="1" :max="99"
              class="w-20"
              input-class="text-center"
            />
            <Select
              v-model="form.recurrenceType"
              :options="[
                { label: '日ごと',  value: 'DAILY' },
                { label: '週ごと',  value: 'WEEKLY' },
                { label: 'ヶ月ごと', value: 'MONTHLY' },
                { label: '年ごと',  value: 'YEARLY' },
              ]"
              option-label="label"
              option-value="value"
              class="flex-1"
            />
          </div>

          <!-- 曜日選択（WEEKLY のみ） -->
          <div v-if="form.recurrenceType === 'WEEKLY'" class="flex gap-1.5 flex-wrap">
            <button
              v-for="d in [
                { label: '日', value: 'SUNDAY' },
                { label: '月', value: 'MONDAY' },
                { label: '火', value: 'TUESDAY' },
                { label: '水', value: 'WEDNESDAY' },
                { label: '木', value: 'THURSDAY' },
                { label: '金', value: 'FRIDAY' },
                { label: '土', value: 'SATURDAY' },
              ]"
              :key="d.value"
              type="button"
              class="h-8 w-8 rounded-full text-xs font-medium border transition-colors"
              :class="form.recurrenceDaysOfWeek.includes(d.value)
                ? 'bg-primary text-white border-primary'
                : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
              @click="toggleDay(d.value)"
            >
              {{ d.label }}
            </button>
          </div>

          <!-- 終了条件 -->
          <div class="flex flex-col gap-2">
            <label class="text-xs text-surface-500">終了</label>
            <div class="flex flex-col gap-1.5">
              <label class="flex items-center gap-2 text-sm cursor-pointer">
                <RadioButton v-model="form.recurrenceEndType" value="NEVER" />
                指定なし
              </label>
              <label class="flex items-center gap-2 text-sm cursor-pointer">
                <RadioButton v-model="form.recurrenceEndType" value="DATE" />
                <span class="shrink-0">日付</span>
                <DatePicker
                  v-if="form.recurrenceEndType === 'DATE'"
                  v-model="form.recurrenceEndDate"
                  date-format="yy/mm/dd"
                  class="flex-1"
                  show-icon
                />
              </label>
              <label class="flex items-center gap-2 text-sm cursor-pointer">
                <RadioButton v-model="form.recurrenceEndType" value="COUNT" />
                <span class="shrink-0">回数</span>
                <InputNumber
                  v-if="form.recurrenceEndType === 'COUNT'"
                  v-model="form.recurrenceCount"
                  :min="1" :max="365"
                  class="w-20"
                  input-class="text-center"
                  suffix=" 回"
                />
              </label>
            </div>
          </div>
        </template>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">説明</label>
        <Textarea v-model="form.description" rows="3" class="w-full" />
      </div>
      <div v-if="effectiveScope.isPersonal">
        <label class="mb-1 block text-sm font-medium">色</label>
        <div class="flex gap-2">
          <button
            v-for="c in ['#6366f1', '#ef4444', '#22c55e', '#f59e0b', '#3b82f6', '#ec4899']"
            :key="c"
            class="h-8 w-8 rounded-full border-2"
            :class="
              form.color === c ? 'border-primary ring-2 ring-primary/30' : 'border-surface-300'
            "
            :style="{ backgroundColor: c }"
            @click="form.color = c"
          />
        </div>
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
