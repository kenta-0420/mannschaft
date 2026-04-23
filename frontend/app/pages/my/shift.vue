<script setup lang="ts">
import type { ShiftRequestResponse } from '~/types/shift'
import { preferenceToI18nKey } from '~/utils/shiftPreference'

/**
 * F03.5 マイシフト — 確定シフト一覧ページ
 *
 * - 月/週ビュー切り替え
 * - 日付ごとのシフトカード（開始/終了時刻・ポジション）
 * - 交代依頼作成ボタン
 */

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { error: showError, success: showSuccess } = useNotification()
const { listMyRequests } = useMyShiftApi()
const { createSwapRequest } = useShiftSwapApi()

type ViewMode = 'monthly' | 'weekly'
const viewMode = ref<ViewMode>('weekly')
const loading = ref(false)
const myRequests = ref<ShiftRequestResponse[]>([])
const currentDate = ref(new Date())

// 交代依頼ダイアログ用
const swapDialogVisible = ref(false)
const swapTargetSlotId = ref<number | null>(null)
const swapReason = ref('')
const swapLoading = ref(false)

// 週の範囲計算（月曜始まり）
const weekStart = computed(() => {
  const d = new Date(currentDate.value)
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  return d
})

const weekDates = computed(() => {
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(weekStart.value)
    d.setDate(d.getDate() + i)
    return d
  })
})

const monthLabel = computed(() =>
  currentDate.value.toLocaleDateString('ja-JP', { year: 'numeric', month: 'long' }),
)

const weekLabel = computed(() => {
  const start = weekStart.value
  const end = new Date(start)
  end.setDate(end.getDate() + 6)
  const fmt = (d: Date) => `${d.getMonth() + 1}/${d.getDate()}`
  return `${fmt(start)} 〜 ${fmt(end)}`
})

