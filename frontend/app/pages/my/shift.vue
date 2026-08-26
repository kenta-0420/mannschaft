<script setup lang="ts">
import dayjs from 'dayjs'
import type { MyConfirmedSlotResponse } from '~/types/shift'

/**
 * F03.5 マイシフト — 確定シフト 月次カレンダービュー
 *
 * - 月次カレンダー（デフォルト）/ 週次リストビュー切り替え
 * - チームフィルタ（確定スロットのチームから一意抽出）
 * - 日付セルタップで右パネルにシフト詳細を表示
 * - 交代依頼ダイアログ（3モード）
 */

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const { userTimezone } = useDatetime()
const { error: showError } = useNotification()
const { listMyConfirmedSlots } = useMyShiftApi()

// ---- ビュー状態 ----
type ViewMode = 'monthly' | 'weekly'
const viewMode = ref<ViewMode>('monthly')
const currentDate = ref(new Date())
const loading = ref(false)
const confirmedSlots = ref<MyConfirmedSlotResponse[]>([])

// ---- チームフィルタ ----
const selectedTeamId = ref<number | null>(null)

// ---- 詳細パネル ----
const selectedDate = ref<string | null>(null)

// ---- 交代依頼ダイアログ ----
const swapDialogVisible = ref(false)
const swapSlotId = ref<number>(0)
const swapSlotDate = ref<string>('')
const swapScheduleId = ref<number>(0)
const swapTeamId = ref<string>('')

// ---- データ取得 ----
async function load() {
  loading.value = true
  try {
    confirmedSlots.value = await listMyConfirmedSlots()
  } catch {
    showError(t('shift.notification.errorLoad'))
  } finally {
    loading.value = false
  }
}

onMounted(() => load())

// ---- チーム一覧（一意抽出） ----
const teamOptions = computed(() => {
  const map = new Map<number, string>()
  for (const slot of confirmedSlots.value) {
    map.set(slot.teamId, slot.teamName)
  }
  return [...map.entries()].map(([id, name]) => ({ id, name }))
})

// ---- フィルタ済みスロット ----
const filteredSlots = computed((): MyConfirmedSlotResponse[] => {
  if (selectedTeamId.value === null) return confirmedSlots.value
  return confirmedSlots.value.filter((s) => s.teamId === selectedTeamId.value)
})

// ---- モバイル即時表示用: 今後の確定シフト（本日以降・日時昇順） ----
// 月次カレンダーの時刻スニペットは狭幅で hidden になり即時性が無いため、
// 390px では本セクションで時刻を直接可視化する（sm:hidden で PC は従来どおり）。
const upcomingSlots = computed((): MyConfirmedSlotResponse[] => {
  const today = todayKey()
  return [...filteredSlots.value]
    .filter((s) => s.slotDate >= today)
    .sort((a, b) => `${a.slotDate}T${a.startTime}`.localeCompare(`${b.slotDate}T${b.startTime}`))
    .slice(0, 10)
})

// ---- 日付ごとのスロットマップ ----
const slotsByDate = computed(() => {
  const map = new Map<string, MyConfirmedSlotResponse[]>()
  for (const slot of filteredSlots.value) {
    if (!map.has(slot.slotDate)) map.set(slot.slotDate, [])
    map.get(slot.slotDate)!.push(slot)
  }
  return map
})

// ---- 日付ユーティリティ ----
function dateKey(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function todayKey(): string {
  return dateKey(new Date())
}

function formatTime(timeStr: string): string {
  return timeStr.substring(0, 5)
}

// ---- 月次カレンダー生成 ----
const calendarDays = computed((): (Date | null)[] => {
  const y = currentDate.value.getFullYear()
  const m = currentDate.value.getMonth()
  const firstDay = new Date(y, m, 1)
  const lastDay = new Date(y, m + 1, 0)
  // 月曜始まり: 月=0, ..., 日=6
  const startDow = (firstDay.getDay() + 6) % 7
  const days: (Date | null)[] = []
  for (let i = 0; i < startDow; i++) days.push(null)
  for (let i = 1; i <= lastDay.getDate(); i++) days.push(new Date(y, m, i))
  // 末尾を7の倍数に揃える
  while (days.length % 7 !== 0) days.push(null)
  return days
})

// ---- 週次ビュー ----
const weekStart = computed(() => {
  const d = new Date(currentDate.value)
  const day = d.getDay()
  const diff = day === 0 ? -6 : 1 - day
  d.setDate(d.getDate() + diff)
  return d
})

const weekDates = computed((): Date[] => {
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(weekStart.value)
    d.setDate(d.getDate() + i)
    return d
  })
})

