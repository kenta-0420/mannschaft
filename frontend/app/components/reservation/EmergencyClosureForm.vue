<script setup lang="ts">
import type { ClosurePreviewItem, ClosureHistoryItem, ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

const props = defineProps<{
  teamId: number
}>()

const { t } = useI18n()
const closureApi = useEmergencyClosureApi()
const notification = useNotification()

// --- 日付 ---
const today = new Date().toISOString().slice(0, 10)
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
const HOURS = Array.from({ length: 24 }, (_, i) => i) // 0〜23

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
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.period') }}</h3>
      <div class="flex flex-wrap items-end gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.start_date') }}</label>
          <input
            v-model="startDate"
            type="date"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.end_date') }}</label>
          <input
            v-model="endDate"
            type="date"
            :min="startDate"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
        </div>
        <Button
          :label="$t('emergency_closure.button.today_only')"
          icon="pi pi-calendar"
          size="small"
          severity="secondary"
          outlined
          @click="setToday"
        />
      </div>

      <!-- 時間帯指定（部分時間帯休業）-->
      <div class="mt-4 border-t border-surface-100 pt-3 dark:border-surface-700">
        <div class="flex items-center gap-2">
          <Checkbox v-model="useTimeRange" input-id="use-time-range" :binary="true" />
          <label for="use-time-range" class="cursor-pointer text-sm">
            {{ $t('emergency_closure.label.partial_time') }}
          </label>
        </div>
        <p class="mt-1 text-xs text-surface-400">
          {{ $t('emergency_closure.hint.all_day') }}
        </p>

        <div v-if="useTimeRange" class="mt-3 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.start_time') }}</label>
            <select
              v-model.number="startHour"
              class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
            >
              <option :value="null">{{ $t('emergency_closure.placeholder.select') }}</option>
              <option v-for="h in HOURS" :key="`s${h}`" :value="h">
                {{ String(h).padStart(2, '0') }}:00
              </option>
            </select>
          </div>
          <span class="pb-2 text-surface-400">〜</span>
          <div>
            <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.end_time') }}</label>
            <select
              v-model.number="endHour"
              class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
            >
              <option :value="null">{{ $t('emergency_closure.placeholder.select') }}</option>
              <option v-for="h in HOURS" :key="`e${h}`" :value="h">
                {{ String(h).padStart(2, '0') }}:00
              </option>
            </select>
          </div>
        </div>

        <p v-if="timeRangeError" class="mt-2 text-xs text-red-500">
          <i class="pi pi-exclamation-circle mr-1" />{{ timeRangeError }}
        </p>
      </div>

      <p v-if="startDate" class="mt-3 text-sm text-surface-500">
        {{ $t('emergency_closure.label.target_period') }}: <span class="font-medium text-surface-700 dark:text-surface-200">{{ periodText }}</span>
      </p>
    </section>

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
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.message_edit') }}</h3>
      <div class="space-y-3">
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.subject') }}</label>
          <InputText v-model="subject" class="w-full" :placeholder="$t('emergency_closure.placeholder.subject')" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ $t('emergency_closure.label.body') }}</label>
          <Textarea
            v-model="messageBody"
            class="w-full"
            rows="8"
            :placeholder="$t('emergency_closure.placeholder.body')"
            auto-resize
          />
        </div>
      </div>
    </section>

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
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.preview') }}</h3>
        <Button
          :label="$t('emergency_closure.button.check_preview')"
          icon="pi pi-search"
          size="small"
          severity="info"
          outlined
          :loading="previewLoading"
          @click="loadPreview"
        />
      </div>

      <div v-if="previewLoading">
        <Skeleton v-for="i in 3" :key="i" height="2.5rem" class="mb-2" />
      </div>

      <template v-else-if="previewDone">
        <p class="mb-2 text-sm font-medium">
          <span v-if="previewItems.length > 0" class="text-primary-600 dark:text-primary-400">
            {{ $t('emergency_closure.message.notify_count', { count: previewItems.length }) }}
          </span>
          <span v-else class="text-surface-400">
            {{ $t('emergency_closure.message.no_reservations') }}
          </span>
        </p>

        <div v-if="previewItems.length > 0" class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-surface-200 dark:border-surface-600">
                <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.patient_name') }}</th>
                <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.datetime') }}</th>
                <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.status') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="item in previewItems"
                :key="item.reservationId"
                class="border-b border-surface-100 last:border-0 dark:border-surface-700"
              >
                <td class="py-2 pr-4">{{ item.userDisplayName }}</td>
                <td class="py-2 pr-4 text-surface-600 dark:text-surface-300">
                  {{ formatPreviewDateTime(item.slotDate, item.startTime, item.endTime) }}
                </td>
                <td class="py-2">
                  <Tag :value="statusLabel(item.status)" :severity="statusSeverity(item.status)" />
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <p v-else class="text-sm text-surface-400">
        {{ $t('emergency_closure.hint.press_check_preview') }}
      </p>
    </section>

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
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold text-surface-700 dark:text-surface-200">{{ $t('emergency_closure.section.history') }}</h3>
        <Button
          icon="pi pi-refresh"
          text
          rounded
          size="small"
          :loading="historyLoading"
          @click="loadHistory"
        />
      </div>

      <div v-if="historyLoading">
        <Skeleton v-for="i in 2" :key="i" height="2.5rem" class="mb-2" />
      </div>
      <div v-else-if="historyItems.length > 0" class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-surface-200 dark:border-surface-600">
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.sent_at') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.section.period') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reason') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.count') }}</th>
              <th class="pb-2 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmation_status') }}</th>
            </tr>
          </thead>
          <tbody>
            <template
              v-for="item in historyItems"
              :key="item.id"
            >
              <tr class="border-b border-surface-100 dark:border-surface-700">
                <td class="py-2 pr-4 text-surface-600 dark:text-surface-300">
                  {{ new Date(item.createdAt).toLocaleString('ja-JP') }}
                </td>
                <td class="py-2 pr-4">
                  <div>
                    {{ item.startDate === item.endDate ? formatDate(item.startDate) : `${formatDate(item.startDate)}〜${formatDate(item.endDate)}` }}
                  </div>
                  <div v-if="item.startTime && item.endTime" class="text-xs text-surface-500">
                    {{ item.startTime }}〜{{ item.endTime }}
                  </div>
                </td>
                <td class="py-2 pr-4">{{ item.reason }}</td>
                <td class="py-2 pr-4">
                  <Tag :value="`${item.notifiedCount}件`" severity="info" />
                </td>
                <td class="py-2">
                  <button
                    class="inline-flex items-center gap-1 rounded text-xs text-primary-600 hover:underline dark:text-primary-400"
                    @click="toggleConfirmations(item.id)"
                  >
                    <i v-if="confirmationsLoading && expandedClosureId === item.id" class="pi pi-spin pi-spinner text-xs" />
                    <template v-else>
                      <span v-if="confirmationsMap[item.id]">
                        {{ $t('emergency_closure.message.confirmed_count', { confirmed: confirmedCount(item.id), total: totalCount(item.id) }) }}
                      </span>
                      <span v-else>{{ $t('emergency_closure.button.view_confirmations') }}</span>
                      <i :class="expandedClosureId === item.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" class="text-xs" />
                    </template>
                  </button>
                </td>
              </tr>
              <tr v-if="expandedClosureId === item.id">
                <td colspan="5" class="px-4 pb-3 pt-1">
                  <div v-if="confirmationsMap[item.id]" class="rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800">
                    <p class="mb-2 text-xs font-semibold text-surface-500">{{ $t('emergency_closure.section.confirmations') }}</p>
                    <table class="w-full text-xs">
                      <thead>
                        <tr class="border-b border-surface-200 dark:border-surface-600">
                          <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.patient_name') }}</th>
                          <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.appointment_at') }}</th>
                          <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.confirmed_header') }}</th>
                          <th class="pb-1 text-left font-medium text-surface-500">{{ $t('emergency_closure.table.reminder') }}</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr
                          v-for="conf in confirmationsMap[item.id]"
                          :key="conf.userId"
                          class="border-b border-surface-100 last:border-0 dark:border-surface-700"
                        >
                          <td class="py-1 pr-4">{{ conf.userDisplayName }}</td>
                          <td class="py-1 pr-4 text-surface-500">
                            {{ new Date(conf.appointmentAt).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}
                          </td>
                          <td class="py-1 pr-4">
                            <span v-if="conf.confirmed" class="inline-flex items-center gap-1 text-green-600 dark:text-green-400">
                              <i class="pi pi-check-circle" /> {{ $t('emergency_closure.status.confirmed') }}
                            </span>
                            <span v-else class="inline-flex items-center gap-1 text-red-500">
                              <i class="pi pi-times-circle" /> {{ $t('emergency_closure.status.unconfirmed') }}
                            </span>
                          </td>
                          <td class="py-1">
                            <span v-if="conf.reminderSent" class="text-amber-500">{{ $t('emergency_closure.status.reminder_sent') }}</span>
                            <span v-else class="text-surface-400">{{ $t('emergency_closure.status.reminder_not_sent') }}</span>
                          </td>
                        </tr>
                      </tbody>
                    </table>
                  </div>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
      </div>
      <DashboardEmptyState v-else icon="pi pi-inbox" :message="$t('emergency_closure.message.no_history')" />
    </section>

    <!-- 確認ダイアログ -->
    <Dialog
      v-model:visible="showConfirm"
      :header="$t('emergency_closure.dialog.title')"
      :style="{ width: '420px' }"
      modal
    >
      <div class="space-y-2 text-sm">
        <p>{{ $t('emergency_closure.dialog.confirm_message') }}</p>
        <ul class="mt-2 space-y-1 rounded-md bg-surface-50 p-3 dark:bg-surface-800">
          <li><span class="font-medium">{{ $t('emergency_closure.dialog.label_period') }}:</span> {{ periodText }}</li>
          <li><span class="font-medium">{{ $t('emergency_closure.dialog.label_subject') }}:</span> {{ subject }}</li>
          <li v-if="cancelReservations" class="text-orange-600 dark:text-orange-400">
            {{ $t('emergency_closure.dialog.cancel_warning') }}
          </li>
        </ul>
      </div>
      <template #footer>
        <Button :label="$t('button.cancel')" text @click="showConfirm = false" />
        <Button :label="$t('emergency_closure.button.send_confirm')" icon="pi pi-send" severity="danger" @click="confirmSend" />
      </template>
    </Dialog>
  </div>
</template>
