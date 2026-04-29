<script setup lang="ts">
import type { ClosurePreviewItem, ClosureHistoryItem, ClosureConfirmationItem } from '~/composables/useEmergencyClosureApi'

const props = defineProps<{
  teamId: number
}>()

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
    return '開始時刻と終了時刻を選択してください'
  }
  if (startHour.value >= endHour.value) {
    return '開始時刻は終了時刻より前にしてください'
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

const TEMPLATES: Template[] = [
  {
    label: '先生体調不良',
    subject: '【重要】担当スタッフ体調不良による臨時休業のお知らせ',
    reason: '担当スタッフ体調不良',
    body: `いつもご利用いただきありがとうございます。

誠に恐れ入りますが、担当スタッフの体調不良により、
{period}の診療を休業とさせていただきます。

ご予約いただいておりましたお客様には大変ご迷惑をおかけし、深くお詫び申し上げます。

改めてご予約いただけますようよろしくお願いいたします。`,
  },
  {
    label: '設備メンテナンス',
    subject: '【重要】設備メンテナンスによる臨時休業のお知らせ',
    reason: '設備メンテナンス',
    body: `いつもご利用いただきありがとうございます。

誠に恐れ入りますが、設備メンテナンスのため、
{period}の診療を休業とさせていただきます。

ご不便をおかけして大変申し訳ございませんが、何卒ご理解いただけますようお願い申し上げます。

改めてご予約いただけますようよろしくお願いいたします。`,
  },
  {
    label: '天候・緊急事態',
    subject: '【重要】臨時休業のお知らせ',
    reason: '天候・緊急事態',
    body: `いつもご利用いただきありがとうございます。

誠に恐れ入りますが、天候・緊急事態のため、
{period}の診療を休業とさせていただきます。

ご予約いただいておりましたお客様には大変ご迷惑をおかけし、深くお詫び申し上げます。

状況が改善次第、改めてご連絡させていただきます。`,
  },
  {
    label: 'その他（手動入力）',
    subject: '',
    reason: '',
    body: '',
  },
]

const selectedTemplateIndex = ref<number | null>(null)

function applyTemplate(index: number) {
  selectedTemplateIndex.value = index
  const tmpl = TEMPLATES[index]
  subject.value = tmpl.subject
  reason.value = tmpl.reason
  messageBody.value = tmpl.body.replace('{period}', periodText.value)
}

// --- メッセージ編集 ---
const subject = ref('')
const reason = ref('')
const messageBody = ref('')

// 期間または時間帯が変わったときにテンプレート本文の {period} を更新
watch([startDate, endDate, useTimeRange, startHour, endHour], () => {
  if (selectedTemplateIndex.value === null) return
  const tmpl = TEMPLATES[selectedTemplateIndex.value]
  messageBody.value = tmpl.body.replace('{period}', periodText.value)
})

// --- オプション ---
const cancelReservations = ref(false)

// --- プレビュー ---
const previewLoading = ref(false)
const previewItems = ref<ClosurePreviewItem[]>([])
const previewDone = ref(false)

async function loadPreview() {
  if (!startDate.value || !endDate.value) {
    notification.warn('期間を選択してください')
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
    notification.error('プレビューの取得に失敗しました')
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
    notification.warn('期間を選択してください')
    return
  }
  if (timeRangeError.value) {
    notification.warn(timeRangeError.value)
    return
  }
  if (!subject.value.trim()) {
    notification.warn('件名を入力してください')
    return
  }
  if (!messageBody.value.trim()) {
    notification.warn('本文を入力してください')
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
    notification.success(`${res.data.notifiedCount}件に送信しました`)
    await loadHistory()
    previewItems.value = []
    previewDone.value = false
  }
  catch {
    notification.error('送信に失敗しました')
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
  const map: Record<string, string> = {
    PENDING: '仮予約',
    CONFIRMED: '確定',
    COMPLETED: '完了',
    CANCELLED: 'キャンセル',
    NO_SHOW: '無断不参加',
    REJECTED: '拒否',
  }
  return map[status] ?? status
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
    notification.error('確認状況の取得に失敗しました')
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
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">休業期間</h3>
      <div class="flex flex-wrap items-end gap-3">
        <div>
          <label class="mb-1 block text-sm font-medium">開始日</label>
          <input
            v-model="startDate"
            type="date"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">終了日</label>
          <input
            v-model="endDate"
            type="date"
            :min="startDate"
            class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
          >
        </div>
        <Button
          label="今日のみ"
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
            一日のうち一部の時間帯のみ休業する
          </label>
        </div>
        <p class="mt-1 text-xs text-surface-400">
          チェックを入れない場合は終日休業になります。複数日指定時は各日同じ時間帯が休業対象です
        </p>

        <div v-if="useTimeRange" class="mt-3 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-sm font-medium">開始時刻</label>
            <select
              v-model.number="startHour"
              class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
            >
              <option :value="null">選択してください</option>
              <option v-for="h in HOURS" :key="`s${h}`" :value="h">
                {{ String(h).padStart(2, '0') }}:00
              </option>
            </select>
          </div>
          <span class="pb-2 text-surface-400">〜</span>
          <div>
            <label class="mb-1 block text-sm font-medium">終了時刻</label>
            <select
              v-model.number="endHour"
              class="rounded-md border border-surface-300 bg-white px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-primary-500 dark:border-surface-600 dark:bg-surface-800 dark:text-surface-100"
            >
              <option :value="null">選択してください</option>
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
        対象期間: <span class="font-medium text-surface-700 dark:text-surface-200">{{ periodText }}</span>
      </p>
    </section>

    <!-- Section 2: テンプレート選択 -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">メッセージテンプレート</h3>
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
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">メッセージ編集</h3>
      <div class="space-y-3">
        <div>
          <label class="mb-1 block text-sm font-medium">件名</label>
          <InputText v-model="subject" class="w-full" placeholder="件名を入力してください" />
        </div>
        <div>
          <label class="mb-1 block text-sm font-medium">本文</label>
          <Textarea
            v-model="messageBody"
            class="w-full"
            rows="8"
            placeholder="本文を入力してください"
            auto-resize
          />
        </div>
      </div>
    </section>

    <!-- Section 4: オプション -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">オプション</h3>
      <div class="flex items-center gap-2">
        <Checkbox v-model="cancelReservations" input-id="cancel-reservations" :binary="true" />
        <label for="cancel-reservations" class="cursor-pointer text-sm">
          対象予約をキャンセルする
        </label>
      </div>
      <p class="mt-1 text-xs text-surface-400">
        チェックを入れると、対象期間の予約が自動的にキャンセル状態になります
      </p>
    </section>

    <!-- Section 5: プレビュー -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold text-surface-700 dark:text-surface-200">影響する予約の確認</h3>
        <Button
          label="影響する予約を確認"
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
            {{ previewItems.length }}件の予約に通知します
          </span>
          <span v-else class="text-surface-400">
            対象期間に予約はありません
          </span>
        </p>

        <div v-if="previewItems.length > 0" class="overflow-x-auto">
          <table class="w-full text-sm">
            <thead>
              <tr class="border-b border-surface-200 dark:border-surface-600">
                <th class="pb-2 text-left font-medium text-surface-500">患者名</th>
                <th class="pb-2 text-left font-medium text-surface-500">日時</th>
                <th class="pb-2 text-left font-medium text-surface-500">ステータス</th>
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
        「影響する予約を確認」ボタンを押して対象予約を確認してください
      </p>
    </section>

    <!-- Section 6: 送信 -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <h3 class="mb-3 font-semibold text-surface-700 dark:text-surface-200">一括送信</h3>

      <div v-if="sendResult !== null" class="mb-3 rounded-md bg-green-50 px-4 py-3 text-sm text-green-700 dark:bg-green-900/20 dark:text-green-400">
        <i class="pi pi-check-circle mr-2" />
        {{ sendResult }}件に送信しました
      </div>

      <Button
        label="一括送信"
        icon="pi pi-send"
        severity="danger"
        :loading="sendLoading"
        @click="openConfirm"
      />
    </section>

    <!-- 送信履歴 -->
    <section class="rounded-lg border border-surface-200 p-4 dark:border-surface-700">
      <div class="mb-3 flex items-center justify-between">
        <h3 class="font-semibold text-surface-700 dark:text-surface-200">送信履歴</h3>
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
              <th class="pb-2 text-left font-medium text-surface-500">送信日時</th>
              <th class="pb-2 text-left font-medium text-surface-500">休業期間</th>
              <th class="pb-2 text-left font-medium text-surface-500">理由</th>
              <th class="pb-2 text-left font-medium text-surface-500">件数</th>
              <th class="pb-2 text-left font-medium text-surface-500">確認状況</th>
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
                        {{ confirmedCount(item.id) }}/{{ totalCount(item.id) }}件確認済み
                      </span>
                      <span v-else>確認状況を見る</span>
                      <i :class="expandedClosureId === item.id ? 'pi pi-chevron-up' : 'pi pi-chevron-down'" class="text-xs" />
                    </template>
                  </button>
                </td>
              </tr>
              <tr v-if="expandedClosureId === item.id">
                <td colspan="5" class="px-4 pb-3 pt-1">
                  <div v-if="confirmationsMap[item.id]" class="rounded-md border border-surface-200 bg-surface-50 p-3 dark:border-surface-600 dark:bg-surface-800">
                    <p class="mb-2 text-xs font-semibold text-surface-500">確認状況</p>
                    <table class="w-full text-xs">
                      <thead>
                        <tr class="border-b border-surface-200 dark:border-surface-600">
                          <th class="pb-1 text-left font-medium text-surface-500">患者名</th>
                          <th class="pb-1 text-left font-medium text-surface-500">予約日時</th>
                          <th class="pb-1 text-left font-medium text-surface-500">確認</th>
                          <th class="pb-1 text-left font-medium text-surface-500">リマインド</th>
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
                              <i class="pi pi-check-circle" /> 確認済み
                            </span>
                            <span v-else class="inline-flex items-center gap-1 text-red-500">
                              <i class="pi pi-times-circle" /> 未確認
                            </span>
                          </td>
                          <td class="py-1">
                            <span v-if="conf.reminderSent" class="text-amber-500">送信済み</span>
                            <span v-else class="text-surface-400">未送信</span>
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
      <DashboardEmptyState v-else icon="pi pi-inbox" message="送信履歴はありません" />
    </section>

    <!-- 確認ダイアログ -->
    <Dialog
      v-model:visible="showConfirm"
      header="送信確認"
      :style="{ width: '420px' }"
      modal
    >
      <div class="space-y-2 text-sm">
        <p>以下の内容で一括送信します。よろしいですか？</p>
        <ul class="mt-2 space-y-1 rounded-md bg-surface-50 p-3 dark:bg-surface-800">
          <li><span class="font-medium">対象期間:</span> {{ periodText }}</li>
          <li><span class="font-medium">件名:</span> {{ subject }}</li>
          <li v-if="cancelReservations" class="text-orange-600 dark:text-orange-400">
            ※ 対象予約はキャンセルされます
          </li>
        </ul>
      </div>
      <template #footer>
        <Button label="キャンセル" text @click="showConfirm = false" />
        <Button label="送信する" icon="pi pi-send" severity="danger" @click="confirmSend" />
      </template>
    </Dialog>
  </div>
</template>