// ---- ラベル ----
const periodLabel = computed(() => {
  if (viewMode.value === 'monthly') {
    return dayjs.tz(currentDate.value, userTimezone.value).format('YYYY年M月')
  }
  const start = weekStart.value
  const end = new Date(start)
  end.setDate(end.getDate() + 6)
  const fmt = (d: Date) => `${d.getMonth() + 1}/${d.getDate()}`
  return `${fmt(start)} 〜 ${fmt(end)}`
})

// ---- ナビゲーション ----
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
  // 今日の日付を選択
  selectedDate.value = todayKey()
}

// ---- 詳細パネル ----
function selectDate(key: string) {
  selectedDate.value = selectedDate.value === key ? null : key
}

const selectedDateSlots = computed((): MyConfirmedSlotResponse[] => {
  if (!selectedDate.value) return []
  return slotsByDate.value.get(selectedDate.value) ?? []
})

const selectedDateFormatted = computed((): string => {
  if (!selectedDate.value) return ''
  return dayjs.tz(selectedDate.value, userTimezone.value).format('M月D日 (ddd)')
})

// ---- 交代依頼ダイアログ ----
function openSwapDialog(slot: MyConfirmedSlotResponse) {
  swapSlotId.value = slot.slotId
  swapSlotDate.value = slot.slotDate
  swapScheduleId.value = slot.scheduleId
  swapTeamId.value = String(slot.teamId)
  swapDialogVisible.value = true
}

function onSwapSubmitted() {
  // 交代依頼送信後にリロード
  load()
}

// ---- 週次の曜日ヘッダー ----
const DOW_KEYS = ['月', '火', '水', '木', '金', '土', '日'] as const

// ---- 使い方モーダル ----
const showGuide = ref(false)
</script>

