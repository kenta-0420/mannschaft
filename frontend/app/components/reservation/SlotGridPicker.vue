<script setup lang="ts">
import dayjs from 'dayjs'
import type { ReservationLineResponse } from '~/types/reservation'
import type { components } from '~/types/generated'

// === 生成型（真実のソース = openapi-typescript）===
type GridColumnDto = components['schemas']['GridColumnDto']
type GridCellDto = components['schemas']['GridCellDto']
/** セル状態は生成型の enum 値をそのまま使う。 */
type CellState = NonNullable<GridCellDto['state']>

const props = defineProps<{
  teamId: string
  /** 管理者（ADMIN）か否か。空状態の文言・管理CTAの出し分けに使う。 */
  isAdmin: boolean
}>()

// 既存 SlotPicker と同一シグネチャで emit（TeamReservationsPanel の onSlotSelected を共有）
const emit = defineEmits<{
  slotSelected: [slotId: number, lineId: number, lineName: string, date: string, startTime: string, endTime: string]
  /** 「予約対象の管理」タブへの誘導。親が activeTab を切り替える。 */
  manageLines: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const { userTimezone } = useDatetime()

type ViewMode = 'single' | 'week'

interface LineOption { id: number; name: string }
interface DayGrid { date: string; columns: GridColumnDto[] }
interface TimeSlot { key: string; startTime: string; endTime: string; label: string }
interface RenderCell { cell: GridCellDto | undefined; column: GridColumnDto; date: string }
interface RenderRow { label: string; cells: RenderCell[] }
interface RenderDay { date: string; header: string[]; rows: RenderRow[]; empty: boolean }
interface StaffOption { staffUserId: number; staffName: string }

const lines = ref<LineOption[]>([])
const days = ref<DayGrid[]>([])
const selectedDate = ref<Date | null>(new Date())
const selectedStaffIds = ref<number[]>([])
const viewMode = ref<ViewMode>('single')
/** 軸の向き。true=時間を縦（既定）・false=時間を横。 */
const axisTimeRows = ref(true)
const loading = ref(false)
const errorMsg = ref('')

const viewOptions = computed(() => [
  { label: t('reservation.grid.view.single'), value: 'single' as ViewMode },
  { label: t('reservation.grid.view.week'), value: 'week' as ViewMode },
])
const axisOptions = computed(() => [
  { label: t('reservation.grid.axis.time_rows'), value: true },
  { label: t('reservation.grid.axis.time_cols'), value: false },
])

/** グリッド全体から予約対象（スタッフ列・staffUserId!=null）の一覧を導出する。 */
const staffOptions = computed<StaffOption[]>(() => {
  const map = new Map<number, string>()
  for (const day of days.value) {
    for (const col of day.columns) {
      if (col.staffUserId != null && !map.has(col.staffUserId)) {
        map.set(col.staffUserId, col.staffName ?? String(col.staffUserId))
      }
    }
  }
  return [...map.entries()].map(([staffUserId, staffName]) => ({ staffUserId, staffName }))
})

/** 予約対象（Line）が1件でも存在するか。空状態を「対象ゼロ」か「枠ゼロ」で出し分ける。 */
const hasLines = computed(() => lines.value.length > 0)

function fmt(time: string): string {
  // "10:00:00" / "10:00" いずれも HH:mm へ整形
  return time.length >= 5 ? time.slice(0, 5) : time
}

function colLabel(col: GridColumnDto): string {
  if (col.staffUserId == null) return t('reservation.grid.column.common')
  return col.staffName ?? String(col.staffUserId)
}

/** 選択された予約対象で列を絞り込む（共通列は常に残す）。未選択なら全列。 */
function filterColumns(columns: GridColumnDto[]): GridColumnDto[] {
  if (selectedStaffIds.value.length === 0) return columns
  return columns.filter(
    c => c.staffUserId == null || selectedStaffIds.value.includes(c.staffUserId),
  )
}

/** 列群から時間帯（行/列見出し）の和集合を作り、開始時刻でソートする。 */
function buildTimeSlots(columns: GridColumnDto[]): TimeSlot[] {
  const map = new Map<string, TimeSlot>()
  for (const col of columns) {
    for (const cell of col.cells ?? []) {
      const startTime = cell.startTime ?? ''
      const endTime = cell.endTime ?? ''
      const key = `${startTime}-${endTime}`
      if (!map.has(key)) {
        map.set(key, { key, startTime, endTime, label: `${fmt(startTime)}-${fmt(endTime)}` })
      }
    }
  }
  return [...map.values()].sort((a, b) => a.startTime.localeCompare(b.startTime))
}

const renderDays = computed<RenderDay[]>(() =>
  days.value.map((day) => {
    const cols = filterColumns(day.columns)
    const slots = buildTimeSlots(cols)
    const colMaps = cols.map((c) => {
      const m = new Map<string, GridCellDto>()
      for (const cell of c.cells ?? []) {
        m.set(`${cell.startTime ?? ''}-${cell.endTime ?? ''}`, cell)
      }
      return m
    })
    const empty = cols.length === 0 || slots.length === 0

    if (axisTimeRows.value) {
      // 縦=時間 / 横=予約対象
      return {
        date: day.date,
        empty,
        header: cols.map(colLabel),
        rows: slots.map(slot => ({
          label: slot.label,
          cells: cols.map((column, ci) => ({
            cell: colMaps[ci]!.get(slot.key),
            column,
            date: day.date,
          })),
        })),
      }
    }
    // 縦=予約対象 / 横=時間
    return {
      date: day.date,
      empty,
      header: slots.map(slot => slot.label),
      rows: cols.map((column, ci) => ({
        label: colLabel(column),
        cells: slots.map(slot => ({
          cell: colMaps[ci]!.get(slot.key),
          column,
          date: day.date,
        })),
      })),
    }
  }),
)

function cellStateClass(state: CellState | undefined): string {
  switch (state) {
    case 'AVAILABLE':
      return 'cursor-pointer border-surface-200 text-green-700 hover:border-primary hover:bg-primary/5 dark:border-surface-600 dark:text-green-400'
    case 'BOOKED':
      return 'cursor-not-allowed border-surface-100 bg-surface-100 text-surface-500 dark:border-surface-600 dark:bg-surface-700'
    case 'CLOSED':
      return 'cursor-not-allowed border-surface-100 bg-surface-50 text-surface-400 dark:border-surface-600 dark:bg-surface-800'
    case 'UNAVAILABLE':
      return 'cursor-not-allowed border-surface-100 bg-surface-50 text-surface-400 [background-image:repeating-linear-gradient(45deg,transparent,transparent_5px,rgba(0,0,0,0.06)_5px,rgba(0,0,0,0.06)_10px)] dark:border-surface-600 dark:[background-image:repeating-linear-gradient(45deg,transparent,transparent_5px,rgba(255,255,255,0.05)_5px,rgba(255,255,255,0.05)_10px)]'
    default:
      return 'border-transparent'
  }
}

function stateLabel(state: CellState | undefined): string {
  switch (state) {
    case 'AVAILABLE':
      return t('reservation.grid.state.available')
    case 'BOOKED':
      return t('reservation.grid.state.booked')
    case 'CLOSED':
      return t('reservation.grid.state.closed')
    case 'UNAVAILABLE':
      return t('reservation.grid.state.unavailable')
    default:
      return ''
  }
}

/**
 * 予約に使うライン（予約対象）を解決する。
 * 列の lineIds[0] を既定にし、無ければチームの有効ライン先頭にフォールバックする
 * （lineIds は「そのスタッフの推奨ライン」プリセットに過ぎず予約可能ラインを制限しない・§4.C C-8）。
 */
function resolveLine(column: GridColumnDto): LineOption {
  const presetId = column.lineIds?.[0]
  if (presetId != null) {
    const found = lines.value.find(l => l.id === presetId)
    return { id: presetId, name: found?.name ?? '' }
  }
  const fallback = lines.value[0]
  return { id: fallback?.id ?? 0, name: fallback?.name ?? '' }
}

function onCellClick(rc: RenderCell) {
  if (rc.cell?.state !== 'AVAILABLE') return
  const line = resolveLine(rc.column)
  emit(
    'slotSelected',
    rc.cell.slotId ?? 0,
    line.id,
    line.name,
    rc.date,
    rc.cell.startTime ?? '',
    rc.cell.endTime ?? '',
  )
}

async function loadLines() {
  const res = await reservationApi.getLines(props.teamId)
  lines.value = (res.data as ReservationLineResponse[])
    .filter(l => l.meta?.isActive)
    .map(l => ({ id: l.id ?? 0, name: l.meta?.name ?? '' }))
}

async function loadGrid() {
  if (!selectedDate.value) return
  loading.value = true
  errorMsg.value = ''
  try {
    const start = dayjs(selectedDate.value).tz(userTimezone.value)
    const dates = viewMode.value === 'week'
      ? Array.from({ length: 7 }, (_, i) => start.add(i, 'day').format('YYYY-MM-DD'))
      : [start.format('YYYY-MM-DD')]
    const results = await Promise.all(
      dates.map(date => reservationApi.getSlotGrid(props.teamId, { date })),
    )
    days.value = results.map((res, i) => ({ date: dates[i]!, columns: res.data.columns ?? [] }))
  }
  catch {
    days.value = []
    errorMsg.value = t('reservation.grid.load_error')
  }
  finally {
    loading.value = false
  }
}

function gridStyle(headerLength: number): string {
  return `grid-template-columns: minmax(4.5rem, auto) repeat(${headerLength}, minmax(4.5rem, 1fr));`
}

function dayLabel(date: string): string {
  return dayjs(date).format('YYYY/MM/DD (ddd)')
}

watch([selectedDate, viewMode], loadGrid)
onMounted(async () => {
  await loadLines()
  await loadGrid()
})
</script>

<template>
  <div class="space-y-4">
    <!-- 操作行: 日付・単日/週・軸切替・予約対象フィルタ -->
    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.date') }}</label>
        <DatePicker v-model="selectedDate" date-format="yy/mm/dd" class="w-full" show-icon />
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.grid.staff_filter.label') }}</label>
        <MultiSelect
          v-model="selectedStaffIds"
          :options="staffOptions"
          option-label="staffName"
          option-value="staffUserId"
          class="w-full"
          display="chip"
          :placeholder="t('reservation.grid.staff_filter.placeholder')"
        />
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
      <SelectButton
        v-model="viewMode"
        :options="viewOptions"
        option-label="label"
        option-value="value"
        :allow-empty="false"
        aria-labelledby="grid-view-mode"
      />
      <div class="flex items-center gap-2">
        <span class="text-xs text-surface-500">{{ t('reservation.grid.axis.label') }}</span>
        <SelectButton
          v-model="axisTimeRows"
          :options="axisOptions"
          option-label="label"
          option-value="value"
          :allow-empty="false"
        />
      </div>
    </div>

    <!-- 使い方の一言 + 色の凡例 -->
    <div class="rounded-lg bg-surface-50 p-3 text-xs text-surface-600 dark:bg-surface-800 dark:text-surface-300">
      <p class="flex items-start gap-1.5">
        <i class="pi pi-info-circle mt-0.5" />
        <span>{{ t('reservation.grid.help') }}</span>
      </p>
      <div class="mt-2 flex flex-wrap gap-x-4 gap-y-1">
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm border border-surface-200 text-green-600 dark:border-surface-600"><i class="pi pi-check text-[8px]" /></span>{{ t('reservation.grid.state.available') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-100 dark:bg-surface-700" />{{ t('reservation.grid.state.booked') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-50 dark:bg-surface-800" />{{ t('reservation.grid.state.closed') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-50 [background-image:repeating-linear-gradient(45deg,transparent,transparent_2px,rgba(0,0,0,0.12)_2px,rgba(0,0,0,0.12)_4px)] dark:bg-surface-800" />{{ t('reservation.grid.state.unavailable') }}</span>
      </div>
    </div>

    <!-- ローディング -->
    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 4" :key="i" height="3rem" />
    </div>

    <!-- 取得失敗 -->
    <Message v-else-if="errorMsg" severity="error" :closable="false">
      {{ errorMsg }}
    </Message>

    <!-- グリッド本体（単日=1日／週=7日）-->
    <div v-else class="space-y-6">
      <div v-for="day in renderDays" :key="day.date" class="space-y-2">
        <p v-if="viewMode === 'week'" class="text-sm font-semibold text-surface-700 dark:text-surface-200">
          {{ dayLabel(day.date) }}
        </p>

        <!-- 予約対象ゼロ: セットアップ導線（管理者のみCTA）-->
        <DashboardEmptyState
          v-if="day.empty && !hasLines"
          icon="pi pi-list"
          :message="isAdmin ? t('reservation.empty.book.admin_no_lines') : t('reservation.empty.book.member_no_lines')"
          :sub-message="isAdmin ? t('reservation.empty.book.admin_no_lines_hint') : t('reservation.empty.book.member_no_lines_hint')"
        >
          <template v-if="isAdmin" #action>
            <Button
              :label="t('reservation.button.go_to_line_manage')"
              icon="pi pi-arrow-right"
              icon-pos="right"
              size="small"
              @click="emit('manageLines')"
            />
          </template>
        </DashboardEmptyState>
        <!-- 予約対象あり・枠ゼロ: 枠追加導線（管理者のみCTA）-->
        <DashboardEmptyState
          v-else-if="day.empty"
          icon="pi pi-calendar-times"
          :message="isAdmin ? t('reservation.empty.book.admin_no_slots') : t('reservation.empty.book.member_no_slots')"
          :sub-message="isAdmin ? t('reservation.empty.book.admin_no_slots_hint') : t('reservation.empty.book.member_no_slots_hint')"
        >
          <template v-if="isAdmin" #action>
            <Button
              :label="t('reservation.button.manage_slots')"
              icon="pi pi-cog"
              size="small"
              @click="emit('manageLines')"
            />
          </template>
        </DashboardEmptyState>

        <div v-else class="overflow-x-auto">
          <div class="inline-grid min-w-full gap-1" :style="gridStyle(day.header.length)">
            <!-- ヘッダー行: 左上コーナー + 列見出し -->
            <div class="sticky left-0 z-10 flex items-center justify-center bg-surface-0 p-2 text-xs font-semibold text-surface-500 dark:bg-surface-900">
              {{ axisTimeRows ? t('reservation.grid.column.time') : t('reservation.grid.column.common') }}
            </div>
            <div
              v-for="(head, hi) in day.header"
              :key="`h-${hi}`"
              class="flex items-center justify-center rounded-md bg-surface-100 p-2 text-center text-xs font-semibold text-surface-700 dark:bg-surface-800 dark:text-surface-200"
            >
              {{ head }}
            </div>

            <!-- 本体行 -->
            <template v-for="(row, ri) in day.rows" :key="`r-${ri}`">
              <div class="sticky left-0 z-10 flex items-center justify-center bg-surface-0 p-2 text-center text-xs font-medium text-surface-600 dark:bg-surface-900 dark:text-surface-300">
                {{ row.label }}
              </div>
              <button
                v-for="(rc, ci) in row.cells"
                :key="`c-${ri}-${ci}`"
                type="button"
                class="rounded-md border p-2 text-center text-[11px] transition-all"
                :class="cellStateClass(rc.cell?.state)"
                :disabled="rc.cell?.state !== 'AVAILABLE'"
                :aria-label="stateLabel(rc.cell?.state)"
                @click="onCellClick(rc)"
              >
                <span v-if="rc.cell">{{ stateLabel(rc.cell.state) }}</span>
                <span v-else class="text-surface-300 dark:text-surface-600">—</span>
              </button>
            </template>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
