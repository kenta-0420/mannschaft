<script setup lang="ts">
/**
 * 営業時間管理（F03.4.5 §3.2）ADMIN限定・新設。
 *
 * - 曜日7行 ×（営業トグル isOpen・開始/終了30分刻み Select）。
 * - 保存は一括 PUT（`updateBusinessHours`）。応答は `BusinessHoursSaveResponse`
 *   （`hours` ＋ 変更曜日ぶんの同期自動生成結果 `generation`）。
 *   「今すぐ枠を作成」を押さずとも、営業時間の拡大で埋まるはずの枠が保存と同時に生成される
 *   （マスター指摘「保存したのに枠がない」の解消・§3.1 と同じ契約形）。
 * - 縮小方向（isOpen true→false・時間帯短縮）は遡及 CLOSE しない（既存の未来枠はそのまま残る。
 *   `ReservationBusinessHourService#updateBusinessHours` 実装で reservation_slots に一切触れないことを
 *   確認済み・§3.2 shrink_note）。保存前に検知したら confirm ダイアログで正直にガイドする。
 * - 生成失敗（`generation.failed=true`）は保存自体は成立の上でエラートーストへ正直に報告する
 *   （握りつぶし禁止・翌朝の日次バッチが自己修復）。
 *
 * 金型: ReservationPolicySettings.vue（defineProps<{teamId; disabled?}>+onMounted(load)+notification）。
 * ConfirmDialog は app.vue 一本化済みのため、ここでは `useConfirm().require()` を直呼びする
 * （新規 <ConfirmDialog> マウント禁止・LineManager.vue と同一パターン）。
 */
import type { components } from '~/types/generated'
import type { ReservationDayOfWeekCode, BusinessHoursUpdateHourInput } from '~/composables/useReservationApi'
import { RESERVATION_DAY_OPTIONS, buildHalfHourTimeOptions, toHm, hmToMinutes } from '~/composables/useReservationDayOptions'

type BusinessHourResponse = components['schemas']['BusinessHourResponse']

const props = defineProps<{
  teamId: string
  disabled?: boolean
}>()

