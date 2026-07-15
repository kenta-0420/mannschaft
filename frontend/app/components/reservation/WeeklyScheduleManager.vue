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
 * §4 の定期予約不可枠（赤系・事由ラベル付き併記表示）は W2-2（第二隊以降）でこのファイルへ統合する。
 *
 * 金型: LineManager.vue（CRUDダイアログ型）。最終ゲートは BE。
 */
import type { components } from '~/types/generated'
import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'
import { RESERVATION_DAY_OPTIONS, buildHalfHourTimeOptions, toHm } from '~/composables/useReservationDayOptions'

type SlotTemplateResponse = components['schemas']['SlotTemplateResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']
type SlotGenerationResultDto = components['schemas']['SlotGenerationResultDto']

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
  capacity: number
  isActive: boolean
}

function defaultForm(): TemplateForm {
  return {
    lineId: COMMON_LINE,
    startTime: '09:00',
    endTime: '10:00',
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
  !!form.value.startTime && !!form.value.endTime && form.value.startTime < form.value.endTime,
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

function openCreate() {
  editingTemplate.value = null
  form.value = defaultForm()
  selectedDays.value = []
  showDialog.value = true
}

function openEdit(template: SlotTemplateResponse) {
  editingTemplate.value = template
  form.value = {
    lineId: template.lineId ?? COMMON_LINE,
    startTime: toHm(template.startTime),
    endTime: toHm(template.endTime),
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

onMounted(async () => {
  await loadPermissions()
  await Promise.all([loadTemplates(), loadLines()])
})

// 親（TeamReservationsPanel）のアコーディオン件数バッジ用（既存 FriendFolderList 等と同一パターン）。
defineExpose({ refresh: loadTemplates, items: templates })
</script>

<template>
  <div>
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <h3 class="text-lg font-semibold">{{ t('reservation.template.title') }}</h3>
      <Button
        v-if="isAdmin"
        :label="t('reservation.template.add')"
        icon="pi pi-plus"
        size="small"
        data-testid="template-add"
        @click="openCreate"
      />
    </div>

    <!-- 保存直後の自動反映ガイド（regenerate_guide 改訂値・§11） -->
    <Message v-if="showRegenerateGuide && isAdmin" severity="info" :closable="true" class="mb-3 text-sm">
      {{ t('reservation.template.regenerate_guide') }}
    </Message>

    <!-- 一覧 -->
    <div v-if="loading"><Skeleton v-for="i in 3" :key="i" height="3rem" class="mb-2" /></div>
    <div v-else-if="templates.length > 0" class="space-y-2">
      <div
        v-for="template in templates"
        :key="template.id"
        class="flex items-center gap-3 rounded-lg border border-surface-300 p-3 dark:border-surface-600"
        :class="template.isActive === false ? 'opacity-60' : ''"
      >
        <div class="min-w-0 flex-1">
          <p class="font-medium">
            {{ dayLabel(template.dayOfWeek) }}
            {{ toHm(template.startTime) }} - {{ toHm(template.endTime) }}
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
            @click="openCreate"
          />
        </div>
      </template>
    </DashboardEmptyState>

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
  </div>
</template>
