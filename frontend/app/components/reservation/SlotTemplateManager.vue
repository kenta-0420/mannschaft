<script setup lang="ts">
/**
 * 予約枠 週間テンプレート管理（F03.4.2 §10）ADMIN限定
 *
 * - 曜日×時間帯×ライン（共通枠含む）×定員のテンプレート CRUD ＋「今すぐ枠を作成」動線。
 * - 曜日トグルの見た目は ScheduleEventRecurrenceInput.vue の写経。
 *   **ただし写経元は 'MONDAY' フルネームを emit するため、value は必ず3文字大文字
 *   'MON'..'SUN'（BE の ReservationDayOfWeek enum）へ変換して API に送る**
 *   （フルネーム送信は Jackson デシリアライズ失敗で 400 — 設計書 §4/§10 明記）。
 * - テンプレ保存の成功後は regenerate_guide を表示し、「今すぐ枠を作成」
 *   （POST /reservation-slot-templates/generate・冪等）へ誘導する。
 * - エラーは BE コードで判定して表示（握りつぶし禁止）:
 *     RESERVATION_037(400) 上限500行 / RESERVATION_044(429) generate レートリミット。
 *
 * 金型: LineManager.vue（CRUDダイアログ型）。最終ゲートは BE。
 */
import type { components } from '~/types/generated'
import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'

type SlotTemplateResponse = components['schemas']['SlotTemplateResponse']
type ReservationLineResponse = components['schemas']['ReservationLineResponse']

