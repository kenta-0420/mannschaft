<script setup lang="ts">
/**
 * F03.4.4 マトリックスUI（機能H）。
 *
 * 縦=日付＋予約対象（ライン）／横=時間（30分）のマトリックス。写経元 SlotGridPicker.vue
 * （単日/週切替・生成型参照・slotSelected emit 契約）を発展させ、axis=LINE・レンジ呼び
 * （週=月曜起点・7日分を1リクエスト）・メニューフィルターを追加する。
 *
 * セルクリック→予約のフロー:
 *   - 30分セル（span=1・AVAILABLE）: GroupBookingDialog（メニュー選択→連続枠プレビュー→
 *     POST /reservation-groups or 単枠フロー）を開く。
 *   - 長尺手動枠（span>1・colspan跨ぎ描画）: 単枠予約フローへ（`slotSelected` を emit し、
 *     親 TeamReservationsPanel が既存 ReservationForm ダイアログを開く。SlotGridPicker と同一パターン）。
 */
import dayjs from 'dayjs'
import type { ReservationLineResponse } from '~/types/reservation'
import type { components } from '~/types/generated'
import {
  buildTimeHeader,
  alignRowToHeader,
  computeStartableIndices,
  isPastCell,
  mondayOffsetDays,
  type MatrixCellInput,
  type RowSlot,
} from '~/utils/reservationMatrix'
import type { GroupBookingContext, LineOption } from '~/components/reservation/GroupBookingDialog.vue'

type GridColumnDto = components['schemas']['GridColumnDto']
type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']

const props = defineProps<{
  teamId: string
  /** 管理者（ADMIN）か否か。空状態の文言・管理CTAの出し分けに使う。 */
  isAdmin: boolean
}>()

