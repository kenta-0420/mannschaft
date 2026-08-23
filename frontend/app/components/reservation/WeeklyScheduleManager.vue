<script setup lang="ts">
/**
 * 週間スケジュール管理（旧 SlotTemplateManager・F03.4.5 §3.2/§4.5）ADMIN限定
 *
 * 【F03.4.5 W2-1 第一隊 改訂点（営業スケジュール中心モデル・マスターの実使用フィードバック起点）】
 * - テンプレ保存＝**即自動生成**（§3.1）。保存レスポンスは `SlotTemplateSaveResponse`
 *   （`data.template` ＋ `data.generation`）で、「今すぐ枠を作成」ボタン・`weeks` Select は撤去した
 *   （保存＝反映になるため操作の存在意義が消える。API 自体は BE 側 `@Deprecated` で残置）。
 * - 複数曜日の新規作成（W1 由来の `selectedDays` ループ）は、各呼びの `generation` を**合算して1トースト**
 *   で報告する（§3.1 集約規則）。部分失敗（一部曜日で `generation.failed=true`、または呼び自体が
 *   4xx/5xx）も「N曜日中M曜日で失敗」の1トーストに集約する（曜日ごとにN連トーストを出さない）。
 * - `hasBusinessHours=false` のチームでは空状態に「先に営業時間を設定してください」導線を表示し、
 *   ①営業時間セクションへスクロールする `focus-business-hours` を親（TeamReservationsPanel）へ emit する。
 *
 * - 曜日トグルの見た目は ScheduleEventRecurrenceInput.vue の写経。
 *   **ただし写経元は 'MONDAY' フルネームを emit するため、value は必ず3文字大文字
 *   'MON'..'SUN'（BE の ReservationDayOfWeek enum）へ変換して API に送る**
 *   （フルネーム送信は Jackson デシリアライズ失敗で 400 — 設計書 §3.1/§4/§10 明記）。
 * - エラーは BE コードで判定して表示（握りつぶし禁止）: RESERVATION_037(400) 上限500行。
 *
 * 【F03.4.5 W2-2-FE §4 B) 定期予約不可枠 統合（本隊）】
 * - 曜日ごとのグルーピング表示に、枠テンプレ（青系）と定期予約不可（赤系・事由ラベル付き）を
 *   並べて表示する（§4.5）。各曜日行に「＋枠テンプレ」（既存 openCreate へ day 引数追加）と
 *   「＋予約不可」（新設 openCreateRecurring）の2ボタンを置く。
 * - 定期不可の追加/編集ダイアログは `ReservationUnavailabilityManager.vue` の金型（時刻 Select・
 *   impact 警告）を写経。`reason` は必須（BE `@NotBlank` 実測）・`isPublic` ON 時は
 *   `reason_no_pii` 注意ガイドを必須表示（§4.4/AC R-9）・全日型は作らせない（start/end 必須・§4.3）。
 * - dayOfWeek は必ず3文字大文字（'MON'..'SUN'）で送る（BE `ReservationDayOfWeek` enum・
 *   'MONDAY' 等フルネームは Jackson デシリアライズ失敗で 400）。
 * - エラーコード（BE PR #2232 実測）: 409=RESERVATION_027（overlap する active 予約・機能B と共用）／
 *   400=RESERVATION_052（上限50行）／404=RESERVATION_051（IDOR秘匿・他チーム ruleId）。
 * - 単発の予約不可枠（機能B・`ReservationUnavailabilityManager`）は詳細設定 Accordion に残置（§4.5・変更なし）。
 *
 * 金型: LineManager.vue（CRUDダイアログ型）／ReservationUnavailabilityManager.vue（impact 判定作法）。
 * 最終ゲートは BE。
 */
import type { components } from '~/types/generated'
import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'
import {
  RESERVATION_DAY_OPTIONS,
  buildHalfHourTimeOptions,
  toHm,
  isValidHalfHourRange,
} from '~/composables/useReservationDayOptions'
import { collectOccupiedCells, type SlotDragRange } from '~/composables/useSlotDragSelection'

type SlotTemplateResponse = components['schemas']['SlotTemplateResponse'] & { endsNextDay?: boolean }
type ReservationLineResponse = components['schemas']['ReservationLineResponse']
type SlotGenerationResultDto = components['schemas']['SlotGenerationResultDto']
type RecurringBlockedTimeResponse = components['schemas']['RecurringBlockedTimeResponse'] & { endsNextDay?: boolean }
type RecurringBlockedTimeImpactResponse = components['schemas']['RecurringBlockedTimeImpactResponse']

const props = defineProps<{
  teamId: string
  /**
   * `ReservationSettingsResponse.hasBusinessHours`（実測フィールド）。未ロード中は暫定 true とし、
   * ロード完了後に false へ切り替わったら空状態の初回体験ガイドを出す（S-11・§3.2）。
   */
  hasBusinessHours?: boolean
}>()