const props = defineProps<{
  teamId: string
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
// 多重防御（defense-in-depth）: 親タブの v-if に加え、破壊的操作ボタンを本コンポーネントでも
// ロールで制御する。BE が本防御線だが、別画面から再利用された際の誤表示を防ぐ。
const { isAdmin, loadPermissions } = useRoleAccess('team', computed(() => props.teamId))

/**
 * 曜日トグルの選択肢。ラベルは既存 schedule.recurrence.days.* を再利用（新設しない）。
 * value は BE 正準の3文字大文字コード（'MONDAY' 等のフルネームは送らない）。
 */
const DAY_OPTIONS: ReadonlyArray<{ value: ReservationDayOfWeekCode; labelKey: string }> = [
  { value: 'SUN', labelKey: 'schedule.recurrence.days.SUNDAY' },
  { value: 'MON', labelKey: 'schedule.recurrence.days.MONDAY' },
  { value: 'TUE', labelKey: 'schedule.recurrence.days.TUESDAY' },
  { value: 'WED', labelKey: 'schedule.recurrence.days.WEDNESDAY' },
  { value: 'THU', labelKey: 'schedule.recurrence.days.THURSDAY' },
  { value: 'FRI', labelKey: 'schedule.recurrence.days.FRIDAY' },
  { value: 'SAT', labelKey: 'schedule.recurrence.days.SATURDAY' },
]

/** 共通枠（lineId なし）を表すフォーム用センチネル値 */
const COMMON_LINE = -1

const templates = ref<SlotTemplateResponse[]>([])
const lines = ref<ReservationLineResponse[]>([])
const loading = ref(true)
const saving = ref(false)
const generating = ref(false)
const showDialog = ref(false)
const editingTemplate = ref<SlotTemplateResponse | null>(null)
/** 保存直後に「今すぐ枠を作成」への誘導ガイドを出す */
const showRegenerateGuide = ref(false)

const generateWeeks = ref(4)

interface TemplateForm {
  lineId: number
  dayOfWeek: ReservationDayOfWeekCode | null
  startTime: string
  endTime: string
  capacity: number
  isActive: boolean
}

function defaultForm(): TemplateForm {
  return {
    lineId: COMMON_LINE,
    dayOfWeek: null,
    startTime: '09:00',
    endTime: '10:00',
    capacity: 1,
    isActive: true,
  }
}

const form = ref<TemplateForm>(defaultForm())

/** 30分刻みの時刻オプション（00:00〜23:30） */
const timeOptions = computed(() => {
  const opts: Array<{ label: string; value: string }> = []
  for (let h = 0; h < 24; h++) {
    for (const m of [0, 30]) {
      const v = `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
      opts.push({ label: v, value: v })
    }
  }
  return opts
})

const generateWeeksOptions = computed(() =>
  [1, 2, 3, 4].map(w => ({ label: String(w), value: w })),
)

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
  || form.value.dayOfWeek == null
  || !timeRangeValid.value
  || form.value.capacity < 1
  || form.value.capacity > 99,
)

/** active テンプレが1件もない場合は generate を実行できない（BE も 400） */
const hasActiveTemplates = computed(() => templates.value.some(tp => tp.isActive !== false))

function toHm(value?: string | null): string {
  return value ? value.slice(0, 5) : ''
}

function dayLabel(code?: string | null): string {
  const opt = DAY_OPTIONS.find(d => d.value === code)
  return opt ? t(opt.labelKey) : (code ?? '')
}

function selectDay(day: ReservationDayOfWeekCode) {
  form.value.dayOfWeek = day
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
  showDialog.value = true
}

function openEdit(template: SlotTemplateResponse) {
  editingTemplate.value = template
  form.value = {
    lineId: template.lineId ?? COMMON_LINE,
    dayOfWeek: DAY_OPTIONS.find(d => d.value === template.dayOfWeek)?.value ?? null,
    startTime: toHm(template.startTime),
    endTime: toHm(template.endTime),
    capacity: template.capacity ?? 1,
    isActive: template.isActive ?? true,
  }
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

async function save() {
  if (saveDisabled.value || form.value.dayOfWeek == null) return
  saving.value = true
  // dayOfWeek は必ず3文字大文字（'MON'..'SUN'）。時刻は既存 SlotFormDialog と同じ HH:mm:00。
  const base = {
    dayOfWeek: form.value.dayOfWeek,
    startTime: `${form.value.startTime}:00`,
    endTime: `${form.value.endTime}:00`,
    capacity: form.value.capacity,
  }
  try {
    if (editingTemplate.value?.id) {
      // PATCH: 共通枠へ戻す場合は clearLineId で明示（null 据え置きと区別）
      await reservationApi.updateSlotTemplate(props.teamId, editingTemplate.value.id, {
        ...base,
        ...(form.value.lineId === COMMON_LINE
          ? { clearLineId: true }
          : { lineId: form.value.lineId }),
        isActive: form.value.isActive,
      })
      notification.success(t('reservation.message.template_update_success'))
    }
    else {
      await reservationApi.createSlotTemplate(props.teamId, {
        ...base,
        ...(form.value.lineId === COMMON_LINE ? {} : { lineId: form.value.lineId }),
      })
      notification.success(t('reservation.message.template_create_success'))
    }
    showDialog.value = false
    // 保存 → 生成は別操作。反映には「今すぐ枠を作成」が必要なことを案内する（§5.4 regenerate_guide 統合）
    showRegenerateGuide.value = true
    await loadTemplates()
  }
  catch (err) {
    notifySaveError(err)
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

/** 今すぐ枠を作成（一括生成・冪等）。結果カウントをトーストで報告する。 */
async function generateNow() {
  if (generating.value || !hasActiveTemplates.value) return
  generating.value = true
  try {
    const res = await reservationApi.generateSlotsFromTemplates(props.teamId, {
      weeks: generateWeeks.value,
    })
    const d = res.data
    notification.success(
      t('reservation.template.generate'),
      t('reservation.template.generate_result', {
        generated: d.generatedCount ?? 0,
        skipped: d.skippedExistingCount ?? 0,
      }),
    )
    if ((d.skippedClosedDayCount ?? 0) > 0) {
      notification.info(
        t('reservation.template.generate'),
        t('reservation.template.generate_skipped_closed', { n: d.skippedClosedDayCount }),
      )
    }
    showRegenerateGuide.value = false
  }
  catch (err) {
    const apiError = err as { data?: { error?: { code?: string } }; statusCode?: number }
    if (apiError?.data?.error?.code === 'RESERVATION_044' || apiError?.statusCode === 429) {
      // generate レートリミット（RESERVATION_044・429）
      notification.warn(
        t('reservation.template.generate'),
        t('reservation.template.generate_rate_limited'),
      )
    }
    else {
      handleApiError(err)
    }
  }
  finally {
    generating.value = false
  }
}

onMounted(async () => {
  await loadPermissions()
  await Promise.all([loadTemplates(), loadLines()])
})
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

    <!-- 保存直後の反映ガイド（regenerate_guide 統合・§5.4） -->
    <Message v-if="showRegenerateGuide && isAdmin" severity="info" :closable="true" class="mb-3 text-sm">
      {{ t('reservation.template.regenerate_guide') }}
    </Message>

    <!-- 今すぐ枠を作成（一括生成・冪等） -->
    <div
      v-if="isAdmin && templates.length > 0"
      class="mb-4 flex flex-wrap items-center gap-2 rounded-lg border border-surface-200 p-3 dark:border-surface-700"
    >
      <label class="text-sm text-surface-600 dark:text-surface-400">
        {{ t('reservation.template.generate_weeks') }}
      </label>
      <Select
        v-model="generateWeeks"
        :options="generateWeeksOptions"
        option-label="label"
        option-value="value"
        class="w-20"
      />
      <Button
        :label="t('reservation.template.generate')"
        icon="pi pi-bolt"
        size="small"
        :loading="generating"
        :disabled="!hasActiveTemplates"
        data-testid="generate-now"
        @click="generateNow"
      />
    </div>

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
      :sub-message="isAdmin ? t('reservation.template.empty_state_hint') : undefined"
    >
      <template v-if="isAdmin" #action>
        <Button
          :label="t('reservation.template.add')"
          icon="pi pi-plus"
          size="small"
          @click="openCreate"
        />
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

        <!-- 曜日トグル（見た目は ScheduleEventRecurrenceInput 写経・value は 'MON' 形式） -->
        <div>
          <label class="mb-1 block text-sm font-medium">
            {{ t('reservation.template.day_of_week') }} <span class="text-red-500">*</span>
          </label>
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="d in DAY_OPTIONS"
              :key="d.value"
              type="button"
              :data-day="d.value"
              class="h-8 w-8 rounded-full text-xs font-medium border transition-colors"
              :class="form.dayOfWeek === d.value
                ? 'bg-primary text-white border-primary'
                : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
              @click="selectDay(d.value)"
            >
              {{ t(d.labelKey) }}
            </button>
          </div>
          <p v-if="form.dayOfWeek == null" class="mt-1 text-xs text-surface-500">
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