<template>
  <div class="mx-auto max-w-5xl">
    <PageHeader :title="t('shift.page.myShift')" back-to="/my" help @help="showGuide = true" />

    <!-- モバイル即時表示: 今後の確定シフト（狭幅は月次カレンダーの時刻が hidden のため即時性が無い） -->
    <section v-if="!loading && upcomingSlots.length > 0" class="mb-4 sm:hidden">
      <h2 class="mb-2 text-sm font-semibold text-surface-700 dark:text-surface-300">
        {{ t('shift.myShift.upcomingTitle') }}
      </h2>
      <ul class="flex flex-col gap-2">
        <li
          v-for="slot in upcomingSlots"
          :key="slot.slotId"
          class="flex items-center gap-3 rounded-lg border border-surface-200 bg-surface-0 px-3 py-2 dark:border-surface-700 dark:bg-surface-900"
        >
          <div class="flex w-12 shrink-0 flex-col items-center">
            <span class="text-xs font-semibold text-surface-600 dark:text-surface-300">
              {{ dayjs.tz(slot.slotDate, userTimezone).format('M/D') }}
            </span>
            <span class="text-[10px] text-surface-400">
              {{ dayjs.tz(slot.slotDate, userTimezone).format('(ddd)') }}
            </span>
          </div>
          <div class="min-w-0 flex-1">
            <p class="text-sm font-bold text-primary">
              {{ formatTime(slot.startTime) }} 〜 {{ formatTime(slot.endTime) }}
            </p>
            <p class="truncate text-xs text-surface-500 dark:text-surface-400">
              {{ slot.teamName }}<template v-if="slot.positionName"> · {{ slot.positionName }}</template>
            </p>
          </div>
        </li>
      </ul>
    </section>

    <!-- ビュー切り替え・ナビゲーション -->
    <div class="mb-4 flex flex-wrap items-center justify-between gap-2">
      <!-- 月/週切り替え -->
      <div class="flex overflow-hidden rounded-lg border border-surface-200 dark:border-surface-700">
        <button
          v-for="mode in (['monthly', 'weekly'] as const)"
          :key="mode"
          type="button"
          class="min-h-[44px] px-4 py-2 text-sm font-medium transition-colors"
          :class="
            viewMode === mode
              ? 'bg-primary text-white'
              : 'bg-surface-0 text-surface-600 hover:bg-surface-50 dark:bg-surface-900 dark:text-surface-400 dark:hover:bg-surface-800'
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
        <span class="min-w-[120px] text-center text-sm font-semibold text-surface-700 dark:text-surface-300">
          {{ periodLabel }}
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

    <!-- チームフィルタ -->
    <div
      v-if="teamOptions.length > 0"
      class="mb-4 flex flex-wrap items-center gap-2"
    >
      <span class="text-xs text-surface-500 dark:text-surface-400">
        {{ t('shift.myShift.teamFilter.label') }}:
      </span>
      <button
        type="button"
        class="min-h-[32px] rounded-full px-3 py-1 text-xs font-medium transition-colors"
        :class="
          selectedTeamId === null
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-400 dark:hover:bg-surface-700'
        "
        @click="selectedTeamId = null"
      >
        {{ t('shift.myShift.teamFilter.all') }}
      </button>
      <button
        v-for="team in teamOptions"
        :key="team.id"
        type="button"
        class="min-h-[32px] rounded-full px-3 py-1 text-xs font-medium transition-colors"
        :class="
          selectedTeamId === team.id
            ? 'bg-primary text-white'
            : 'bg-surface-100 text-surface-600 hover:bg-surface-200 dark:bg-surface-800 dark:text-surface-400 dark:hover:bg-surface-700'
        "
        @click="selectedTeamId = team.id"
      >
        {{ team.name }}
      </button>
    </div>

    <PageLoading v-if="loading" size="40px" />

    <template v-else>
      <!-- 空状態 -->
      <DashboardEmptyState
        v-if="confirmedSlots.length === 0"
        icon="pi-calendar"
        :message="t('shift.empty.noShifts')"
      />

      <div v-else class="flex flex-col gap-4 sm:flex-row sm:items-start">
        <!-- ===== 月次カレンダー ===== -->
        <div
          v-if="viewMode === 'monthly'"
          class="w-full sm:flex-1"
        >
          <!-- 曜日ヘッダー -->
          <div class="mb-1 grid grid-cols-7 text-center">
            <div
              v-for="dow in DOW_KEYS"
              :key="dow"
              class="py-1 text-xs font-medium"
              :class="dow === '土' ? 'text-blue-500' : dow === '日' ? 'text-red-500' : 'text-surface-500 dark:text-surface-400'"
            >
              {{ dow }}
            </div>
          </div>

          <!-- 日付グリッド -->
          <div class="grid grid-cols-7 gap-px rounded-xl overflow-hidden border border-surface-200 dark:border-surface-700">
            <div
              v-for="(day, idx) in calendarDays"
              :key="idx"
              class="min-h-[64px] sm:min-h-[80px] p-1 bg-surface-0 dark:bg-surface-900 transition-colors"
              :class="[
                day ? 'cursor-pointer hover:bg-surface-50 dark:hover:bg-surface-800' : 'opacity-0 pointer-events-none',
                day && dateKey(day) === todayKey() ? 'ring-2 ring-inset ring-primary' : '',
                day && selectedDate === dateKey(day) ? 'bg-primary/10 dark:bg-primary/20' : '',
              ]"
              @click="day && selectDate(dateKey(day))"
            >
              <template v-if="day">
                <!-- 日付番号 -->
                <div class="flex items-center justify-between">
                  <span
                    class="flex h-7 w-7 items-center justify-center rounded-full text-xs font-semibold"
                    :class="
                      dateKey(day) === todayKey()
                        ? 'bg-primary text-white'
                        : (day.getDay() === 6 ? 'text-blue-500' : day.getDay() === 0 ? 'text-red-500' : 'text-surface-700 dark:text-surface-300')
                    "
                  >
                    {{ day.getDate() }}
                  </span>
                  <!-- シフト件数バッジ -->
                  <span
                    v-if="(slotsByDate.get(dateKey(day)) ?? []).length > 0"
                    class="flex h-5 w-5 items-center justify-center rounded-full bg-primary text-[10px] font-bold text-white"
                  >
                    {{ (slotsByDate.get(dateKey(day)) ?? []).length }}
                  </span>
                </div>
                <!-- シフト時間帯（最初の1件のみ表示） -->
                <div
                  v-if="(slotsByDate.get(dateKey(day)) ?? []).length > 0"
                  class="mt-1 hidden sm:block"
                >
                  <span class="block truncate rounded bg-primary/10 px-1 py-0.5 text-[10px] text-primary dark:bg-primary/20 dark:text-primary-300">
                    {{ formatTime(slotsByDate.get(dateKey(day))![0]!.startTime) }}
                    〜
                    {{ formatTime(slotsByDate.get(dateKey(day))![0]!.endTime) }}
                  </span>
                  <span
                    v-if="(slotsByDate.get(dateKey(day)) ?? []).length > 1"
                    class="mt-0.5 block text-[9px] text-surface-400"
                  >
                    +{{ (slotsByDate.get(dateKey(day)) ?? []).length - 1 }}
                  </span>
                </div>
              </template>
            </div>
          </div>
        </div>

        <!-- ===== 週次リストビュー ===== -->
        <div
          v-else
          class="w-full sm:flex-1 flex flex-col gap-3"
        >
          <SectionCard
            v-for="day in weekDates"
            :key="dateKey(day)"
            class="overflow-hidden"
          >
            <!-- 日付ヘッダー -->
            <button
              type="button"
              class="flex w-full items-center gap-3 px-4 py-3 transition-colors hover:bg-surface-50 dark:hover:bg-surface-800"
              @click="selectDate(dateKey(day))"
            >
              <span
                class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-sm font-bold"
                :class="
                  dateKey(day) === todayKey()
                    ? 'bg-primary text-white'
                    : (day.getDay() === 6 ? 'bg-blue-100 text-blue-600 dark:bg-blue-900 dark:text-blue-300' : day.getDay() === 0 ? 'bg-red-100 text-red-600 dark:bg-red-900 dark:text-red-300' : 'bg-surface-100 text-surface-700 dark:bg-surface-800 dark:text-surface-300')
                "
              >
                {{ day.getDate() }}
              </span>
              <div class="min-w-0 flex-1 text-left">
                <span class="text-sm font-medium text-surface-700 dark:text-surface-300">
                  {{ dayjs.tz(day, userTimezone).format('M月D日 (ddd)') }}
                </span>
                <span
                  v-if="(slotsByDate.get(dateKey(day)) ?? []).length > 0"
                  class="ml-2 text-xs text-surface-400"
                >
                  {{ (slotsByDate.get(dateKey(day)) ?? []).length }} {{ t('shift.myShift.confirmedTitle') }}
                </span>
              </div>
              <span
                v-if="(slotsByDate.get(dateKey(day)) ?? []).length > 0"
                class="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-primary text-xs font-bold text-white"
              >
                {{ (slotsByDate.get(dateKey(day)) ?? []).length }}
              </span>
            </button>

            <!-- シフト一覧（展開時） -->
            <Transition name="slide-down">
              <div
                v-if="selectedDate === dateKey(day) && (slotsByDate.get(dateKey(day)) ?? []).length > 0"
                class="border-t border-surface-200 dark:border-surface-700 divide-y divide-surface-100 dark:divide-surface-800"
              >
                <div
                  v-for="slot in slotsByDate.get(dateKey(day))"
                  :key="slot.slotId"
                  class="px-4 py-3"
                >
                  <div class="flex items-start justify-between gap-3">
                    <div class="min-w-0 flex-1">
                      <div class="flex flex-wrap items-center gap-1.5 mb-1">
                        <Tag :value="slot.teamName" severity="secondary" class="text-xs" />
                        <span
                          v-if="slot.positionName"
                          class="rounded bg-surface-100 dark:bg-surface-800 px-1.5 py-0.5 text-xs text-surface-600 dark:text-surface-400"
                        >
                          {{ slot.positionName }}
                        </span>
                      </div>
                      <p class="text-sm font-medium text-surface-800 dark:text-surface-200">
                        {{ formatTime(slot.startTime) }} 〜 {{ formatTime(slot.endTime) }}
                      </p>
                      <p class="mt-0.5 text-xs text-surface-500 dark:text-surface-400">
                        {{ slot.scheduleName }}
                      </p>
                    </div>
                    <Button
                      :label="t('shift.swap.create')"
                      size="small"
                      outlined
                      severity="secondary"
                      class="shrink-0"
                      @click="openSwapDialog(slot)"
                    />
                  </div>
                </div>
              </div>
            </Transition>
          </SectionCard>
        </div>

        <!-- ===== 詳細パネル（月次ビュー + 日付選択時） ===== -->
        <Transition name="slide-panel">
          <SectionCard
            v-if="viewMode === 'monthly' && selectedDate"
            class="w-full sm:w-80 shrink-0"
          >
            <div class="flex items-center justify-between border-b border-surface-200 dark:border-surface-700 px-4 py-3">
              <h3 class="text-sm font-semibold text-surface-800 dark:text-surface-200">
                {{ selectedDateFormatted }}
              </h3>
              <button
                type="button"
                class="flex h-8 w-8 items-center justify-center rounded-full text-surface-400 transition-colors hover:bg-surface-100 dark:hover:bg-surface-800"
                @click="selectedDate = null"
              >
                <i class="pi pi-times text-xs" />
              </button>
            </div>

            <!-- シフト詳細一覧 -->
            <div class="p-3">
              <div
                v-if="selectedDateSlots.length === 0"
                class="py-6 text-center text-sm text-surface-400"
              >
                {{ t('shift.myShift.slot.noSlot') }}
              </div>
              <div v-else class="flex flex-col gap-3">
                <div
                  v-for="slot in selectedDateSlots"
                  :key="slot.slotId"
                  class="rounded-lg border border-surface-100 dark:border-surface-800 bg-surface-50 dark:bg-surface-800 p-3"
                >
                  <!-- チームバッジ + ポジション -->
                  <div class="mb-2 flex flex-wrap items-center gap-1.5">
                    <Tag :value="slot.teamName" severity="secondary" class="text-xs" />
                    <span
                      v-if="slot.positionName"
                      class="rounded bg-surface-200 dark:bg-surface-700 px-1.5 py-0.5 text-xs text-surface-600 dark:text-surface-400"
                    >
                      {{ slot.positionName }}
                    </span>
                  </div>

                  <!-- 時刻 -->
                  <p class="text-sm font-semibold text-surface-800 dark:text-surface-200">
                    {{ formatTime(slot.startTime) }} 〜 {{ formatTime(slot.endTime) }}
                  </p>

                  <!-- スケジュール名 -->
                  <p class="mt-0.5 text-xs text-surface-500 dark:text-surface-400">
                    {{ slot.scheduleName }}
                  </p>

                  <!-- 交代依頼ボタン -->
                  <div class="mt-3">
                    <Button
                      :label="t('shift.swap.create')"
                      size="small"
                      outlined
                      severity="secondary"
                      class="w-full"
                      @click="openSwapDialog(slot)"
                    />
                  </div>
                </div>
              </div>
            </div>
          </SectionCard>
        </Transition>
      </div>
    </template>

    <!-- 交代依頼ダイアログ -->
    <ShiftSwapRequestFormDialog
      v-model:visible="swapDialogVisible"
      :slot-id="swapSlotId"
      :slot-date="swapSlotDate"
      :schedule-id="swapScheduleId"
      :team-id="swapTeamId"
      @submitted="onSwapSubmitted"
    />

    <!-- 使い方説明モーダル -->
    <MyShiftGuideModal v-model:visible="showGuide" />
  </div>
</template>

<style scoped>
.slide-down-enter-active,
.slide-down-leave-active {
  transition:
    max-height 0.25s ease,
    opacity 0.2s ease;
  overflow: hidden;
  max-height: 600px;
}
.slide-down-enter-from,
.slide-down-leave-to {
  max-height: 0;
  opacity: 0;
}

.slide-panel-enter-active,
.slide-panel-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.slide-panel-enter-from,
.slide-panel-leave-to {
  opacity: 0;
  transform: translateX(16px);
}
</style>