const emit = defineEmits<{
  /** 保存成功時（週間スケジュール側の hasBusinessHours キャッシュ更新用）。 */
  saved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const confirm = useConfirm()

interface DayRow {
  dayOfWeek: ReservationDayOfWeekCode
  isOpen: boolean
  openTime: string
  closeTime: string
}

function defaultRow(dayOfWeek: ReservationDayOfWeekCode): DayRow {
  return { dayOfWeek, isOpen: false, openTime: '09:00', closeTime: '18:00' }
}

const loading = ref(true)
const saving = ref(false)
const rows = ref<DayRow[]>(RESERVATION_DAY_OPTIONS.map(d => defaultRow(d.value)))
/** 直近ロード/保存成功時点のスナップショット（縮小判定の突合基準・§3.2）。 */
const originalRows = ref<DayRow[]>(rows.value.map(r => ({ ...r })))

const timeOptions = computed(() => buildHalfHourTimeOptions())

function rowValid(row: DayRow): boolean {
  if (!row.isOpen) return true
  if (!row.openTime || !row.closeTime) return false
  return row.openTime < row.closeTime
}

const allValid = computed(() => rows.value.every(rowValid))
const saveDisabled = computed(() => props.disabled || saving.value || !allValid.value)

function fromResponse(list: BusinessHourResponse[]): DayRow[] {
  return RESERVATION_DAY_OPTIONS.map((d) => {
    const found = list.find(h => h.businessStatus?.dayOfWeek === d.value)
    if (!found?.businessStatus) return defaultRow(d.value)
    const status = found.businessStatus
    return {
      dayOfWeek: d.value,
      isOpen: status.isOpen ?? false,
      openTime: toHm(status.openTime) || '09:00',
      closeTime: toHm(status.closeTime) || '18:00',
    }
  })
}

async function load() {
  loading.value = true
  try {
    const res = await reservationApi.getBusinessHours(props.teamId)
    const next = fromResponse(res.data ?? [])
    rows.value = next
    originalRows.value = next.map(r => ({ ...r }))
  }
  catch {
    // 取得失敗: 既定値（全曜日休業）を維持（新規チームの 404 相当は想定内・エラー表示しない）
  }
  finally {
    loading.value = false
  }
}

/** 縮小方向の変更（isOpen true→false、または時間帯の短縮）を検知する（§3.2 shrink_note）。 */
function isShrinking(next: DayRow, original: DayRow): boolean {
  if (!original.isOpen) return false
  if (!next.isOpen) return true
  return hmToMinutes(next.openTime) > hmToMinutes(original.openTime)
    || hmToMinutes(next.closeTime) < hmToMinutes(original.closeTime)
}

const hasShrink = computed(() =>
  rows.value.some((row, i) => isShrinking(row, originalRows.value[i]!)),
)

function buildRequestBody(): BusinessHoursUpdateHourInput[] {
  return rows.value.map(row => ({
    dayOfWeek: row.dayOfWeek,
    isOpen: row.isOpen,
    // isOpen=false の曜日は時刻を送らない（BE validation は isOpen=true のときのみ範囲検証・§3.2）
    openTime: row.isOpen ? `${row.openTime}:00` : undefined,
    closeTime: row.isOpen ? `${row.closeTime}:00` : undefined,
  }))
}

async function doSave() {
  saving.value = true
  try {
    const res = await reservationApi.updateBusinessHours(props.teamId, buildRequestBody())
    const data = res.data
    const next = fromResponse(data?.hours ?? [])
    rows.value = next
    originalRows.value = next.map(r => ({ ...r }))

    const generation = data?.generation
    if (generation?.failed) {
      // 保存は成立済み・生成のみ失敗（§3.2 契約）。握りつぶさず正直に報告する。
      notification.warn(t('reservation.business_hours.title'), t('reservation.business_hours.generation_failed'))
    }
    else {
      const generated = generation?.generatedCount ?? 0
      const skippedOutside = generation?.skippedOutsideHoursCount ?? 0
      const skippedClosed = generation?.skippedClosedDayCount ?? 0
      if (generated === 0 && (skippedOutside > 0 || skippedClosed > 0)) {
        // 「保存したのに0件」の無言の混乱を防ぐ（S-11・原因を明示）
        notification.warn(t('reservation.business_hours.title'), t('reservation.template.generated_zero_hint'))
      }
      else {
        notification.success(
          t('reservation.business_hours.title'),
          t('reservation.business_hours.save_success', { generated }),
        )
      }
    }
    emit('saved')
  }
  catch (err) {
    handleApiError(err)
  }
  finally {
    saving.value = false
  }
}

function save() {
  if (saveDisabled.value) return
  if (hasShrink.value) {
    // 縮小/定休化の保存確認（§3.2・遡及なしを正直に伝える）。ConfirmDialog は app.vue 一本化済みのため直呼び。
    confirm.require({
      message: t('reservation.business_hours.shrink_note'),
      header: t('reservation.dialog.title'),
      icon: 'pi pi-exclamation-triangle',
      acceptLabel: t('reservation.button.save'),
      rejectLabel: t('reservation.button.cancel'),
      accept: () => { void doSave() },
    })
    return
  }
  void doSave()
}

onMounted(load)

defineExpose({ refresh: load })
</script>

<template>
  <div>
    <h3 class="mb-1 text-lg font-semibold">{{ t('reservation.business_hours.title') }}</h3>

    <div v-if="loading" class="space-y-2">
      <Skeleton v-for="i in 7" :key="i" height="2.75rem" width="100%" />
    </div>

    <template v-else>
      <div class="space-y-2">
        <div
          v-for="row in rows"
          :key="row.dayOfWeek"
          class="flex flex-wrap items-center gap-3 rounded-lg border border-surface-200 p-2.5 dark:border-surface-700"
          :data-testid="`business-hours-row-${row.dayOfWeek}`"
        >
          <span class="w-16 shrink-0 text-sm font-medium">
            {{ t(RESERVATION_DAY_OPTIONS.find(d => d.value === row.dayOfWeek)?.labelKey ?? '') }}
          </span>

          <div class="flex items-center gap-2">
            <ToggleSwitch
              v-model="row.isOpen"
              :input-id="`bh-open-${row.dayOfWeek}`"
              :disabled="disabled || saving"
              :data-testid="`business-hours-toggle-${row.dayOfWeek}`"
            />
            <label :for="`bh-open-${row.dayOfWeek}`" class="cursor-pointer text-sm text-surface-600 dark:text-surface-400">
              {{ t('reservation.business_hours.day_open') }}
            </label>
          </div>

          <template v-if="row.isOpen">
            <Select
              v-model="row.openTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              :disabled="disabled || saving"
              class="w-28"
            />
            <span class="text-surface-400">-</span>
            <Select
              v-model="row.closeTime"
              :options="timeOptions"
              option-label="label"
              option-value="value"
              :disabled="disabled || saving"
              class="w-28"
            />
          </template>
          <span v-else class="text-sm text-surface-500">{{ t('reservation.state.inactive') }}</span>

          <p v-if="!rowValid(row)" class="w-full text-xs text-amber-600 dark:text-amber-400">
            {{ t('reservation.template.error.time_range_invalid') }}
          </p>
        </div>
      </div>

      <p v-if="saving" class="mt-2 text-xs text-surface-500">
        {{ t('reservation.business_hours.generating') }}
      </p>

      <div class="mt-4 flex justify-end">
        <Button
          :label="t('reservation.button.save')"
          icon="pi pi-check"
          :loading="saving"
          :disabled="saveDisabled"
          data-testid="business-hours-save"
          @click="save"
        />
      </div>
    </template>
  </div>
</template>