const emit = defineEmits<{
  /** 空状態の「営業時間を設定する」導線クリック時。親が①営業時間セクションを開いてスクロールする。 */
  'focus-business-hours': []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
// 変数名 confirm は既存コードのグローバル window.confirm()（remove/removeRecurring の削除確認）と衝突するため
// confirmDialog という名前にする（PrimeVue の ConfirmationService インスタンス）。
const confirmDialog = useConfirm()
// 多重防御（defense-in-depth）: 親タブの v-if に加え、破壊的操作ボタンを本コンポーネントでも
// ロールで制御する。BE が本防御線だが、別画面から再利用された際の誤表示を防ぐ。
const { isAdmin, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

/** 共通枠（lineId なし）を表すフォーム用センチネル値 */
const COMMON_LINE = -1

const templates = ref<SlotTemplateResponse[]>([])
const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const saving = ref(false)
const showDialog = ref(false)
const editingTemplate = ref<SlotTemplateResponse | null>(null)
/** 保存直後に表示する自動反映の補足ガイド（§11 regenerate_guide 改訂値）。 */
const showRegenerateGuide = ref(false)

interface TemplateForm {
  lineId: number
  startTime: string
  endTime: string
  endsNextDay: boolean
  capacity: number
  isActive: boolean
}

function defaultForm(): TemplateForm {
  return {
    lineId: COMMON_LINE,
    startTime: '09:00',
    endTime: '10:00',
    endsNextDay: false,
    capacity: 1,
    isActive: true,
  }
}

const form = ref<TemplateForm>(defaultForm())

/**
 * 曜日トグルの選択状態。
 * 新規作成時は複数選択可（選択曜日ぶん createSlotTemplate を順に呼び曜日ごとのテンプレ行に展開する）。
 * 編集時は既存行単位のまま単一曜日（toggleDay が選択を1件に固定する）。
 */
const selectedDays = ref<ReservationDayOfWeekCode[]>([])

/** 30分刻みの時刻オプション（00:00〜23:30） */
const timeOptions = computed(() => buildHalfHourTimeOptions())

/** 対象ラインの選択肢（共通枠 + active ライン） */
const lineOptions = computed(() => [
  { label: t('reservation.template.line_common'), value: COMMON_LINE },
  ...lines.value
    .filter(l => l.meta?.isActive)
    .map(l => ({ label: l.meta?.name ?? '', value: l.id ?? 0 })),
])

const timeRangeValid = computed(() =>
  !!form.value.startTime && !!form.value.endTime
  && (form.value.endsNextDay ? form.value.startTime > form.value.endTime : form.value.startTime < form.value.endTime),
)

const saveDisabled = computed(() =>
  saving.value
  || selectedDays.value.length === 0
  || !timeRangeValid.value
  || form.value.capacity < 1
  || form.value.capacity > 99,
)

function dayLabel(code?: string | null): string {
  const opt = RESERVATION_DAY_OPTIONS.find(d => d.value === code)
  return opt ? t(opt.labelKey) : (code ?? '')
}

function formatEndTime(value: string | null | undefined, endsNextDay?: boolean): string {
  const hm = toHm(value)
  return endsNextDay ? t('reservation.template.next_day_time', { time: hm }) : hm
}

/**
 * 曜日トグルのクリック処理。
 * 編集時は既存行単位のまま（クリックした曜日1件に固定・現行維持）。
 * 新規作成時は複数選択可（トグル式のON/OFF）。
 */
function toggleDay(day: ReservationDayOfWeekCode) {
  if (editingTemplate.value) {
    selectedDays.value = [day]
    return
  }
  const idx = selectedDays.value.indexOf(day)
  if (idx >= 0) selectedDays.value.splice(idx, 1)
  else selectedDays.value.push(day)
}

async function loadTemplates() {
  loading.value = true
  try {
    const res = await reservationApi.getSlotTemplates(props.teamId)
    templates.value = res.data.templates ?? []
  }
  catch {
    templates.value = []
    notification.error(t('reservation.message.template_load_failed'))
  }
  finally {
    loading.value = false
  }
}

async function loadLines() {
  try {
    const res = await reservationApi.getLines(props.teamId)
    lines.value = res.data ?? []
  }
  catch {
    lines.value = []
  }
}

/**
 * テンプレ作成ダイアログを開く。`day` 指定時（曜日行の「＋枠テンプレ」クイック追加）は
 * 当該曜日のみ選択済みで開く。無指定（ヘッダーの「テンプレートを追加」・複数曜日対応）は従来どおり空。
 */
function openCreate(day?: ReservationDayOfWeekCode) {
  editingTemplate.value = null
  form.value = defaultForm()
  selectedDays.value = day ? [day] : []
  showDialog.value = true
}

function openEdit(template: SlotTemplateResponse) {
  editingTemplate.value = template
  form.value = {
    lineId: template.lineId ?? COMMON_LINE,
    startTime: toHm(template.startTime),
    endTime: toHm(template.endTime),
    endsNextDay: template.endsNextDay ?? false,
    capacity: template.capacity ?? 1,
    isActive: template.isActive ?? true,
  }
  const day = RESERVATION_DAY_OPTIONS.find(d => d.value === template.dayOfWeek)?.value
  selectedDays.value = day ? [day] : []
  showDialog.value = true
}

/** BE エラーコード → 利用者向け文言（握りつぶさない） */
function notifySaveError(err: unknown) {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  if (code === 'RESERVATION_037') {
    notification.error(t('dialog.error'), t('reservation.template.limit_reached'))
    return
  }
  handleApiError(err)
}

/**
 * 保存成功後の同期自動生成結果（1件 or N件合算）をトーストへ集約する（§3.1 集約規則・AC-FE7★）。
 *
 * - いずれかの `generation.failed` が true → 「{total}曜日中{failed}曜日で失敗」の警告トースト1本
 *   （§11 auto_generated_partial）。
 * - 生成0件かつ営業時間外/定休日スキップが原因 → 原因を明示する警告トースト（§11 generated_zero_hint・S-11）。
 * - それ以外 → 合算件数の成功トースト（§11 auto_generated）。
 */
function reportGenerationOutcome(generations: SlotGenerationResultDto[]) {
  if (generations.length === 0) return

  const failedCount = generations.filter(g => g.failed).length
  if (failedCount > 0) {
    notification.warn(
      t('reservation.template.title'),
      t('reservation.template.auto_generated_partial', { total: generations.length, failed: failedCount }),
    )
    return
  }

  const totalGenerated = generations.reduce((sum, g) => sum + (g.generatedCount ?? 0), 0)
  const totalSkippedOutside = generations.reduce((sum, g) => sum + (g.skippedOutsideHoursCount ?? 0), 0)
  const totalSkippedClosed = generations.reduce((sum, g) => sum + (g.skippedClosedDayCount ?? 0), 0)

  if (totalGenerated === 0 && (totalSkippedOutside > 0 || totalSkippedClosed > 0)) {
    // 「保存したのに0件」の無言の混乱を防ぐ（S-11・原因を明示）
    notification.warn(t('reservation.template.title'), t('reservation.template.generated_zero_hint'))
    return
  }

  notification.success(
    t('reservation.template.title'),
    t('reservation.template.auto_generated', { days: 28, generated: totalGenerated }),
  )
}

async function save() {
  if (saveDisabled.value || selectedDays.value.length === 0) return
  saving.value = true
  // dayOfWeek は必ず3文字大文字（'MON'..'SUN'）。時刻は既存 SlotFormDialog と同じ HH:mm:00。
  const base = {
    startTime: `${form.value.startTime}:00`,
    endTime: `${form.value.endTime}:00`,
    endsNextDay: form.value.endsNextDay,
    capacity: form.value.capacity,
  }
  try {
    if (editingTemplate.value?.id) {
      // 編集は既存行単位のまま単一曜日（selectedDays は toggleDay により常に1件に固定される）
      const day = selectedDays.value[0]
      if (!day) return
      // PATCH: 共通枠へ戻す場合は clearLineId で明示（null 据え置きと区別）
      const res = await reservationApi.updateSlotTemplate(props.teamId, editingTemplate.value.id, {
        ...base,
        dayOfWeek: day,
        ...(form.value.lineId === COMMON_LINE
          ? { clearLineId: true }
          : { lineId: form.value.lineId }),
        isActive: form.value.isActive,
      })
      if (res.data.generation) reportGenerationOutcome([res.data.generation])
    }
    else {
      // 選択曜日ぶん createSlotTemplate を順に呼び、曜日ごとのテンプレ行に展開する（DDL/API変更なし）
      const total = selectedDays.value.length
      const succeeded: ReservationDayOfWeekCode[] = []
      const generations: SlotGenerationResultDto[] = []
      try {
        for (const day of selectedDays.value) {
          const res = await reservationApi.createSlotTemplate(props.teamId, {
            ...base,
            dayOfWeek: day,
            ...(form.value.lineId === COMMON_LINE ? {} : { lineId: form.value.lineId }),
          })
          succeeded.push(day)
          if (res.data.generation) generations.push(res.data.generation)
        }
      }
      catch (err) {
        // 部分失敗の根治処理（RESERVATION_037 上限到達の途中失敗が現実的な発生経路）:
        // (1) 成功済み曜日を選択から除去 — ダイアログを開いたまま再試行しても成功分を再作成して
        //     重複行にならないようにする（失敗曜日のみ残す）
        // (2) 部分成功は「N曜日中M曜日で失敗」の警告トースト1本に集約する（§3.1 集約規則・AC-FE7★）
        // (3) 一覧を実状態（成功分のみ作成済み）へ同期
        selectedDays.value = selectedDays.value.filter(d => !succeeded.includes(d))
        if (succeeded.length > 0) {
          notification.warn(
            t('reservation.template.title'),
            t('reservation.template.auto_generated_partial', { total, failed: total - succeeded.length }),
          )
          showRegenerateGuide.value = true
        }
        else {
          // 1件も保存できていない（全滅）場合のみ、BEエラーの詳細を個別トーストで伝える
          notifySaveError(err)
        }
        await loadTemplates()
        // ダイアログは閉じない（失敗曜日のみ選択された状態で再試行できる）
        return
      }
      reportGenerationOutcome(generations)
    }
    showDialog.value = false
    // 自動生成は保存に統合済みだが、既存の未予約枠を新定義に合わせたい場合の手順は残す（§11 regenerate_guide 改訂）
    showRegenerateGuide.value = true
    await loadTemplates()
  }
  catch (err) {
    // 編集（PATCH）失敗経路。エラー通知に加え、一覧を実状態へ同期しておく（検分指摘 (a)）
    notifySaveError(err)
    await loadTemplates()
  }
  finally {
    saving.value = false
  }
}

// === 週グリッドのドラッグ範囲選択（管理者の枠作成の手数削減）===
//
// ダイアログのフォーム（曜日トグル＋開始/終了 Select＋定員）は残したまま（AC5・キーボード操作と
// ドラッグが使えない場面のため）、グリッドを主導線として併設する。
// ドラッグで曜日・開始/終了時刻は確定済みなので、確認では**定員だけ**を聞く（再入力させない）。

/** グリッド上で既に埋まっているセル。枠テンプレと定期予約不可の両方を「埋まっている」とみなす。 */
const occupiedCells = computed(() =>
  collectOccupiedCells([...templates.value, ...recurringRules.value]),
)

const showDragConfirm = ref(false)
const dragRange = ref<SlotDragRange | null>(null)
const dragCapacity = ref(1)

/** 確認ダイアログに出す確定済みの範囲（曜日ラベル＋時刻）。 */
const dragRangeLabel = computed(() => {
  const range = dragRange.value
  if (!range) return ''
  return `${range.days.map(d => dayLabel(d)).join(' / ')} ${range.startTime} - ${range.endTime}`
})

function onGridSelect(range: SlotDragRange) {
  dragRange.value = range
  dragCapacity.value = 1
  showDragConfirm.value = true
}

/** 既存枠を含む範囲がなぞられた場合（重複作成の API エラーを踏ませない・AC6）。 */
function onGridBlocked() {
  notification.warn(t('reservation.template.title'), t('reservation.template.grid.blocked'))
}

/**
 * ドラッグ確定＋定員入力から作成する。既存の `save()`（複数曜日ループ・generation 集約・
 * 部分失敗処理）をそのまま再利用するため、フォーム状態へ流し込んでから呼ぶ（作成経路を二重化しない）。
 */
async function createFromDragRange() {
  const range = dragRange.value
  if (!range || range.days.length === 0) return
  editingTemplate.value = null
  form.value = {
    ...defaultForm(),
    startTime: range.startTime,
    endTime: range.endTime,
    capacity: dragCapacity.value,
  }
  selectedDays.value = [...range.days]
  showDragConfirm.value = false
  dragRange.value = null
  await save()
}

async function remove(template: SlotTemplateResponse) {
  if (!template.id) return
  if (!confirm(t('reservation.template.delete_confirm'))) return
  try {
    await reservationApi.deleteSlotTemplate(props.teamId, template.id)
    notification.success(t('reservation.message.template_delete_success'))
    await loadTemplates()
  }
  catch (err) {
    handleApiError(err)
  }
}

// === §4 B) 定期予約不可枠（週次繰り返し・赤系併記表示）===

const recurringRules = ref<RecurringBlockedTimeResponse[]>([])
const recurringLoading = ref(true)
const showRecurringDialog = ref(false)
const editingRule = ref<RecurringBlockedTimeResponse | null>(null)
const recurringSaving = ref(false)

interface RecurringForm {
  dayOfWeek: ReservationDayOfWeekCode
  lineId: number
  startTime: string
  endTime: string
  endsNextDay: boolean
  reason: string
  isPublic: boolean
  isActive: boolean
}

function defaultRecurringForm(day?: ReservationDayOfWeekCode): RecurringForm {
  return {
    dayOfWeek: day ?? 'MON',
    lineId: COMMON_LINE,
    startTime: '19:00',
    endTime: '20:00',
    endsNextDay: false,
    reason: '',
    isPublic: false,
    isActive: true,
  }
}

const recurringForm = ref<RecurringForm>(defaultRecurringForm())

/** 曜日 Select の選択肢（単一選択・§4.5）。値は3文字大文字コード。 */
const recurringDayOptions = computed(() =>
  RESERVATION_DAY_OPTIONS.map(d => ({ label: t(d.labelKey), value: d.value })),
)

/** 全日型は作らせない（§4.3）: 両時刻が有効な半開区間（start < end）のときのみ true。 */
const recurringTimeRangeValid = computed(() =>
  recurringForm.value.endsNextDay
    ? !!recurringForm.value.startTime && !!recurringForm.value.endTime && recurringForm.value.startTime > recurringForm.value.endTime
    : isValidHalfHourRange(recurringForm.value.startTime, recurringForm.value.endTime),
)

/** 事由（reason）は必須（BE `@NotBlank`・§4.1）。 */
const recurringReasonValid = computed(() =>
  recurringForm.value.reason.trim().length > 0 && recurringForm.value.reason.trim().length <= 100,
)

const recurringImpact = ref<RecurringBlockedTimeImpactResponse | null>(null)
const recurringImpactLoading = ref(false)
let recurringImpactToken = 0

/** impact 判定用の有効リクエスト（時刻レンジが無効な間は null＝impact を呼ばない）。 */
const recurringEffectiveRequest = computed(() => {
  if (!recurringTimeRangeValid.value) return null
  return {
    dayOfWeek: recurringForm.value.dayOfWeek,
    startTime: `${recurringForm.value.startTime}:00`,
    endTime: `${recurringForm.value.endTime}:00`,
    endsNextDay: recurringForm.value.endsNextDay,
    lineId: recurringForm.value.lineId === COMMON_LINE ? undefined : recurringForm.value.lineId,
  }
})

async function refreshRecurringImpact() {
  const req = recurringEffectiveRequest.value
  if (!req) {
    recurringImpact.value = null
    return
  }
  const token = ++recurringImpactToken
  recurringImpactLoading.value = true
  try {
    const res = await reservationApi.getRecurringBlockedTimeImpact(props.teamId, req)
    if (token === recurringImpactToken) recurringImpact.value = res.data
  }
  catch {
    // impact 取得失敗は登録可否に影響させない（BE の 409 が最終防御・ReservationUnavailabilityManager と同方針）
    if (token === recurringImpactToken) recurringImpact.value = null
  }
  finally {
    if (token === recurringImpactToken) recurringImpactLoading.value = false
  }
}

watch(recurringEffectiveRequest, refreshRecurringImpact, { deep: true })

const recurringHasConflict = computed(() => (recurringImpact.value?.affectedCount ?? 0) > 0)

const recurringSaveDisabled = computed(() =>
  recurringSaving.value
  || !recurringTimeRangeValid.value
  || !recurringReasonValid.value
  || recurringImpactLoading.value
  || recurringHasConflict.value,
)

async function loadRecurringRules() {
  recurringLoading.value = true
  try {
    const res = await reservationApi.listRecurringBlockedTimes(props.teamId)
    recurringRules.value = res.data ?? []
  }
  catch {
    recurringRules.value = []
    notification.error(t('reservation.recurring_block.message.load_failed'))
  }
  finally {
    recurringLoading.value = false
  }
}

/** 曜日別グルーピング（§4.5・§3.2 の「曜日ごとのグルーピング表示」）。 */
function templatesByDay(day: ReservationDayOfWeekCode): SlotTemplateResponse[] {
  return templates.value.filter(tpl => tpl.dayOfWeek === day)
}
function rulesByDay(day: ReservationDayOfWeekCode): RecurringBlockedTimeResponse[] {
  return recurringRules.value.filter(r => r.dayOfWeek === day)
}

/** テンプレ・定期不可のいずれかが1件でもあれば曜日グルーピング表示、無ければ従来の空状態を出す。 */
const hasAnyContent = computed(() => templates.value.length > 0 || recurringRules.value.length > 0)

/** 定期不可の追加ダイアログを開く（曜日行の「＋予約不可」クイック追加は day 指定で開く）。 */
function openCreateRecurring(day?: ReservationDayOfWeekCode) {
  editingRule.value = null
  recurringForm.value = defaultRecurringForm(day)
  recurringImpact.value = null
  showRecurringDialog.value = true
}

function openEditRecurring(rule: RecurringBlockedTimeResponse) {
  editingRule.value = rule
  recurringForm.value = {
    dayOfWeek: (RESERVATION_DAY_OPTIONS.find(d => d.value === rule.dayOfWeek)?.value ?? 'MON'),
    lineId: rule.lineId ?? COMMON_LINE,
    startTime: toHm(rule.startTime),
    endTime: toHm(rule.endTime),
    endsNextDay: rule.endsNextDay ?? false,
    reason: rule.reason ?? '',
    isPublic: rule.isPublic ?? false,
    isActive: rule.isActive ?? true,
  }
  recurringImpact.value = null
  showRecurringDialog.value = true
}

/**
 * BE エラーコード → 利用者向け文言（握りつぶさない。§4.6/§10 実測: 051=NOT_FOUND・052=上限・027=409共用）。
 *
 * 検分指摘（軽2）: `toggleRecurringActive`（一時停止/再開）が `handleApiError` を直呼びしていたため、
 * 保存経路（`saveRecurring`）と同じ 409(RESERVATION_027) でも表示文言が経路によって不揃いだった。
 * 本関数へ寄せて統一し、`context` で保存/一時停止・再開を出し分ける
 * （BE の `updateRule` が isActive のみの PATCH でも重複ガードを無条件実行する — 「規制を緩める操作なのに
 * 409で失敗し得る」問題自体は BE 側の課題であり本PRでは直さない。別弾。FE の文言だけ文脈に合わせる）。
 */
function notifyRecurringSaveError(err: unknown, context: 'save' | 'toggle' = 'save') {
  const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
  if (code === 'RESERVATION_052') {
    notification.error(t('dialog.error'), t('reservation.recurring_block.limit_reached'))
    return
  }
  if (code === 'RESERVATION_027') {
    if (context === 'toggle') {
      // 一時停止/再開の文脈: 「登録できません」ではなく操作そのものが失敗した旨を伝える
      notification.error(t('dialog.error'), t('reservation.recurring_block.error.toggle_conflict'))
      return
    }
    // 保存（作成/更新）の文脈: overlap する active 予約が残ったまま登録した競合（最終防御・機能B と同一コード共用）
    notification.error(t('dialog.error'), t('reservation.unavailability.error.has_active_reservations'))
    refreshRecurringImpact()
    return
  }
  handleApiError(err)
}

/**
 * 定期予約不可枠の作成/更新。`force=true`（強行登録・§4.3〜§6.2 の構造的衝突の根治・殿の裁定）のときのみ
 * `forceCancelConflicting: true` を additive に送る。既定 false（=通常の保存経路は従来どおり409で弾かれる）。
 */
async function saveRecurring(force = false) {
  const req = recurringEffectiveRequest.value
  if (!req) return
  if (!force && recurringSaveDisabled.value) return
  recurringSaving.value = true
  try {
    if (editingRule.value?.id) {
      const res = await reservationApi.updateRecurringBlockedTime(props.teamId, editingRule.value.id, {
        dayOfWeek: req.dayOfWeek,
        startTime: req.startTime,
        endTime: req.endTime,
        endsNextDay: req.endsNextDay,
        reason: recurringForm.value.reason.trim(),
        isPublic: recurringForm.value.isPublic,
        isActive: recurringForm.value.isActive,
        ...(recurringForm.value.lineId === COMMON_LINE ? { clearLineId: true } : { lineId: recurringForm.value.lineId }),
        ...(force ? { forceCancelConflicting: true } : {}),
      })
      notifyForceCancelledIfAny(res.data.forceCancelledCount)
      notification.success(t('reservation.recurring_block.message.update_success'))
    }
    else {
      const res = await reservationApi.createRecurringBlockedTime(props.teamId, {
        dayOfWeek: req.dayOfWeek,
        startTime: req.startTime,
        endTime: req.endTime,
        endsNextDay: req.endsNextDay,
        reason: recurringForm.value.reason.trim(),
        isPublic: recurringForm.value.isPublic,
        ...(recurringForm.value.lineId === COMMON_LINE ? {} : { lineId: recurringForm.value.lineId }),
        ...(force ? { forceCancelConflicting: true } : {}),
      })
      notifyForceCancelledIfAny(res.data.forceCancelledCount)
      notification.success(t('reservation.recurring_block.message.create_success'))
    }
    showRecurringDialog.value = false
    await loadRecurringRules()
  }
  catch (err) {
    notifyRecurringSaveError(err)
  }
  finally {
    recurringSaving.value = false
  }
}

/** 強行登録で実際にキャンセルされた件数を正直に伝える（0件=forceが実行された事実そのものを区別して見せる）。 */
function notifyForceCancelledIfAny(count?: number) {
  if (count != null && count > 0) {
    notification.warn(
      t('reservation.recurring_block.title'),
      t('reservation.recurring_block.force_cancel.result_notice', { n: count }),
    )
  }
}

/**
 * 強行登録の確認ダイアログ（破壊的操作・§6.2「殿の裁定」）。
 * impact 件数と「予約がキャンセルされ、申込者に通知が送られる」ことを明示してから確定させる
 * （既定 false・うっかり押せない形にする）。
 */
function confirmForceSaveRecurring() {
  const count = recurringImpact.value?.affectedCount ?? 0
  confirmDialog.require({
    message: t('reservation.recurring_block.force_cancel.confirm_message', { n: count }),
    header: t('reservation.recurring_block.force_cancel.confirm_title'),
    icon: 'pi pi-exclamation-triangle',
    acceptLabel: t('reservation.recurring_block.force_cancel.confirm_ok'),
    rejectLabel: t('reservation.button.cancel'),
    acceptClass: 'p-button-danger',
    accept: () => saveRecurring(true),
  })
}

/** 一時停止/再開（isActive トグル）。§4.6 の PATCH を再利用。 */
async function toggleRecurringActive(rule: RecurringBlockedTimeResponse) {
  if (!rule.id) return
  try {
    await reservationApi.updateRecurringBlockedTime(props.teamId, rule.id, {
      isActive: !(rule.isActive ?? true),
    })
    await loadRecurringRules()
  }
  catch (err) {
    // 検分指摘（軽2）: 保存経路と同一のエラー判定に寄せる（409=RESERVATION_027 は「一時停止/再開」文脈の文言で通知）
    notifyRecurringSaveError(err, 'toggle')
  }
}

/** 物理削除（履歴価値なし・§4.6）。確認ダイアログを経由する（多重マウント回避のため既存 confirm() 作法を踏襲）。 */
async function removeRecurring(rule: RecurringBlockedTimeResponse) {
  if (!rule.id) return
  if (!confirm(t('reservation.recurring_block.delete_confirm'))) return
  try {
    await reservationApi.deleteRecurringBlockedTime(props.teamId, rule.id)
    notification.success(t('reservation.recurring_block.message.delete_success'))
    await loadRecurringRules()
  }
  catch (err) {
    handleApiError(err)
  }
}

onMounted(async () => {
  await loadPermissions()
  await Promise.all([loadTemplates(), loadLines(), loadRecurringRules()])
})

// 親（TeamReservationsPanel）のアコーディオン件数バッジ用（既存 FriendFolderList 等と同一パターン）。
// 検分指摘（軽4）: バッジは「枠テンプレ＋定期予約不可」の合算件数を表すため、recurringItems も供給する
// （既存の items 契約は変更せず追加のみ＝additive）。
defineExpose({
  refresh: async () => { await Promise.all([loadTemplates(), loadRecurringRules()]) },
  items: templates,
  recurringItems: recurringRules,
})
</script>

<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h3 class="text-lg font-semibold">{{ t('reservation.template.title') }}</h3>
      <div v-if="isAdmin" class="flex flex-wrap gap-2">
        <Button
          :label="t('reservation.template.add')"
          icon="pi pi-plus"
          size="small"
          data-testid="template-add"
          @click="openCreate()"
        />
        <Button
          :label="t('reservation.recurring_block.add')"
          icon="pi pi-plus"
          size="small"
          severity="danger"
          outlined
          data-testid="recurring-add"
          @click="openCreateRecurring()"
        />
      </div>
    </div>

    <!-- 保存直後の自動反映ガイド（regenerate_guide 改訂値・§11） -->
    <Message v-if="showRegenerateGuide && isAdmin" severity="info" :closable="true" class="mb-3 text-sm">
      {{ t('reservation.template.regenerate_guide') }}
    </Message>

    <!-- 使い方の一言: 青=枠テンプレ・赤=定期予約不可（§4.5「営業の設計図」が1画面） -->
    <p v-if="hasAnyContent" class="mb-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-surface-500">
      <span class="flex items-center gap-1">
        <span class="inline-block size-2.5 rounded-full bg-blue-400" />{{ t('reservation.template.title') }}
      </span>
      <span class="flex items-center gap-1">
        <span class="inline-block size-2.5 rounded-full bg-red-400" />{{ t('reservation.recurring_block.title') }}
      </span>
    </p>

    <!-- 週グリッド（ドラッグ範囲選択で枠を作成・ADMIN限定）。フォーム導線は下のダイアログに残置。 -->
    <WeeklySlotDragGrid
      v-if="isAdmin && !loading && !recurringLoading"
      class="mb-4"
      :occupied="occupiedCells"
      @select="onGridSelect"
      @blocked="onGridBlocked"
    />

    <!-- 一覧（曜日ごとのグルーピング表示・§3.2/§4.5） -->
    <div v-if="loading || recurringLoading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="hasAnyContent" class="space-y-3">
      <div
        v-for="day in RESERVATION_DAY_OPTIONS"
        :key="day.value"
        class="rounded-lg border border-surface-200 dark:border-surface-700"
      >
        <div class="flex flex-wrap items-center justify-between gap-2 border-b border-surface-200 p-2 dark:border-surface-700">
          <span class="text-sm font-semibold">{{ t(day.labelKey) }}</span>
          <div v-if="isAdmin" class="flex gap-1">
            <Button
              icon="pi pi-plus"
              text
              rounded
              size="small"
              :aria-label="t('reservation.template.add')"
              :data-testid="`day-template-add-${day.value}`"
              @click="openCreate(day.value)"
            />
            <Button
              icon="pi pi-plus"
              text
              rounded
              size="small"
              severity="danger"
              :aria-label="t('reservation.recurring_block.add')"
              :data-testid="`day-recurring-add-${day.value}`"
              @click="openCreateRecurring(day.value)"
            />
          </div>
        </div>
        <div class="space-y-2 p-2">
          <!-- 枠テンプレ（青系） -->
          <div
            v-for="template in templatesByDay(day.value)"
            :key="template.id"
            class="flex items-center gap-3 rounded-lg border border-blue-200 bg-blue-50/60 p-3 dark:border-blue-900 dark:bg-blue-950/20"
            :class="template.isActive === false ? 'opacity-60' : ''"
          >
            <div class="min-w-0 flex-1">
              <p class="font-medium">
                {{ dayLabel(template.dayOfWeek) }}
                {{ toHm(template.startTime) }} - {{ formatEndTime(template.endTime, template.endsNextDay) }}
                <span class="ml-2 text-sm text-surface-500">
                  {{ template.lineId != null ? (template.lineName ?? '') : t('reservation.template.line_common') }}
                </span>
              </p>
              <div class="mt-0.5 flex flex-wrap gap-2 text-xs text-surface-500">
                <span>{{ t('reservation.template.capacity') }}: {{ template.capacity }}</span>
                <span v-if="template.cellCount != null">
                  {{ t('reservation.template.cell_count', { n: template.cellCount }) }}
                </span>
                <span v-if="template.isActive === false">{{ t('reservation.state.inactive') }}</span>
              </div>
            </div>
            <Button v-if="isAdmin" icon="pi pi-pencil" text rounded size="small" @click="openEdit(template)" />
            <Button v-if="isAdmin" icon="pi pi-trash" text rounded size="small" severity="danger" @click="remove(template)" />
          </div>

          <!-- 定期予約不可（赤系・事由ラベル付き・§4.4/§4.5） -->
          <div
            v-for="rule in rulesByDay(day.value)"
            :key="rule.id"
            class="flex items-center gap-3 rounded-lg border border-red-200 bg-red-50/60 p-3 dark:border-red-900 dark:bg-red-950/20"
            :class="rule.isActive === false ? 'opacity-60' : ''"
            :data-testid="`recurring-row-${rule.id}`"
          >
            <div class="min-w-0 flex-1">
              <p class="font-medium">
                {{ dayLabel(rule.dayOfWeek) }}
                {{ toHm(rule.startTime) }} - {{ formatEndTime(rule.endTime, rule.endsNextDay) }}
                <span class="ml-2 text-sm text-surface-500">
                  {{ rule.lineId != null ? (rule.lineName ?? '') : t('reservation.recurring_block.line_all') }}
                </span>
              </p>
              <div class="mt-0.5 flex flex-wrap items-center gap-2 text-xs text-surface-500">
                <span>{{ t('reservation.recurring_block.reason') }}: {{ rule.reason }}</span>
                <Tag v-if="rule.isPublic" :value="t('reservation.recurring_block.is_public')" severity="info" />
                <span v-if="rule.isActive === false">{{ t('reservation.state.inactive') }}</span>
              </div>
            </div>
            <Button
              v-if="isAdmin"
              :icon="rule.isActive === false ? 'pi pi-play' : 'pi pi-pause'"
              text
              rounded
              size="small"
              :data-testid="`recurring-toggle-${rule.id}`"
              @click="toggleRecurringActive(rule)"
            />
            <Button v-if="isAdmin" icon="pi pi-pencil" text rounded size="small" @click="openEditRecurring(rule)" />
            <Button v-if="isAdmin" icon="pi pi-trash" text rounded size="small" severity="danger" @click="removeRecurring(rule)" />
          </div>

          <p
            v-if="templatesByDay(day.value).length === 0 && rulesByDay(day.value).length === 0"
            class="text-xs text-surface-400"
          >
            {{ t('reservation.recurring_block.day_empty') }}
          </p>
        </div>
      </div>
    </div>
    <DashboardEmptyState
      v-else
      icon="pi pi-calendar-plus"
      :message="t('reservation.template.empty_state')"
      :sub-message="isAdmin
        ? (props.hasBusinessHours === false ? t('reservation.template.need_business_hours_hint') : t('reservation.template.empty_state_hint'))
        : undefined"
    >
      <template v-if="isAdmin" #action>
        <div class="flex flex-wrap justify-center gap-2">
          <Button
            v-if="props.hasBusinessHours === false"
            :label="t('reservation.business_hours.title')"
            icon="pi pi-clock"
            size="small"
            severity="secondary"
            outlined
            data-testid="focus-business-hours"
            @click="emit('focus-business-hours')"
          />
          <Button
            :label="t('reservation.template.add')"
            icon="pi pi-plus"
            size="small"
            @click="openCreate()"
          />
        </div>
      </template>
    </DashboardEmptyState>

    <!-- ドラッグ確定後の最小限確認（定員だけ。曜日・時刻は確定済みなので再入力させない） -->
    <Dialog
      v-model:visible="showDragConfirm"
      :header="t('reservation.template.grid.confirm_title')"
      :style="{ width: '360px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <p class="text-sm font-medium" data-testid="drag-range-label">{{ dragRangeLabel }}</p>
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.template.capacity') }}</label>
          <InputNumber v-model="dragCapacity" :min="1" :max="99" show-buttons class="w-full" />
        </div>
      </div>
      <template #footer>
        <Button :label="t('reservation.button.cancel')" text @click="showDragConfirm = false" />
        <Button
          :label="t('reservation.template.grid.create')"
          icon="pi pi-check"
          :loading="saving"
          data-testid="drag-create-confirm"
          @click="createFromDragRange"
        />
      </template>
    </Dialog>

    <!-- 作成・編集ダイアログ -->
    <Dialog
      v-model:visible="showDialog"
      :header="editingTemplate ? t('reservation.template.edit') : t('reservation.template.add')"
      :style="{ width: '440px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <!-- 対象ライン（共通枠含む） -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.template.line') }}</label>
          <Select
            v-model="form.lineId"
            :options="lineOptions"
            option-label="label"
            option-value="value"
            class="w-full"
          />
        </div>

        <!-- 曜日トグル（見た目は ScheduleEventRecurrenceInput 写経・value は 'MON' 形式）。
             新規作成時は複数選択可（選択曜日ぶん展開して作成）、編集時は既存行単位のまま単一選択。 -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.template.day_of_week') }} <span class="text-red-500">*</span>
          </label>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="d in RESERVATION_DAY_OPTIONS"
              :key="d.value"
              type="button"
              :data-day="d.value"
              class="h-8 w-8 rounded-full text-xs font-medium border transition-colors"
              :class="selectedDays.includes(d.value)
                ? 'bg-primary text-white border-primary'
                : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
              @click="toggleDay(d.value)"
            >
              {{ t(d.labelKey) }}
            </button>
          </div>
          <p v-if="selectedDays.length === 0" class="mt-1 text-xs text-surface-500">
            {{ t('reservation.template.error.day_required') }}
          </p>
        </div>

        <!-- 開始・終了時刻（30分刻み） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.template.time_range') }} <span class="text-red-500">*</span>
          </label>
          <div class="flex flex-wrap items-center gap-2">
            <Select
              v-model="form.startTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              class="w-32"
            />
            <span class="text-surface-400">-</span>
            <Select
              v-model="form.endTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              class="w-32"
            />
            <div class="flex items-center gap-2">
              <ToggleSwitch v-model="form.endsNextDay" data-testid="template-ends-next-day" />
              <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('reservation.template.ends_next_day') }}</span>
            </div>
          </div>
          <p v-if="!timeRangeValid" class="mt-1 text-xs text-amber-600 dark:text-amber-400">
            {{ t('reservation.template.error.time_range_invalid') }}
          </p>
        </div>

        <!-- 定員 -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.template.capacity') }}</label>
          <InputNumber v-model="form.capacity" :min="1" :max="99" show-buttons class="w-full" />
        </div>

        <!-- 有効/無効（編集時のみ） -->
        <div v-if="editingTemplate" class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ t('reservation.template.is_active') }}</label>
          <ToggleSwitch v-model="form.isActive" />
        </div>
      </div>

      <template #footer>
        <Button :label="t('reservation.button.cancel')" text @click="showDialog = false" />
        <Button
          :label="t('reservation.button.save')"
          icon="pi pi-check"
          :loading="saving"
          :disabled="saveDisabled"
          data-testid="template-save"
          @click="save"
        />
      </template>
    </Dialog>

    <!-- 定期予約不可 作成・編集ダイアログ（§4.5・ReservationUnavailabilityManager 金型写経） -->
    <Dialog
      v-model:visible="showRecurringDialog"
      :header="editingRule ? t('reservation.recurring_block.edit') : t('reservation.recurring_block.add')"
      :style="{ width: '460px' }"
      modal
    >
      <div class="flex flex-col gap-4">
        <!-- 曜日（単一選択・値は3文字大文字コード） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.template.day_of_week') }} <span class="text-red-500">*</span>
          </label>
          <Select
            v-model="recurringForm.dayOfWeek"
            :options="recurringDayOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="recurring-day-select"
          />
        </div>

        <!-- 対象ライン（既定=チーム全体） -->
        <div>
          <label class="mb-1 block text-sm font-medium">{{ t('reservation.template.line') }}</label>
          <Select
            v-model="recurringForm.lineId"
            :options="lineOptions"
            option-label="label"
            option-value="value"
            class="w-full"
            data-testid="recurring-line-select"
          />
        </div>

        <!-- 開始・終了時刻（30分刻み・全日型は作らせない＝show-clear を付けない） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.template.time_range') }} <span class="text-red-500">*</span>
          </label>
          <div class="flex flex-wrap items-center gap-2">
            <Select
              v-model="recurringForm.startTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              class="w-32"
              data-testid="recurring-start-time"
            />
            <span class="text-surface-400">-</span>
            <Select
              v-model="recurringForm.endTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              class="w-32"
              data-testid="recurring-end-time"
            />
            <div class="flex items-center gap-2">
              <ToggleSwitch v-model="recurringForm.endsNextDay" data-testid="recurring-ends-next-day" />
              <span class="text-sm text-surface-600 dark:text-surface-400">{{ t('reservation.template.ends_next_day') }}</span>
            </div>
          </div>
          <p v-if="!recurringTimeRangeValid" class="mt-1 text-xs text-amber-600 dark:text-amber-400">
            {{ t('reservation.template.error.time_range_invalid') }}
          </p>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('reservation.recurring_block.full_day_hint') }}
          </p>
        </div>

        <!-- 事由（必須・PII実防御プレースホルダ） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.recurring_block.reason') }} <span class="text-red-500">*</span>
          </label>
          <InputText
            v-model="recurringForm.reason"
            maxlength="100"
            :placeholder="t('reservation.recurring_block.reason_placeholder')"
            class="w-full"
            data-testid="recurring-reason"
          />
          <!-- is_public=ON 時のみ必須表示（§4.4/AC R-9・vitest: トグルON/OFFでの表示切替番人） -->
          <p
            v-if="recurringForm.isPublic"
            class="mt-1 text-xs text-amber-600 dark:text-amber-400"
            data-testid="recurring-reason-no-pii"
          >
            {{ t('reservation.recurring_block.reason_no_pii') }}
          </p>
        </div>

        <!-- 会員への公開可否 -->
        <div>
          <div class="flex items-center justify-between">
            <label class="text-sm font-medium">{{ t('reservation.recurring_block.is_public') }}</label>
            <ToggleSwitch v-model="recurringForm.isPublic" data-testid="recurring-is-public-toggle" />
          </div>
          <p class="mt-1 text-xs text-surface-500">
            {{ t('reservation.recurring_block.is_public_note') }}
          </p>
        </div>

        <!-- 有効/無効（編集時のみ・一時停止） -->
        <div v-if="editingRule" class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ t('reservation.template.is_active') }}</label>
          <ToggleSwitch v-model="recurringForm.isActive" />
        </div>

        <!-- impact 警告（90日 horizon の overlap active 予約プレビュー・§4.3） -->
        <Message v-if="recurringHasConflict" severity="warn" :closable="false">
          <div class="space-y-2">
            <p class="text-sm font-medium">
              {{ t('reservation.recurring_block.impact_warning', { n: recurringImpact?.affectedCount ?? 0 }) }}
            </p>
            <ul class="space-y-1 text-xs">
              <li
                v-for="r in recurringImpact?.reservations ?? []"
                :key="r.reservationId"
                class="flex flex-wrap gap-x-2"
              >
                <span class="font-medium">{{ r.userName }}</span>
                <span class="text-surface-500">{{ toHm(r.startTime) }} - {{ toHm(r.endTime) }}</span>
                <span v-if="r.staffName" class="text-surface-500">/ {{ r.staffName }}</span>
              </li>
            </ul>
            <!-- 強行登録（既定 false・破壊的操作のため確認ダイアログを必ず経由する・殿の裁定） -->
            <Button
              v-if="isAdmin"
              :label="t('reservation.recurring_block.force_cancel.button')"
              icon="pi pi-exclamation-triangle"
              size="small"
              severity="danger"
              outlined
              data-testid="recurring-force-cancel-button"
              @click="confirmForceSaveRecurring"
            />
          </div>
        </Message>
        <span v-if="recurringImpactLoading" class="text-xs text-surface-500">
          {{ t('reservation.unavailability.impact.checking') }}
        </span>
      </div>

      <template #footer>
        <Button :label="t('reservation.button.cancel')" text @click="showRecurringDialog = false" />
        <Button
          :label="t('reservation.button.save')"
          icon="pi pi-check"
          :loading="recurringSaving"
          :disabled="recurringSaveDisabled"
          data-testid="recurring-save"
          @click="saveRecurring()"
        />
      </template>
    </Dialog>
  </div>
</template>
