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
  resolveDragSelection,
  unavailableReasonOfSlot,
  type MatrixCellInput,
  type RowSlot,
  type HeaderSlot,
  GROUP_MAX_SIZE,
} from '~/utils/reservationMatrix'
import type { GroupBookingContext, LineOption } from '~/components/reservation/GroupBookingDialog.vue'
import ReservationWaitlistDialog, { type WaitlistDialogContext } from '~/components/reservation/ReservationWaitlistDialog.vue'

type GridColumnDto = components['schemas']['GridColumnDto']
type ReservationMenuResponse = components['schemas']['ReservationMenuResponse']

const props = defineProps<{
  teamId: string
  /** 予約枠の日付・現在時刻判定に使うチーム基準タイムゾーン。 */
  teamTimezone?: string
  /** 管理者（ADMIN）か否か。空状態の文言・管理CTAの出し分けに使う。 */
  isAdmin: boolean
}>()

// 写経元 SlotGridPicker と同一シグネチャで emit（長尺手動枠クリック時のみ・単枠フロー）
const emit = defineEmits<{
  slotSelected: [slotId: number, lineId: number, lineName: string, date: string, startTime: string, endTime: string]
  manageLines: []
  /**
   * 「予約対象はあるが表示中の週に枠が1件も無い」空状態の管理者CTA。
   * 予約対象ゼロ（manageLines）と違い、次の一手は**枠の作成**なので週間スケジュール管理へ誘導する。
   */
  manageSlots: []
  /** グループ/単枠 予約確定成功。親（TeamReservationsPanel）が一覧等を再読込する。 */
  reserved: []
  /** キャンセル待ちの登録/取消が成功した（W2-4-FE）。親は「自分のキャンセル待ち」一覧を再読込する。 */
  waitlistChanged: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const teamTimezone = computed(() => props.teamTimezone ?? 'Asia/Tokyo')
/** 静かなエラー記録（トーストは出さずバックエンドへ送信。WidgetAttendanceResults.vue 等と同一パターン）。 */
const { captureQuiet } = useErrorReport()

/** 呼称の動的差し込み（F03.4.5 §5.2）: 行ヘッダに使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

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

// === キャンセル待ち（waitlist・W2-4-FE）===
/** このチームで自分が WAITING 登録済みの slotId 集合（満席セルのラベル/ダイアログ初期状態に使う）。 */
const myWaitlistSlotIds = ref<Set<number>>(new Set())
const waitlistDialogVisible = ref(false)
const waitlistContext = ref<WaitlistDialogContext | null>(null)

const dialogVisible = ref(false)
const dialogContext = ref<GroupBookingContext | null>(null)

const menuFilterOptions = computed(() => [
  { label: t('reservation.matrix.menu_filter_all'), value: null as string | null },
  ...menus.value
    .filter(m => m.isActive !== false)
    .map(m => ({ label: m.name ?? '', value: m.id ?? null })),
])

const hasLines = computed(() => lines.value.length > 0)

/**
 * 表示中の週に枠が1件でもあるか。
 *
 * 「予約対象が無い」（`hasLines === false`）とは**別物**で、こちらは「予約対象はあるが、その週に
 * 枠が1件も無い」の判定に使う。旧表示 SlotPicker には両方の空状態があったが、マトリックスには
 * 枠ゼロ側が実装されておらず、既定がマトリックスになった時点で到達不能になっていた
 * （PR #2574 の旧表示撤去で恒久化）。ここで復活させる。
 */
const hasSlots = computed(() => allCells.value.length > 0)

function columnLabel(col: GridColumnDto): string {
  return col.lineId == null ? t('reservation.grid.column.common') : (col.lineName ?? '')
}

function dayLabel(date: string): string {
  return date ? dayjs.tz(date, teamTimezone.value).format('YYYY/MM/DD (ddd)') : ''
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

/**
 * 🔴teamId は slug（`WaitlistEntryResponse.teamId` は BE の数値 DB id）のため文字列比較できない
 * （検分で発覚した実バグの是正・2026-07-30）。`SlotMatrixPicker` は既にこのチームの枠一覧
 * （`allCells`）を読み込み済みのため、team id 解決を経由せず
 * 「slotId が読み込み済みの枠 id 集合に含まれるか」で絞り込む（team id 不要・厳密・最も安い）。
 */
const loadedSlotIds = computed<Set<number>>(() => {
  const ids = new Set<number>()
  for (const c of allCells.value) {
    if (c.slotId != null) ids.add(c.slotId)
  }
  return ids
})

const header = computed(() => buildTimeHeader(allCells.value))

const matrixRows = computed<MatrixRowVM[]>(() => {
  const rows: MatrixRowVM[] = []
  for (const day of days.value) {
    for (const col of day.columns ?? []) {
      const aligned = alignRowToHeader(col.cells ?? [], header.value)
      rows.push({
        date: day.date,
        column: col,
        dateLabel: dayLabel(day.date),
        columnLabel: columnLabel(col),
        aligned,
        startable: null,
      })
    }
  }
  // 日付行を跨ぐメニューでも、翌日00:00枠が同一lineかつ連続なら起点にできる。
  for (const row of rows) {
    const nextDate = dayjs.tz(row.date, teamTimezone.value).add(1, 'day').format('YYYY-MM-DD')
    const next = rows.find(candidate => candidate.date === nextDate
      && candidate.column.lineId === row.column.lineId)
    const continuation = next ? [...row.aligned, ...next.aligned.slice(0, GROUP_MAX_SIZE)] : row.aligned
    row.startable = requiredCellCount.value
      ? computeStartableIndices(continuation, requiredCellCount.value)
      : null
  }
  return rows
})

function continuationFor(row: MatrixRowVM): { slots: RowSlot[]; header: HeaderSlot[] } {
  const nextDate = dayjs.tz(row.date, teamTimezone.value).add(1, 'day').format('YYYY-MM-DD')
  const next = matrixRows.value.find(candidate => candidate.date === nextDate
    && candidate.column.lineId === row.column.lineId)
  if (!next) return { slots: row.aligned, header: header.value }
  const extra = next.aligned.slice(0, GROUP_MAX_SIZE)
  const extraHeader = extra.map((_, index) => ({
    minutes: (index * 30) + 1440,
    label: dayjs().startOf('day').add(index * 30, 'minute').format('HH:mm'),
  }))
  return { slots: [...row.aligned, ...extra], header: [...header.value, ...extraHeader] }
}

function weekRangeLabel(): string {
  if (!weekStart.value) return ''
  const start = dayjs.tz(weekStart.value, teamTimezone.value)
  const end = start.add(6, 'day')
  return `${start.format('YYYY/MM/DD')} - ${end.format('YYYY/MM/DD')}`
}

function prevWeek() {
  weekStart.value = dayjs.tz(weekStart.value, teamTimezone.value).subtract(7, 'day').format('YYYY-MM-DD')
}
function nextWeek() {
  weekStart.value = dayjs.tz(weekStart.value, teamTimezone.value).add(7, 'day').format('YYYY-MM-DD')
}
function thisWeek() {
  const today = dayjs().tz(teamTimezone.value)
  weekStart.value = today.subtract(mondayOffsetDays(today.day()), 'day').format('YYYY-MM-DD')
}

function refreshClock() {
  const now = dayjs().tz(teamTimezone.value)
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
    const to = dayjs.tz(weekStart.value, teamTimezone.value).add(6, 'day').format('YYYY-MM-DD')
    const res = await reservationApi.getSlotGrid(props.teamId, {
      from,
      to,
      menuId: filterMenuId.value ?? undefined,
    })
    days.value = (res.data.days ?? []).map((d) => {
      const date = d.date ?? ''
      return {
        date,
        columns: (d.columns ?? []).map(col => ({
          ...col,
          cells: (col.cells ?? []).map((c) => {
            const cell = c as typeof c & { slotDate?: string; endDate?: string }
            return { ...c, slotDate: cell.slotDate ?? date, endDate: cell.endDate ?? (cell.slotDate ?? date) }
          }),
        })),
      }
    })
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

/**
 * BOOKED（満席）セルは、過去枠でない限りキャンセル待ち導線として常にクリック可能にする（W2-4-FE）。
 * メニューフィルターの起点判定（startable）は「連続確保の起点になれるか」の判定であり、
 * キャンセル待ちには無関係のため対象外にする。
 */
function isCellDisabled(row: MatrixRowVM, headerIndex: number): boolean {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return true
  if (slot.cell.state === 'BOOKED') {
    // slotId 不明の BOOKED セルは押しても無反応（early return）になるため disabled にする（検分是正）。
    if (slot.cell.slotId == null) return true
    return isPastCell(slot.cell.slotDate ?? row.date, slot.cell.startTime, todayStr.value, nowMinutes.value)
  }
  if (slot.cell.state !== 'AVAILABLE') return true
  if (isPastCell(slot.cell.slotDate ?? row.date, slot.cell.startTime, todayStr.value, nowMinutes.value)) return true
  if (slot.span === 1 && row.startable && !row.startable.has(headerIndex)) return true
  return false
}

function cellStateClass(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  const disabled = isCellDisabled(row, headerIndex)
  const state = slot?.kind === 'cell' ? slot.cell.state : undefined

  // BOOKED はクリック可否に関わらず同じ配色（満席）のまま、クリック可能な場合のみポインタ+ホバーを付ける。
  if (state === 'BOOKED') {
    return disabled
      ? 'cursor-not-allowed border-surface-100 bg-surface-100 text-surface-500 dark:border-surface-600 dark:bg-surface-700'
      : 'cursor-pointer border-surface-100 bg-surface-100 text-surface-500 hover:border-primary dark:border-surface-600 dark:bg-surface-700'
  }
  if (disabled) {
    if (state === 'AVAILABLE') {
      // メニューフィルターで起点不可 or 過去セル: 空きはあるが選べない
      return 'cursor-not-allowed border-surface-100 bg-surface-50 text-surface-300 opacity-60 dark:border-surface-600 dark:bg-surface-800'
    }
    switch (state) {
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

/** UNAVAILABLE セルの事由ラベル（純関数 `unavailableReasonOfSlot` に委譲。§4.4）。 */
function unavailableReasonOf(row: MatrixRowVM, headerIndex: number): string | null {
  return unavailableReasonOfSlot(row.aligned[headerIndex])
}

function cellLabel(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind === 'empty') return '—'
  if (slot.kind === 'covered') return ''
  let label: string
  switch (slot.cell.state) {
    case 'AVAILABLE': label = t('reservation.grid.state.available'); break
    // BOOKED は自分が WAITING 登録済みなら「待機中」に切り替える（W2-4-FE）。
    case 'BOOKED':
      label = slot.cell.slotId != null && myWaitlistSlotIds.value.has(slot.cell.slotId)
        ? t('reservation.waitlist.registered_badge')
        : t('reservation.grid.state.booked')
      break
    case 'CLOSED': label = t('reservation.grid.state.closed'); break
    case 'UNAVAILABLE': {
      const reason = unavailableReasonOf(row, headerIndex)
      label = reason ? `× ${reason}` : t('reservation.grid.state.unavailable')
      break
    }
    default: return ''
  }
  const isNextDay = slot.cell.endDate && slot.cell.slotDate && slot.cell.endDate !== slot.cell.slotDate
  return isNextDay ? `${label} (${t('reservation.template.next_day_time', { time: slot.cell.endTime ?? '' })})` : label
}

/**
 * BOOKED セルの aria-label に「押すと何が起きるか」を追記する（検分是正・W2-4-FE）。
 * 押せない（slotId 不明）セルにはヒントを付けない。
 */
function waitlistAriaHint(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell' || slot.cell.state !== 'BOOKED' || slot.cell.slotId == null) return ''
  return myWaitlistSlotIds.value.has(slot.cell.slotId)
    ? t('reservation.waitlist.aria_hint_cancel')
    : t('reservation.waitlist.aria_hint_register')
}

function cellAriaLabel(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return ''
  const hint = waitlistAriaHint(row, headerIndex)
  const base = `${row.dateLabel} ${header.value[headerIndex]?.label ?? ''} ${row.columnLabel} ${cellLabel(row, headerIndex)}`
  return hint ? `${base}: ${hint}` : base
}

// === ドラッグ複数選択（機能H）===
//
// 同一行内で連続する AVAILABLE な30分セルを pointerdown→pointermove→pointerup でまとめて選び、
// 離した時点で既存の GroupBookingDialog（連続枠の一括予約）へ渡す。
//
// 【長尺枠（span>1）の解き方】
//   ドラッグ先のマスは「可視セルの通し番号」ではなく DOM の data-header-index（＝ヘッダ列
//   インデックス）から取る。長尺枠は grid-column: span N で複数列を1要素で覆うため、通し番号で
//   数えると跨ぎ枠の先で列がずれる。範囲解決の純関数 resolveDragSelection はヘッダ列基準で
//   走査し、長尺枠（および covered 列）を「連続の切れ目」として打ち切る。
//
// 【座標→セル特定】
//   document.elementFromPoint（ビューポート座標）で拾うため、コンテナのスクロール量や
//   sticky ヘッダーのオフセットを自前計算する必要がない。sticky ヘッダー/行見出しが
//   ポインタ下に来た場合は data-header-index を持つ祖先が無い（or 行が違う）ので、
//   直前の有効なフォーカスを保持する＝ヘッダーの下を通っても選択が壊れない。
//
// 【タッチ端末での衝突回避】
//   タッチはマトリックスの縦横パン（スクロール）に使う。ドラッグ選択を触ると衝突するため、
//   pointerType が 'mouse'/'pen' のときだけドラッグ選択を有効にし、タッチでは touch-action を
//   一切変更しない（＝スクロールは完全に従来どおり）。タッチ利用者の複数枠予約は、単発タップで
//   開く GroupBookingDialog の「＋30分延長」で従来どおり行える。
/** ドラッグ開始と判定する移動距離のしきい値（px）。これ未満で離せば単発クリック扱い。 */
const DRAG_THRESHOLD_PX = 8

interface DragAnchor { rowIndex: number; headerIndex: number; clientX: number; clientY: number }
const dragAnchor = ref<DragAnchor | null>(null)
const dragFocusIndex = ref<number | null>(null)
/** しきい値を超えて実際にドラッグ中か（未超過なら単発クリックとして扱う）。 */
const isDragging = ref(false)
/** ドラッグ確定直後に発火する click を単発クリックとして処理しないための抑止フラグ。 */
let suppressNextClick = false

/** 現在のドラッグで選択中のヘッダ列インデックス（アンカー行のみ）。 */
const dragSelectedIndices = computed<number[]>(() => {
  const anchor = dragAnchor.value
  if (!anchor || !isDragging.value) return []
  const row = matrixRows.value[anchor.rowIndex]
  if (!row) return []
  return resolveDragSelection(
    row.aligned,
    anchor.headerIndex,
    dragFocusIndex.value ?? anchor.headerIndex,
    i => !isPastCell(cellDateAt(row, i), cellStartTimeAt(row, i), todayStr.value, nowMinutes.value),
  )
})

function cellStartTimeAt(row: MatrixRowVM, headerIndex: number): string | undefined {
  const slot = row.aligned[headerIndex]
  return slot && slot.kind === 'cell' ? slot.cell.startTime : undefined
}

function cellDateAt(row: MatrixRowVM, headerIndex: number): string {
  const slot = row.aligned[headerIndex]
  return slot && slot.kind === 'cell' ? (slot.cell.slotDate ?? row.date) : row.date
}

/** そのマスがドラッグ選択のハイライト対象か（テンプレートの :class 用）。 */
function isDragSelected(rowIndex: number, headerIndex: number): boolean {
  if (dragAnchor.value?.rowIndex !== rowIndex) return false
  return dragSelectedIndices.value.includes(headerIndex)
}

function clearDrag() {
  dragAnchor.value = null
  dragFocusIndex.value = null
  isDragging.value = false
  if (import.meta.client) {
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointercancel', onDragCancel)
    window.removeEventListener('keydown', onDragKeydown)
  }
}

/** ESC でドラッグ選択を取り消す。 */
function onDragKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape') return
  // 取り消した直後の pointerup で予約導線へ入らないよう、click も併せて抑止する。
  suppressNextClick = isDragging.value
  clearDrag()
}

function onDragCancel() {
  clearDrag()
}

function onCellPointerDown(rowIndex: number, row: MatrixRowVM, headerIndex: number, event: PointerEvent) {
  // タッチはパン（スクロール）専用。マウス/ペンの主ボタンのみドラッグ選択を開始する。
  if (event.pointerType === 'touch') return
  if (event.button !== 0) return
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return
  // ドラッグで一括予約できるのは span=1 の AVAILABLE セルのみ（長尺枠・満席は単発クリックの担当）。
  if (slot.span !== 1 || slot.cell.state !== 'AVAILABLE') return
  if (isCellDisabled(row, headerIndex)) return

  dragAnchor.value = { rowIndex, headerIndex, clientX: event.clientX, clientY: event.clientY }
  dragFocusIndex.value = headerIndex
  isDragging.value = false
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onDragCancel)
  window.addEventListener('keydown', onDragKeydown)
}

/**
 * ポインタ直下のマスから、同一行の「ヘッダ列インデックス」を解決する。
 *
 * ポインタキャプチャを使っていないため、マウスドラッグ中の `event.target` は常に
 * 「カーソル直下の最前面要素」＝拾いたいマスそのものになる。これを一次情報にし、
 * target が要素でない等の例外時のみ座標から `elementFromPoint` で引き直す
 * （ビューポート座標のため、コンテナのスクロール量や sticky ヘッダーのオフセットを
 * 自前計算する必要がない）。
 */
function headerIndexFromEvent(event: PointerEvent, rowIndex: number): number | null {
  const fromTarget = event.target instanceof Element ? event.target : null
  const el = fromTarget ?? document.elementFromPoint(event.clientX, event.clientY)
  const cell = el instanceof Element ? el.closest('[data-header-index]') : null
  if (!(cell instanceof HTMLElement)) return null
  if (Number(cell.dataset.rowIndex) !== rowIndex) return null
  const index = Number(cell.dataset.headerIndex)
  return Number.isInteger(index) ? index : null
}

function onPointerMove(event: PointerEvent) {
  const anchor = dragAnchor.value
  if (!anchor) return
  if (!isDragging.value) {
    const dx = event.clientX - anchor.clientX
    const dy = event.clientY - anchor.clientY
    if (Math.hypot(dx, dy) < DRAG_THRESHOLD_PX) return
    isDragging.value = true
  }
  // ドラッグ中のテキスト選択を防ぐ（選択範囲が青く反転して見た目が壊れるため）。
  event.preventDefault()
  const index = headerIndexFromEvent(event, anchor.rowIndex)
  // 解決できない位置（sticky ヘッダーの下・別行・グリッド外）では直前のフォーカスを保持する。
  if (index != null) dragFocusIndex.value = index
}

function onPointerUp() {
  const anchor = dragAnchor.value
  const indices = dragSelectedIndices.value
  const wasDragging = isDragging.value
  if (!anchor || !wasDragging) {
    // しきい値未満＝単発クリック。既存の @click（onCellClick）にそのまま任せる。
    clearDrag()
    return
  }
  const row = matrixRows.value[anchor.rowIndex]
  clearDrag()
  // ドラッグ確定時は直後の click を食わせない（単発クリックの導線と二重に走らせない）。
  suppressNextClick = true
  if (!row || indices.length === 0) return

  const startIndex = indices[0]!
  if (indices.length === 1) {
    // 実質単発。メニューフィルター等の既存挙動をそのまま使う。
    openGroupDialog(row, startIndex, null)
    return
  }
  openGroupDialog(row, startIndex, indices.length)
}

/** GroupBookingDialog を開く（単発クリックとドラッグ確定の共通経路）。 */
function openGroupDialog(row: MatrixRowVM, startIndex: number, dragCellCount: number | null) {
  const continuation = continuationFor(row)
  dialogContext.value = {
    date: row.date,
    columnLineId: row.column.lineId ?? null,
    columnLineName: row.column.lineName ?? null,
    rowSlots: continuation.slots,
    startIndex,
    header: continuation.header,
    preselectedMenuId: filterMenuId.value,
    preselectedRequiredCellCount: requiredCellCount.value,
    dragCellCount,
  }
  dialogVisible.value = true
}

onBeforeUnmount(() => clearDrag())

function onCellClick(row: MatrixRowVM, headerIndex: number) {
  if (suppressNextClick) {
    suppressNextClick = false
    return
  }
  const slot = row.aligned[headerIndex]
  if (!slot || slot.kind !== 'cell') return
  if (isCellDisabled(row, headerIndex)) return

  if (slot.cell.state === 'BOOKED') {
    // 満席セル: キャンセル待ちダイアログを開く（W2-4-FE）。span（長尺枠の跨ぎ）に関わらず
    // 対象は常に単一 slotId のため、長尺/30分どちらでも扱いは同じ。
    if (slot.cell.slotId == null) return
    const line = resolveLine(row.column)
    waitlistContext.value = {
      slotId: slot.cell.slotId,
      date: row.date,
      startTime: slot.cell.startTime ?? '',
      endTime: slot.cell.endTime ?? '',
      lineName: line.name,
    }
    waitlistDialogVisible.value = true
    return
  }

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

  openGroupDialog(row, headerIndex, null)
}

function onDialogReserved() {
  void loadGridAndWaitlist()
  emit('reserved')
}

/** 登録/取消成功時: グリッド＋自分の登録集合を再取得し、親に「自分のキャンセル待ち」再読込を促す。 */
async function onWaitlistChanged() {
  await loadGridAndWaitlist({ silent: true })
  emit('waitlistChanged')
}

async function loadMyWaitlist() {
  try {
    const res = await reservationApi.listMyWaitlist()
    const loaded = loadedSlotIds.value
    myWaitlistSlotIds.value = new Set(
      (res.data ?? [])
        .filter(e => e.slotId != null && loaded.has(e.slotId))
        .map(e => e.slotId!),
    )
  }
  catch (error) {
    // 取得失敗は「未登録」扱いにフォールバック（満席セルのラベルが「待機中」にならないだけで、
    // ダイアログを開けば実際の登録状態は API 側で正しく判定される。致命的でないため通知はしない）。
    // ただし完全に握りつぶすと恒常的な失敗が誰にも見えなくなるため、静かに記録する（captureQuiet）。
    captureQuiet(error, { context: 'SlotMatrixPicker: 自分のキャンセル待ち取得に失敗' })
    myWaitlistSlotIds.value = new Set()
  }
}

/**
 * グリッド→自分のキャンセル待ち集合の順で再取得する（`loadedSlotIds` はグリッド依存のため必ずこの
 * 順序で呼ぶ）。**検分是正（2026-07-30）**: 以前は `loadGrid` 単体を呼ぶ経路が複数箇所（週/メニュー
 * フィルタ切替の watch・KeepAlive 再活性化）に分散しており、`loadMyWaitlist` の呼び忘れにより
 * 「週を移動すると登録済みの満席セルから『待機中』表示が消え、登録ボタンを押すと409になる」
 * 実バグを誘発した。以後グリッド再取得は必ずこの関数経由にし、呼び分け（＝再発）を構造的に禁止する。
 */
async function loadGridAndWaitlist(opts?: { silent?: boolean }) {
  await loadGrid(opts)
  await loadMyWaitlist()
}

function gridStyle(headerLength: number): string {
  return `grid-template-columns: minmax(7rem, auto) repeat(${headerLength}, minmax(3.5rem, 1fr));`
}

// loadGrid が opts 引数を持つため、watch コールバックの (newVal, oldVal) が誤って渡らないようラップする
watch([weekStart, filterMenuId], () => loadGridAndWaitlist())

onMounted(async () => {
  thisWeek()
  await Promise.all([loadLines(), loadMenus(), loadResourceName()])
  await loadGridAndWaitlist()
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
  void loadGridAndWaitlist({ silent: true })
})

// 予約直後に親から再読込させるための公開メソッド（既存パターン踏襲・defineExpose({ refresh })）。
// 呼称設定変更後の再読込（onResourceNameChanged）からも呼ばれるため、呼称表示も合わせて最新化する。
defineExpose({
  refresh: async () => {
    await loadResourceName()
    await loadGridAndWaitlist()
  },
})
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

    <!-- 予約対象はあるが表示中の週の枠がゼロ: 枠作成導線（管理者のみCTA）。
         上の「予約対象ゼロ」（*_no_lines）とは別物なので取り違えないこと。 -->
    <DashboardEmptyState
      v-else-if="!hasSlots"
      icon="pi pi-calendar-times"
      data-testid="matrix-no-slots-empty"
      :message="isAdmin ? t('reservation.empty.book.admin_no_slots') : t('reservation.empty.book.member_no_slots')"
      :sub-message="isAdmin ? t('reservation.empty.book.admin_no_slots_hint') : t('reservation.empty.book.member_no_slots_hint')"
    >
      <template v-if="isAdmin" #action>
        <Button
          :label="t('reservation.button.manage_slots')"
          icon="pi pi-cog"
          size="small"
          @click="emit('manageSlots')"
        />
      </template>
    </DashboardEmptyState>

    <!-- マトリックス本体（縦横スクロール・overscroll-contain・時間ヘッダ行 sticky top・行ヘッダ列 sticky left）。
         縦スクロールを本コンテナ内に閉じ込める（max-h + overflow-auto）ことで sticky top を確実に効かせる。 -->
    <div v-else class="max-h-[65vh] overflow-auto overscroll-contain">
      <!-- ドラッグ中のみテキスト選択を殺す（常時 select-none にはしない＝通常時のコピーを妨げない）。
           touch-action は一切いじらない: タッチはマトリックスの縦横パン専用で、ドラッグ選択は
           マウス/ペンのみ（onCellPointerDown で pointerType を判定）。 -->
      <div
        class="inline-grid min-w-full gap-1"
        :class="isDragging ? 'select-none' : ''"
        :style="gridStyle(header.length)"
      >
        <!-- ヘッダー行: 左上コーナー（両軸 sticky・最前面）+ 時間見出し（sticky top） -->
        <div class="sticky left-0 top-0 z-20 flex items-center justify-center bg-surface-0 p-2 text-xs font-semibold text-surface-500 dark:bg-surface-900">
          {{ t('reservation.matrix.date_line_header', { resourceName }) }}
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
              :class="[cellStateClass(row, ci), isDragSelected(ri, ci) ? 'border-primary bg-primary/20 ring-2 ring-primary' : '']"
              :disabled="isCellDisabled(row, ci)"
              :aria-label="cellAriaLabel(row, ci)"
              :data-row-index="ri"
              :data-header-index="ci"
              :title="unavailableReasonOf(row, ci) ?? (row.startable && slot.kind === 'cell' && slot.span === 1 && !row.startable.has(ci) && slot.cell.state === 'AVAILABLE' ? t('reservation.matrix.cannot_start_here', { menu: menuFilterOptions.find(o => o.value === filterMenuId)?.label ?? '' }) : undefined)"
              @pointerdown="onCellPointerDown(ri, row, ci, $event)"
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

    <ReservationWaitlistDialog
      v-model:visible="waitlistDialogVisible"
      :team-id="props.teamId"
      :is-admin="props.isAdmin"
      :resource-name="resourceName"
      :context="waitlistContext"
      :registered-slot-ids="myWaitlistSlotIds"
      @changed="onWaitlistChanged"
    />
  </div>
</template>