// 写経元 SlotGridPicker と同一シグネチャで emit（長尺手動枠クリック時のみ・単枠フロー）
const emit = defineEmits<{
  slotSelected: [slotId: number, lineId: number, lineName: string, date: string, startTime: string, endTime: string]
  manageLines: []
  /** グループ/単枠 予約確定成功。親（TeamReservationsPanel）が一覧等を再読込する。 */
  reserved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const { userTimezone } = useDatetime()

interface DayGrid { date: string; columns: GridColumnDto[] }
interface MatrixRowVM {
  date: string
  column: GridColumnDto
  dateLabel: string
  columnLabel: string
  aligned: RowSlot[]
  /** メニューフィルター有効時のみ非 null。起点になり得るヘッダ列インデックス集合。 */
  startable: Set<number> | null
}

const lines = ref<LineOption[]>([])
const menus = ref<ReservationMenuResponse[]>([])
const days = ref<DayGrid[]>([])
const requiredCellCount = ref<number | null>(null)
const filterMenuId = ref<string | null>(null)
const weekStart = ref('')
const loading = ref(false)
const errorMsg = ref('')

const todayStr = ref('')
const nowMinutes = ref(0)

const dialogVisible = ref(false)
const dialogContext = ref<GroupBookingContext | null>(null)

const menuFilterOptions = computed(() => [
  { label: t('reservation.matrix.menu_filter_all'), value: null as string | null },
  ...menus.value
    .filter(m => m.isActive !== false)
    .map(m => ({ label: m.name ?? '', value: m.id ?? null })),
])

const hasLines = computed(() => lines.value.length > 0)

function columnLabel(col: GridColumnDto): string {
  return col.lineId == null ? t('reservation.grid.column.common') : (col.lineName ?? '')
}

function dayLabel(date: string): string {
  return date ? dayjs(date).format('YYYY/MM/DD (ddd)') : ''
}

const allCells = computed<MatrixCellInput[]>(() => {
  const cells: MatrixCellInput[] = []
  for (const day of days.value) {
    for (const col of day.columns ?? []) {
      for (const c of col.cells ?? []) cells.push(c)
    }
  }
  return cells
})

const header = computed(() => buildTimeHeader(allCells.value))

const matrixRows = computed<MatrixRowVM[]>(() => {
  const rows: MatrixRowVM[] = []
  for (const day of days.value) {
    for (const col of day.columns ?? []) {
      const aligned = alignRowToHeader(col.cells ?? [], header.value)
      const startable = requiredCellCount.value
        ? computeStartableIndices(aligned, requiredCellCount.value)
        : null
      rows.push({
        date: day.date,
        column: col,
        dateLabel: dayLabel(day.date),
        columnLabel: columnLabel(col),
        aligned,
        startable,
      })
    }
  }
  return rows
})

function weekRangeLabel(): string {
  if (!weekStart.value) return ''
  const end = dayjs(weekStart.value).add(6, 'day')
  return `${dayjs(weekStart.value).format('YYYY/MM/DD')} - ${end.format('YYYY/MM/DD')}`
}

function prevWeek() {
  weekStart.value = dayjs(weekStart.value).subtract(7, 'day').format('YYYY-MM-DD')
}
function nextWeek() {
  weekStart.value = dayjs(weekStart.value).add(7, 'day').format('YYYY-MM-DD')
}
function thisWeek() {
  const today = dayjs().tz(userTimezone.value)
  weekStart.value = today.subtract(mondayOffsetDays(today.day()), 'day').format('YYYY-MM-DD')
}

function refreshClock() {
  const now = dayjs().tz(userTimezone.value)
  todayStr.value = now.format('YYYY-MM-DD')
  nowMinutes.value = now.hour() * 60 + now.minute()
}

async function loadLines() {
  const res = await reservationApi.getLines(props.teamId)
  lines.value = (res.data as ReservationLineResponse[])
    .filter(l => l.meta?.isActive)
    .map(l => ({ id: l.id ?? 0, name: l.meta?.name ?? '' }))
}

async function loadMenus() {
  const res = await reservationApi.getMenus(props.teamId)
  menus.value = (res.data ?? []).filter(m => m.isActive !== false)
}

/**
 * グリッド取得。`silent: true` は KeepAlive 復帰時のサイレント再取得用で、loading フラグを
 * 立てない（skeleton へ切り替わらない＝表示中のマトリックスを保持したまま裏でデータだけ更新する）。
 */
async function loadGrid(opts?: { silent?: boolean }) {
  if (!weekStart.value) return
  if (!opts?.silent) loading.value = true
  errorMsg.value = ''
  refreshClock()
  try {
    const from = weekStart.value
    const to = dayjs(weekStart.value).add(6, 'day').format('YYYY-MM-DD')
    const res = await reservationApi.getSlotGrid(props.teamId, {
      from,
      to,
      axis: 'LINE',
      menuId: filterMenuId.value ?? undefined,
    })
    days.value = (res.data.days ?? []).map(d => ({ date: d.date ?? '', columns: d.columns ?? [] }))
    requiredCellCount.value = res.data.meta?.requiredCellCount ?? null
  }
  catch {
    days.value = []
    requiredCellCount.value = null
    errorMsg.value = t('reservation.grid.load_error')
  }
  finally {
    if (!opts?.silent) loading.value = false
  }
}

/**
 * 予約に使うライン（予約対象）を解決する。列に lineId が定まっていればそれを使い、
 * 共通枠列（lineId=null）はチームの有効ライン先頭にフォールバックする
 * （写経元 SlotGridPicker.resolveLine と同一方針）。
 */
function resolveLine(column: GridColumnDto): LineOption {
  if (column.lineId != null) {
    return { id: column.lineId, name: column.lineName ?? '' }
  }
  const fallback = lines.value[0]
  return { id: fallback?.id ?? 0, name: fallback?.name ?? '' }
}

function isCellDisabled(row: MatrixRowVM, headerIndex: number): boolean {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return true
  if (slot.cell.state !== 'AVAILABLE') return true
  if (isPastCell(row.date, slot.cell.startTime, todayStr.value, nowMinutes.value)) return true
  if (slot.span === 1 && row.startable && !row.startable.has(headerIndex)) return true
  return false
}

function cellStateClass(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  const disabled = isCellDisabled(row, headerIndex)
  const state = slot?.kind === 'cell' ? slot.cell.state : undefined
  if (disabled) {
    if (state === 'AVAILABLE') {
      // メニューフィルターで起点不可 or 過去セル: 空きはあるが選べない
      return 'cursor-not-allowed border-surface-100 bg-surface-50 text-surface-300 opacity-60 dark:border-surface-600 dark:bg-surface-800'
    }
    switch (state) {
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
  return 'cursor-pointer border-surface-200 text-green-700 hover:border-primary hover:bg-primary/5 dark:border-surface-600 dark:text-green-400'
}

function cellLabel(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind === 'empty') return '—'
  if (slot.kind === 'covered') return ''
  switch (slot.cell.state) {
    case 'AVAILABLE': return t('reservation.grid.state.available')
    case 'BOOKED': return t('reservation.grid.state.booked')
    case 'CLOSED': return t('reservation.grid.state.closed')
    case 'UNAVAILABLE': return t('reservation.grid.state.unavailable')
    default: return ''
  }
}

function cellAriaLabel(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return ''
  return `${row.dateLabel} ${header.value[headerIndex]?.label ?? ''} ${row.columnLabel} ${cellLabel(row, headerIndex)}`
}

function onCellClick(row: MatrixRowVM, headerIndex: number) {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return
  if (isCellDisabled(row, headerIndex)) return

  if (slot.span > 1) {
    // 長尺手動枠: 既存の単枠予約フローへ（親の ReservationForm）
    const line = resolveLine(row.column)
    emit(
      'slotSelected',
      slot.cell.slotId ?? 0,
      line.id,
      line.name,
      row.date,
      slot.cell.startTime ?? '',
      slot.cell.endTime ?? '',
    )
    return
  }

  dialogContext.value = {
    date: row.date,
    columnLineId: row.column.lineId ?? null,
    columnLineName: row.column.lineName ?? null,
    rowSlots: row.aligned,
    startIndex: headerIndex,
    header: header.value,
    preselectedMenuId: filterMenuId.value,
    preselectedRequiredCellCount: requiredCellCount.value,
  }
  dialogVisible.value = true
}

function onDialogReserved() {
  loadGrid()
  emit('reserved')
}

function gridStyle(headerLength: number): string {
  return `grid-template-columns: minmax(7rem, auto) repeat(${headerLength}, minmax(3.5rem, 1fr));`
}

// loadGrid が opts 引数を持つため、watch コールバックの (newVal, oldVal) が誤って渡らないようラップする
watch([weekStart, filterMenuId], () => loadGrid())

onMounted(async () => {
  thisWeek()
  await Promise.all([loadLines(), loadMenus()])
  await loadGrid()
})

// KeepAlive 配下（TeamReservationsPanel の表示切替）での復帰時にサイレント再取得し、
// 表示保持（チラつきなし）とデータ鮮度を両立する。onActivated は初回 mount 直後にも
// 1回発火するため、onMounted 経路との二重fetchをフラグでガードする。
let initialActivationDone = false
onActivated(() => {
  if (!initialActivationDone) {
    initialActivationDone = true
    return
  }
  void loadGrid({ silent: true })
})

// 予約直後に親から再読込させるための公開メソッド（既存パターン踏襲・defineExpose({ refresh })）
defineExpose({ refresh: () => loadGrid() })
</script>

<template>
  <div class="space-y-4">
    <!-- ツールバー: メニューフィルター・週ナビ -->
    <div class="grid grid-cols-1 gap-3 md:grid-cols-2">
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.matrix.menu_filter') }}</label>
        <Select
          v-model="filterMenuId"
          :options="menuFilterOptions"
          option-label="label"
          option-value="value"
          class="w-full"
        />
      </div>
      <div class="flex items-end justify-start gap-2 md:justify-end">
        <Button icon="pi pi-angle-left" text rounded @click="prevWeek" />
        <Button :label="t('reservation.grid.view.week') + ' ' + weekRangeLabel()" text size="small" @click="thisWeek" />
        <Button icon="pi pi-angle-right" text rounded @click="nextWeek" />
      </div>
    </div>

    <!-- 凡例 -->
    <div class="rounded-lg bg-surface-50 p-3 text-xs text-surface-600 dark:bg-surface-800 dark:text-surface-300">
      <div class="flex flex-wrap gap-x-4 gap-y-1">
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm border border-surface-200 text-green-600 dark:border-surface-600"><i class="pi pi-check text-[8px]" /></span>{{ t('reservation.matrix.legend_available') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-100 dark:bg-surface-700" />{{ t('reservation.matrix.legend_booked') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-50 [background-image:repeating-linear-gradient(45deg,transparent,transparent_2px,rgba(0,0,0,0.12)_2px,rgba(0,0,0,0.12)_4px)] dark:bg-surface-800" />{{ t('reservation.matrix.legend_unavailable') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm bg-surface-50 dark:bg-surface-800" />{{ t('reservation.matrix.legend_closed') }}</span>
        <span class="flex items-center gap-1"><span class="inline-block size-3 rounded-sm border border-dashed border-surface-300 dark:border-surface-600" />{{ t('reservation.matrix.legend_none') }}</span>
      </div>
    </div>

    <!-- 予約対象ゼロ: セットアップ導線（管理者のみCTA） -->
    <DashboardEmptyState
      v-if="!loading && !hasLines"
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

    <!-- ローディング -->
    <div v-else-if="loading" class="space-y-2">
      <Skeleton v-for="i in 5" :key="i" height="2.5rem" />
    </div>

    <!-- 取得失敗 -->
    <Message v-else-if="errorMsg" severity="error" :closable="false">
      {{ errorMsg }}
    </Message>

    <!-- マトリックス本体（縦横スクロール・overscroll-contain・時間ヘッダ行 sticky top・行ヘッダ列 sticky left）。
         縦スクロールを本コンテナ内に閉じ込める（max-h + overflow-auto）ことで sticky top を確実に効かせる。 -->
    <div v-else class="max-h-[65vh] overflow-auto overscroll-contain">
      <div class="inline-grid min-w-full gap-1" :style="gridStyle(header.length)">
        <!-- ヘッダー行: 左上コーナー（両軸 sticky・最前面）+ 時間見出し（sticky top） -->
        <div class="sticky left-0 top-0 z-20 flex items-center justify-center bg-surface-0 p-2 text-xs font-semibold text-surface-500 dark:bg-surface-900">
          {{ t('reservation.matrix.date_line_header') }}
        </div>
        <div
          v-for="(h, hi) in header"
          :key="`h-${hi}`"
          class="sticky top-0 z-10 flex items-center justify-center rounded-md bg-surface-100 p-2 text-center text-xs font-semibold text-surface-700 dark:bg-surface-800 dark:text-surface-200"
        >
          {{ h.label }}
        </div>

        <!-- 本体行: 日付×予約対象 -->
        <template v-for="(row, ri) in matrixRows" :key="`r-${ri}`">
          <div class="sticky left-0 z-10 flex flex-col items-center justify-center bg-surface-0 p-2 text-center text-[11px] font-medium text-surface-600 dark:bg-surface-900 dark:text-surface-300">
            <span>{{ row.dateLabel }}</span>
            <span class="text-surface-400">{{ row.columnLabel }}</span>
          </div>
          <template v-for="(slot, ci) in row.aligned" :key="`c-${ri}-${ci}`">
            <button
              v-if="slot.kind !== 'covered'"
              type="button"
              class="rounded-md border p-2 text-center text-[11px] transition-all"
              :style="slot.kind === 'cell' && slot.span > 1 ? `grid-column: span ${slot.span};` : undefined"
              :class="cellStateClass(row, ci)"
              :disabled="isCellDisabled(row, ci)"
              :aria-label="cellAriaLabel(row, ci)"
              :title="row.startable && slot.kind === 'cell' && slot.span === 1 && !row.startable.has(ci) && slot.cell.state === 'AVAILABLE' ? t('reservation.matrix.cannot_start_here', { menu: menuFilterOptions.find(o => o.value === filterMenuId)?.label ?? '' }) : undefined"
              @click="onCellClick(row, ci)"
            >
              {{ cellLabel(row, ci) }}
            </button>
          </template>
        </template>
      </div>
    </div>

    <GroupBookingDialog
      v-model:visible="dialogVisible"
      :team-id="props.teamId"
      :lines="lines"
      :menus="menus"
      :context="dialogContext"
      @reserved="onDialogReserved"
    />
  </div>
</template>