function dateKey(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function todayKey(): string {
  return dateKey(new Date())
}

// 日付ごとにリクエストをグループ化
const requestsByDate = computed(() => {
  const map = new Map<string, ShiftRequestResponse[]>()
  for (const req of myRequests.value) {
    const key = req.slotDate
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(req)
  }
  return map
})

// 表示対象の日付一覧
const visibleDates = computed((): Date[] => {
  if (viewMode.value === 'weekly') return weekDates.value
  // 月ビュー: 当月全日
  const y = currentDate.value.getFullYear()
  const m = currentDate.value.getMonth()
  const daysInMonth = new Date(y, m + 1, 0).getDate()
  return Array.from({ length: daysInMonth }, (_, i) => new Date(y, m, i + 1))
})

function prevPeriod() {
  const d = new Date(currentDate.value)
  if (viewMode.value === 'weekly') {
    d.setDate(d.getDate() - 7)
  } else {
    d.setMonth(d.getMonth() - 1)
  }
  currentDate.value = d
}

function nextPeriod() {
  const d = new Date(currentDate.value)
  if (viewMode.value === 'weekly') {
    d.setDate(d.getDate() + 7)
  } else {
    d.setMonth(d.getMonth() + 1)
  }
  currentDate.value = d
}

function goToToday() {
  currentDate.value = new Date()
}

async function load() {
  loading.value = true
  try {
    myRequests.value = await listMyRequests()
  } catch {
    showError(t('shift.notification.errorLoad'))
  } finally {
    loading.value = false
  }
}

function openSwapDialog(slotId: number) {
  swapTargetSlotId.value = slotId
  swapReason.value = ''
  swapDialogVisible.value = true
}

async function submitSwapRequest() {
  if (!swapTargetSlotId.value) return
  swapLoading.value = true
  try {
    await createSwapRequest({
      slotId: swapTargetSlotId.value,
      reason: swapReason.value || undefined,
    })
    showSuccess(t('shift.notification.submitSuccess'))
    swapDialogVisible.value = false
  } catch {
    showError(t('shift.notification.errorSubmit'))
  } finally {
    swapLoading.value = false
  }
}

onMounted(() => load())
</script>

<template>
  <div class="mx-auto max-w-4xl">
    <PageHeader :title="t('shift.page.myShift')" />

    <!-- ビュー切り替え・ナビゲーション -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <!-- 月/週切り替え -->
      <div class="flex overflow-hidden rounded-lg border border-surface-200">
        <button
          v-for="mode in (['weekly', 'monthly'] as const)"
          :key="mode"
          type="button"
          class="min-h-[44px] px-4 py-2 text-sm font-medium transition-colors"
          :class="
            viewMode === mode
              ? 'bg-primary text-white'
              : 'bg-surface-0 text-surface-600 hover:bg-surface-50'
          "
          @click="viewMode = mode"
        >
          {{ t(`shift.view.${mode}`) }}
        </button>
      </div>

      <!-- 期間ナビ -->
      <div class="flex items-center gap-2">
        <Button
          icon="pi pi-chevron-left"
          text
          rounded
          severity="secondary"
          :aria-label="t('shift.view.prev')"
          @click="prevPeriod"
        />
        <span class="min-w-[120px] text-center text-sm font-semibold text-surface-700">
          {{ viewMode === 'weekly' ? weekLabel : monthLabel }}
        </span>
        <Button
          icon="pi pi-chevron-right"
          text
          rounded
          severity="secondary"
          :aria-label="t('shift.view.next')"
          @click="nextPeriod"
        />
        <Button :label="t('shift.view.today')" text size="small" @click="goToToday" />
      </div>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <!-- シフトカード一覧 -->
      <div class="flex flex-col gap-3">
        <div
          v-for="date in visibleDates"
          :key="dateKey(date)"
          class="rounded-xl border border-surface-200 bg-surface-0 p-3"
        >
          <!-- 日付ヘッダー -->
          <div class="mb-2 flex items-center gap-2">
            <span
              class="flex h-8 w-8 items-center justify-center rounded-full text-sm font-bold"
              :class="
                dateKey(date) === todayKey()
                  ? 'bg-primary text-white'
                  : 'bg-surface-100 text-surface-700'
              "
            >
              {{ date.getDate() }}
            </span>
            <span class="text-xs text-surface-500">
              {{ date.toLocaleDateString('ja-JP', { weekday: 'short' }) }}
            </span>
          </div>

          <!-- その日のシフト -->
          <template v-if="(requestsByDate.get(dateKey(date)) ?? []).length > 0">
            <div
              v-for="req in requestsByDate.get(dateKey(date))"
              :key="req.id"
              class="mb-2 rounded-lg bg-surface-50 p-3 last:mb-0"
            >
              <div class="flex items-start justify-between gap-2">
                <div class="min-w-0 flex-1">
                  <p class="text-sm font-medium text-surface-800">
                    {{ t('shift.page.schedules') }} #{{ req.scheduleId }}
                  </p>
                  <p class="mt-0.5 text-xs text-surface-500">
                    {{ t('shift.field.preference') }}: {{ t(preferenceToI18nKey(req.preference)) }}
                  </p>
                  <p v-if="req.note" class="mt-0.5 truncate text-xs text-surface-400">
                    {{ req.note }}
                  </p>
                </div>
                <!-- 交代依頼ボタン（slotIdがある場合のみ） -->
                <Button
                  v-if="req.slotId != null"
                  :label="t('shift.swap.create')"
                  size="small"
                  outlined
                  severity="secondary"
                  class="shrink-0"
                  @click="openSwapDialog(req.slotId as number)"
                />
              </div>
            </div>
          </template>
          <template v-else>
            <p class="text-xs text-surface-400">—</p>
          </template>
        </div>
      </div>

      <!-- 空状態 -->
      <DashboardEmptyState
        v-if="myRequests.length === 0"
        icon="pi-clock"
        :message="t('shift.empty.noShifts')"
      />
    </template>

    <!-- 交代依頼ダイアログ -->
    <Dialog
      v-model:visible="swapDialogVisible"
      :header="t('shift.swap.create')"
      modal
      class="w-full max-w-md"
    >
      <div class="flex flex-col gap-4">
        <div>
          <label class="mb-1 block text-sm font-medium text-surface-700">
            {{ t('shift.field.reason') }}
            <span class="text-xs text-surface-400">（{{ t('common.label.optional') }}）</span>
          </label>
          <Textarea
            v-model="swapReason"
            :placeholder="t('shift.field.reason')"
            rows="3"
            class="w-full"
          />
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <Button
            :label="t('common.button.cancel')"
            text
            severity="secondary"
            @click="swapDialogVisible = false"
          />
          <Button
            :label="t('shift.action.submit')"
            :loading="swapLoading"
            @click="submitSwapRequest"
          />
        </div>
      </template>
    </Dialog>
  </div>
</template>
