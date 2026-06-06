<script setup lang="ts">
import dayjs from 'dayjs'
import type { ClosurePreviewItem, ClosureHistoryItem, ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

const props = defineProps<{
  teamId: string
}>()

const { t } = useI18n()
const closureApi = useEmergencyClosureApi()
const notification = useNotification()
const { userTimezone } = useDatetime()

// --- 日付 ---
const today = dayjs().tz(userTimezone.value).format('YYYY-MM-DD')
const startDate = ref(today)
const endDate = ref(today)

function setToday() {
  startDate.value = today
  endDate.value = today
}

// --- 時間帯（部分時間帯休業）---
// 終日休業がデフォルト。トグルで時間帯指定モードに切り替える。
// 時間単位（HH:00）のみを許可するため、0〜23時のセレクトボックスで指定する。
const useTimeRange = ref(false)
const startHour = ref<number | null>(null) // 0〜23
const endHour = ref<number | null>(null)   // 1〜24（終了時刻は 24:00 = 翌0:00 まで許容しないが UI 上は 23:00 まで）

function toHHmm(h: number | null): string | null {
  if (h === null) return null
  return `${String(h).padStart(2, '0')}:00`
}

function formatHour(h: number | null): string {
  if (h === null) return '--:--'
  return `${String(h).padStart(2, '0')}:00`
}

// 開始時刻を選んだとき、終了時刻が未設定 or 開始以下なら自動で +1 時間プリセット（入力摩擦削減）
watch(startHour, (h) => {
  if (h === null) return
  if (endHour.value === null || endHour.value <= h) {
    endHour.value = Math.min(h + 1, 23)
  }
})

// 終日／時間帯トグルを切り替えたとき、時間帯から終日に戻すなら時刻をクリア
watch(useTimeRange, (enabled) => {
  if (!enabled) {
    startHour.value = null
    endHour.value = null
  }
})

// --- 期間表示ヘルパー ---
const WEEKDAYS = ['日', '月', '火', '水', '木', '金', '土']

function formatDate(iso: string): string {
  const d = new Date(iso)
  const m = d.getMonth() + 1
  const day = d.getDate()
  const w = WEEKDAYS[d.getDay()]
  return `${m}月${day}日（${w}）`
}

/** プレビュー行の日時表示。"yyyy-MM-dd" + "HH:mm:ss" 2つを「4月8日（水）09:00〜11:00」形式に整形 */
function formatPreviewDateTime(slotDate: string, startTime: string, endTime: string): string {
  const date = formatDate(slotDate)
  const start = (startTime ?? '').slice(0, 5)
  const end = (endTime ?? '').slice(0, 5)
  return `${date} ${start}〜${end}`
}

const periodText = computed(() => {
  const datePart = startDate.value === endDate.value
    ? formatDate(startDate.value)
    : `${formatDate(startDate.value)}〜${formatDate(endDate.value)}`
  if (useTimeRange.value && startHour.value !== null && endHour.value !== null) {
    return `${datePart} ${formatHour(startHour.value)}〜${formatHour(endHour.value)}`
  }
  return datePart
})

// 時間帯指定が有効だが時刻未入力 or 不正な場合のエラーメッセージ
const timeRangeError = computed<string | null>(() => {
  if (!useTimeRange.value) return null
  if (startHour.value === null || endHour.value === null) {
    return t('emergency_closure.error.time_range_required')
  }
  if (startHour.value >= endHour.value) {
    return t('emergency_closure.error.time_range_order')
  }
  return null
})

// --- テンプレート ---
interface Template {
  label: string
  subject: string
  reason: string
  body: string
}

const TEMPLATES = computed((): Template[] => [
  {
    label: t('emergency_closure.template.staff_sick'),
    subject: t('emergency_closure.template.staff_sick_subject'),
    reason: t('emergency_closure.template.staff_sick_reason'),
    body: t('emergency_closure.template.staff_sick_body'),
  },
  {
    label: t('emergency_closure.template.maintenance'),
    subject: t('emergency_closure.template.maintenance_subject'),
    reason: t('emergency_closure.template.maintenance_reason'),
    body: t('emergency_closure.template.maintenance_body'),
  },
  {
    label: t('emergency_closure.template.emergency'),
    subject: t('emergency_closure.template.emergency_subject'),
    reason: t('emergency_closure.template.emergency_reason'),
    body: t('emergency_closure.template.emergency_body'),
  },
  {
    label: t('emergency_closure.template.custom'),
    subject: '',
    reason: '',
    body: '',
  },
])

const selectedTemplateIndex = ref<number | null>(null)

function applyTemplate(index: number) {
  selectedTemplateIndex.value = index
  const tmpl = TEMPLATES.value[index]
  if (!tmpl) return
  subject.value = tmpl.subject
  reason.value = tmpl.reason
  messageBody.value = tmpl.body.replace('__PERIOD__', periodText.value)
}

// --- メッセージ編集 ---
const subject = ref('')
const reason = ref('')
const messageBody = ref('')

// 期間または時間帯が変わったときにテンプレート本文の __PERIOD__ を更新
watch([startDate, endDate, useTimeRange, startHour, endHour], () => {
  if (selectedTemplateIndex.value === null) return
  const tmpl = TEMPLATES.value[selectedTemplateIndex.value]
  if (!tmpl) return
  messageBody.value = tmpl.body.replace('__PERIOD__', periodText.value)
})

// --- オプション ---
const cancelReservations = ref(false)

// --- プレビュー ---
const previewLoading = ref(false)
const previewItems = ref<ClosurePreviewItem[]>([])
const previewDone = ref(false)

async function loadPreview() {
  if (!startDate.value || !endDate.value) {
    notification.warn(t('emergency_closure.error.period_required'))
    return
  }
  if (timeRangeError.value) {
    notification.warn(timeRangeError.value)
    return
  }
  previewLoading.value = true
  previewDone.value = false
  try {
    const res = await closureApi.previewClosure(
      props.teamId,
      startDate.value,
      endDate.value,
      useTimeRange.value ? toHHmm(startHour.value) : null,
      useTimeRange.value ? toHHmm(endHour.value) : null,
    )
    // バックエンドは ApiResponse でラップして
    // { data: { affectedReservations: [...], ... } } を返すため、ここで配列を取り出す
    previewItems.value = res.data.affectedReservations
    previewDone.value = true
  }
  catch {
    notification.error(t('emergency_closure.error.preview_failed'))
  }
  finally {
    previewLoading.value = false
  }
}

// --- 送信 ---
const sendLoading = ref(false)
const sendResult = ref<number | null>(null)
const showConfirm = ref(false)

function openConfirm() {
  if (!startDate.value || !endDate.value) {
    notification.warn(t('emergency_closure.error.period_required'))
    return
  }
  if (timeRangeError.value) {
    notification.warn(timeRangeError.value)
    return
  }
  if (!subject.value.trim()) {
    notification.warn(t('emergency_closure.error.subject_required'))
    return
  }
  if (!messageBody.value.trim()) {
    notification.warn(t('emergency_closure.error.body_required'))
    return
  }
  showConfirm.value = true
}

async function confirmSend() {
  showConfirm.value = false
  sendLoading.value = true
  sendResult.value = null
  try {
    const res = await closureApi.sendClosure(props.teamId, {
      startDate: startDate.value,
      endDate: endDate.value,
      startTime: useTimeRange.value ? toHHmm(startHour.value) : null,
      endTime: useTimeRange.value ? toHHmm(endHour.value) : null,
      reason: reason.value,
      subject: subject.value,
      messageBody: messageBody.value,
      cancelReservations: cancelReservations.value,
    })
    sendResult.value = res.data.notifiedCount
    notification.success(t('emergency_closure.message.sent_count', { count: res.data.notifiedCount }))
    await loadHistory()
    previewItems.value = []
    previewDone.value = false
  }
  catch {
    notification.error(t('emergency_closure.error.send_failed'))
  }
  finally {
    sendLoading.value = false
  }
}

// --- 履歴 ---
const historyLoading = ref(false)
const historyItems = ref<ClosureHistoryItem[]>([])

async function loadHistory() {
  historyLoading.value = true
  try {
    const res = await closureApi.listClosures(props.teamId)
    historyItems.value = res.data
  }
  catch {
    historyItems.value = []
  }
  finally {
    historyLoading.value = false
  }
}

// --- ステータス日本語変換 ---
function statusLabel(status: string): string {
  const key = `emergency_closure.reservation_status.${status}`
  const translated = t(key)
  return translated !== key ? translated : status
}

function statusSeverity(status: string): string {
  const map: Record<string, string> = {
    PENDING: 'warn',
    CONFIRMED: 'info',
    COMPLETED: 'success',
    CANCELLED: 'secondary',
    NO_SHOW: 'danger',
    REJECTED: 'danger',
  }
  return map[status] ?? 'secondary'
}

// --- 確認状況パネル ---
const expandedClosureId = ref<number | null>(null)
const confirmationsMap = ref<Record<number, ClosureConfirmationItem[]>>({})
const confirmationsLoading = ref(false)

async function toggleConfirmations(closureId: number) {
  if (expandedClosureId.value === closureId) {
    expandedClosureId.value = null
    return
  }
  expandedClosureId.value = closureId
  if (confirmationsMap.value[closureId]) return // キャッシュあり

  confirmationsLoading.value = true
  try {
    const res = await closureApi.getConfirmations(props.teamId, closureId)
    confirmationsMap.value[closureId] = res.data
  }
  catch {
    notification.error(t('emergency_closure.error.confirmations_failed'))
    expandedClosureId.value = null
  }
  finally {
    confirmationsLoading.value = false
  }
}

function confirmedCount(closureId: number): number {
  return (confirmationsMap.value[closureId] ?? []).filter(c => c.confirmed).length
}

function totalCount(closureId: number): number {
  return (confirmationsMap.value[closureId] ?? []).length
}

onMounted(loadHistory)
</script>

<template>
  <div class="space-y-6">
    <!-- Section 1: 休業期間選択 -->
    <EmergencyClosurePeriodInput
      v-model:start-date="startDate"
      v-model:end-date="endDate"
      v-model:use-time-range="useTimeRange"
      v-model:start-hour="startHour"
      v-model:end-hour="endHour"
      :period-text="periodText"
      :time-range-error="timeRangeError"
      @set-today="setToday"
    />

    <!-- Section 2: テンプレート選択 -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.template') }}</h3>
      <div class="flex flex-wrap gap-2">
        <Button
          v-for="(tmpl, idx) in TEMPLATES"
          :key="idx"
          :label="tmpl.label"
          size="small"
          :severity="selectedTemplateIndex === idx ? 'primary' : 'secondary'"
          :outlined="selectedTemplateIndex !== idx"
          @click="applyTemplate(idx)"
        />
      </div>
    </section>

    <!-- Section 3: メッセージ編集 -->
    <EmergencyClosureMessageEditor
      v-model:subject="subject"
      v-model:message-body="messageBody"
    />

    <!-- Section 4: オプション -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.options') }}</h3>
      <div class="flex items-center gap-2">
        <Checkbox v-model="cancelReservations" input-id="cancel-reservations" :binary="true" />
        <label for="cancel-reservations" class="cursor-pointer text-sm">
          {{ $t('emergency_closure.label.cancel_reservations') }}
        </label>
      </div>
      <p class="mt-1 text-xs text-surface-400">
        {{ $t('emergency_closure.hint.cancel_reservations') }}
      </p>
    </section>

    <!-- Section 5: プレビュー -->
    <EmergencyClosurePreview
      :loading="previewLoading"
      :done="previewDone"
      :items="previewItems"
      :format-preview-date-time="formatPreviewDateTime"
      :status-label="statusLabel"
      :status-severity="statusSeverity"
      @check="loadPreview"
    />

    <!-- Section 6: 送信 -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.bulk_send') }}</h3>

      <div v-if="sendResult !== null" class="mb-3 rounded-md bg-green-50 px-4 py-3 text-sm text-green-700 dark:bg-green-900/20 dark:text-green-400">
        <i class="pi pi-check-circle mr-2" />
        {{ $t('emergency_closure.message.sent_count', { count: sendResult }) }}
      </div>

      <Button
        :label="$t('emergency_closure.button.bulk_send')"
        icon="pi pi-send"
        severity="danger"
        :loading="sendLoading"
        @click="openConfirm"
      />
    </section>

    <!-- 送信履歴 -->
    <EmergencyClosureHistory
      :loading="historyLoading"
      :items="historyItems"
      :expanded-closure-id="expandedClosureId"
      :confirmations-map="confirmationsMap"
      :confirmations-loading="confirmationsLoading"
      :format-date="formatDate"
      :confirmed-count="confirmedCount"
      :total-count="totalCount"
      @reload="loadHistory"
      @toggle-confirmations="toggleConfirmations"
    />

    <!-- 確認ダイアログ -->
    <EmergencyClosureConfirmDialog
      v-model:visible="showConfirm"
      :period-text="periodText"
      :subject="subject"
      :cancel-reservations="cancelReservations"
      @confirm="confirmSend"
    />
  </div>
</template>
