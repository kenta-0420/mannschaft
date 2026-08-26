<script setup lang="ts">
/**
 * 例外日カレンダー（F03.4.5 §3.3）ADMIN限定。
 *
 * 【第一隊（骨格）→ 第二隊（本実装）】
 * 月表示ミニカレンダー（PrimeVue DatePicker inline）で日をクリックすると
 * 「この日を休業にする」「臨時営業する」の2択ダイアログを開く（§3.3）。
 *
 * - 休業にする（§3.3.1）: 新API無し。既存 POST /reservation-settings/blocked-times の
 *   ショートカット（blockedDate=選択日・startTime/endTime=null=全日・resourceType=TEAM）。
 *   登録前に GET /blocked-times/impact を全日条件で呼び、有効な予約が残っていれば
 *   警告カード＋登録ボタン disabled（BE の 409=RESERVATION_027 が最終防御）。
 * - 臨時営業する（§3.3.2）: POST .../generate-single-day（単日テンプレ適用・営業時間チェック省略）。
 *   適用する曜日ダイヤ（既定=選択日の実曜日・3文字正準コード）を選べる。
 *   同日に全日休業（TEAM・全日）が既にある場合は blocked_conflict 警告＋実行ボタン disabled
 *   （BE はブロックしない=生成はする・runtime で落とす方針のため、FE 入口で無意味な操作を止める）。
 *
 * 写経元: ReservationUnavailabilityManager.vue（impact 判定・409 ハンドリング）・
 * EmergencyClosureForm.vue（日付ユーティリティ）。
 * 曜日は必ず useReservationDayOptions の3文字正準コード（'MON'..'SUN'）を使う
 * （'MONDAY' 等のフルネーム混入は無音ゼロ生成の地雷）。
 */
import dayjs from 'dayjs'
import type { components } from '~/types/generated'
import { RESERVATION_DAY_OPTIONS } from '~/composables/useReservationDayOptions'
import type { ReservationDayOfWeekCode } from '~/composables/useReservationApi'

type BlockedTimeResponse = components['schemas']['BlockedTimeResponse']
type BlockedTimeImpactResponse = components['schemas']['BlockedTimeImpactResponse']

const props = defineProps<{
  teamId: string
}>()

/** 親（TeamReservationsPanel）へのタブ遷移導線。既存 manage-lines 等と同一パターン。 */
const emit = defineEmits<{
  gotoList: []
  gotoEmergencyClosure: []
  gotoBook: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const { userTimezone } = useDatetime()

/** 臨時営業の上限（BE ReservationSlotGenerationService.SINGLE_DAY_MAX_AHEAD_DAYS と一致）。 */
const SPECIAL_OPEN_MAX_AHEAD_DAYS = 90

function formatDate(date: Date): string {
  return dayjs(date).tz(userTimezone.value).format('YYYY-MM-DD')
}

/** 3文字正準コード（useReservationDayOptions は JS Date.getDay()=0(日)始まりと同じ並び）。 */
function dayOfWeekCodeOf(date: Date): ReservationDayOfWeekCode {
  return RESERVATION_DAY_OPTIONS[date.getDay()]!.value
}

function dayOfWeekLabel(code: ReservationDayOfWeekCode): string {
  const opt = RESERVATION_DAY_OPTIONS.find(d => d.value === code)
  return opt ? t(opt.labelKey) : code
}

/** ダイアログ見出し用（ロケール非依存の ISO 日付＋i18n化した曜日略称）。 */
function formatDateLabel(date: Date): string {
  return `${formatDate(date)} (${dayOfWeekLabel(dayOfWeekCodeOf(date))})`
}

// === カレンダー本体 ===
const todayDate = new Date()
const calendarModel = ref<Date | null>(null)
/** 日クリックで選ばれた対象日（2択ダイアログ〜各ダイアログで共有）。 */
const chosenDate = ref<Date | null>(null)
const chosenDateStr = computed(() => (chosenDate.value ? formatDate(chosenDate.value) : ''))
const chosenDateLabel = computed(() => (chosenDate.value ? formatDateLabel(chosenDate.value) : ''))

const showChoiceDialog = ref(false)
const showCloseDialog = ref(false)
const showSpecialDialog = ref(false)

function onDateSelect(date: Date) {
  chosenDate.value = date
  showChoiceDialog.value = true
}

function openCloseDialog() {
  showChoiceDialog.value = false
  showCloseDialog.value = true
}

function openSpecialDialog() {
  showChoiceDialog.value = false
  showSpecialDialog.value = true
}

// === 「この日を休業にする」ダイアログ（§3.3.1） ===
const closeReason = ref('')
const closeImpact = ref<BlockedTimeImpactResponse | null>(null)
const closeImpactLoading = ref(false)
const closeSubmitting = ref(false)

const closeHasConflict = computed(() => (closeImpact.value?.affectedCount ?? 0) > 0)
const closeSubmitDisabled = computed(() =>
  closeSubmitting.value || closeImpactLoading.value || closeHasConflict.value || !chosenDateStr.value,
)

async function loadCloseImpact() {
  if (!chosenDateStr.value) return
  closeImpactLoading.value = true
  closeImpact.value = null
  try {
    const res = await reservationApi.getBlockedTimeImpact(props.teamId, {
      date: chosenDateStr.value,
      resourceType: 'TEAM',
    })
    closeImpact.value = res.data
  }
  catch {
    // impact 取得失敗は登録可否に影響させない（BE の 409 が最終防御・写経元と同方針）
    closeImpact.value = null
  }
  finally {
    closeImpactLoading.value = false
  }
}

watch(showCloseDialog, (visible) => {
  if (!visible) return
  closeReason.value = ''
  void loadCloseImpact()
})

async function submitCloseDay() {
  if (closeSubmitDisabled.value || !chosenDateStr.value) return
  closeSubmitting.value = true
  try {
    await reservationApi.createBlockedTime(props.teamId, {
      blockedDate: chosenDateStr.value,
      resourceType: 'TEAM',
      reason: closeReason.value.trim() || undefined,
    })
    notification.success(t('reservation.unavailability.message.create_success'))
    showCloseDialog.value = false
  }
  catch (error) {
    const code = (error as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'RESERVATION_027') {
      // overlap する予約が残ったまま登録した競合（最終防御・写経元 ReservationUnavailabilityManager 同様）
      notification.error(t('dialog.error'), t('reservation.unavailability.error.has_active_reservations'))
      await loadCloseImpact()
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    closeSubmitting.value = false
  }
}

function gotoReservationsList() {
  showCloseDialog.value = false
  emit('gotoList')
}

function gotoEmergencyClosure() {
  showCloseDialog.value = false
  emit('gotoEmergencyClosure')
}

// === 「臨時営業する」ダイアログ（§3.3.2） ===
const sourceDayOfWeek = ref<ReservationDayOfWeekCode>('MON')
const dayOfWeekOptions = computed(() =>
  RESERVATION_DAY_OPTIONS.map(d => ({ label: t(d.labelKey), value: d.value })),
)

/** 同日の全日休業（機能B・TEAM軸）。存在すれば blocked_conflict 警告＋実行ボタン disabled（AC S-12）。 */
const blockedConflictItem = ref<BlockedTimeResponse | null>(null)
const blockedConflictLoading = ref(false)

const specialSubmitting = ref(false)
const specialDone = ref(false)
const specialGeneratedCount = ref(0)

/** 明日以降〜90日以内のみ有効（BE PAST_DATE_SLOT/90日上限とFE入口で一致させる）。 */
const specialDateValidity = computed(() => {
  if (!chosenDate.value) return false
  const today = dayjs().tz(userTimezone.value).startOf('day')
  const target = dayjs.tz(chosenDateStr.value, userTimezone.value).startOf('day')
  const diff = target.diff(today, 'day')
  return diff >= 1 && diff <= SPECIAL_OPEN_MAX_AHEAD_DAYS
})

async function loadBlockedConflict() {
  if (!chosenDateStr.value) return
  blockedConflictLoading.value = true
  blockedConflictItem.value = null
  try {
    const res = await reservationApi.listBlockedTimes(props.teamId, {
      from: chosenDateStr.value,
      to: chosenDateStr.value,
    })
    blockedConflictItem.value = (res.data ?? []).find(b =>
      b.resource?.resourceType === 'TEAM' && !b.timeSlot?.startTime && !b.timeSlot?.endTime,
    ) ?? null
  }
  catch {
    // 衝突確認の取得失敗はゼロ件扱い（BE は生成をブロックしない方針のため実行を阻害しない）
    blockedConflictItem.value = null
  }
  finally {
    blockedConflictLoading.value = false
  }
}

watch(showSpecialDialog, (visible) => {
  if (!visible) return
  specialDone.value = false
  specialGeneratedCount.value = 0
  if (chosenDate.value) sourceDayOfWeek.value = dayOfWeekCodeOf(chosenDate.value)
  void loadBlockedConflict()
})

const specialSubmitDisabled = computed(() =>
  specialSubmitting.value
  || blockedConflictLoading.value
  || !!blockedConflictItem.value
  || !specialDateValidity.value,
)

async function submitSpecialOpen() {
  if (specialSubmitDisabled.value || !chosenDateStr.value) return
  specialSubmitting.value = true
  try {
    const res = await reservationApi.generateSingleDaySlots(props.teamId, {
      date: chosenDateStr.value,
      sourceDayOfWeek: sourceDayOfWeek.value,
    })
    specialGeneratedCount.value = res.data.generatedCount ?? 0
    specialDone.value = true
    notification.success(
      t('reservation.exception_day.special_done', {
        date: chosenDateStr.value,
        generated: specialGeneratedCount.value,
      }),
    )
  }
  catch (error) {
    // 対象曜日 active テンプレ0件・90日超過は COMMON_001+fieldErrors で返る（§3.3.2）。
    // 汎用メッセージ「入力内容に不備があります」に丸めず、具体的なフィールドエラーを正直に表示する
    // （障害対応の原則：症状を隠さない）。
    const fieldMsg = (error as {
      data?: { error?: { fieldErrors?: Array<{ field: string; message: string }> } }
    })?.data?.error?.fieldErrors?.[0]?.message
    if (fieldMsg) {
      notification.error(t('dialog.error'), fieldMsg)
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    specialSubmitting.value = false
  }
}

async function deleteBlockedConflict() {
  if (!blockedConflictItem.value?.id) return
  // 写経元 ReservationUnavailabilityManager と同一パターン（多重ConfirmDialog回避のため native confirm を使用）
  if (!confirm(t('reservation.unavailability.dialog.delete_confirm'))) return
  try {
    await reservationApi.deleteBlockedTime(props.teamId, blockedConflictItem.value.id)
    notification.success(t('reservation.unavailability.message.delete_success'))
    blockedConflictItem.value = null
  }
  catch (error) {
    handleApiError(error)
  }
}

function gotoBookAfterSpecial() {
  showSpecialDialog.value = false
  emit('gotoBook')
}

// === 予約影響一覧の表示ヘルパー（写経元 ReservationUnavailabilityManager） ===
function toHm(value?: string | null): string {
  return value ? value.slice(0, 5) : ''
}

function statusLabel(status?: string | null): string {
  if (!status) return ''
  const key = `reservation.status.${status}`
  return t(key)
}
</script>

<template>
  <div class="space-y-4">
    <Message severity="secondary" :closable="false" class="text-sm">
      {{ t('reservation.exception_day.help') }}
    </Message>

    <div class="max-w-md">
      <DatePicker
        v-model="calendarModel"
        inline
        :min-date="todayDate"
        date-format="yy/mm/dd"
        class="w-full"
        @date-select="onDateSelect"
      />
    </div>

    <!-- 2択ダイアログ -->
    <Dialog
      v-model:visible="showChoiceDialog"
      modal
      :header="chosenDateLabel"
      :style="{ width: '380px' }"
    >
      <div class="flex flex-col gap-3">
        <Button
          data-testid="exception-choice-close"
          :label="t('reservation.exception_day.close_day')"
          icon="pi pi-ban"
          severity="danger"
          outlined
          @click="openCloseDialog"
        />
        <Button
          data-testid="exception-choice-special"
          :label="t('reservation.exception_day.open_special')"
          icon="pi pi-sun"
          outlined
          @click="openSpecialDialog"
        />
      </div>
    </Dialog>

    <!-- 「この日を休業にする」ダイアログ（§3.3.1） -->
    <Dialog
      v-model:visible="showCloseDialog"
      modal
      :header="`${t('reservation.exception_day.close_day')} — ${chosenDateLabel}`"
      :style="{ width: '480px' }"
    >
      <div class="flex flex-col gap-4">
        <div v-if="closeImpactLoading" class="text-xs text-surface-500">
          {{ t('reservation.unavailability.impact.checking') }}
        </div>

        <Message
          v-if="closeHasConflict"
          severity="warn"
          :closable="false"
        >
          <div class="space-y-2">
            <p class="text-sm font-medium">
              {{ t('reservation.exception_day.close_day_impact_warning', { n: closeImpact?.affectedCount ?? 0 }) }}
            </p>
            <ul class="space-y-1 text-xs">
              <li
                v-for="r in closeImpact?.reservations ?? []"
                :key="r.reservationId"
                class="flex flex-wrap items-center gap-x-2"
              >
                <span class="font-medium">{{ r.userName }}</span>
                <span class="text-surface-500">{{ toHm(r.startTime) }} - {{ toHm(r.endTime) }}</span>
                <span class="text-surface-500">({{ statusLabel(r.status) }})</span>
                <Button
                  data-testid="exception-close-goto-list"
                  :label="t('reservation.exception_day.goto_reservations')"
                  size="small"
                  text
                  @click="gotoReservationsList"
                />
              </li>
            </ul>
          </div>
        </Message>

        <!-- 理由（任意） -->
        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('reservation.unavailability.field.reason') }}
          </label>
          <InputText
            v-model="closeReason"
            data-testid="exception-close-reason"
            maxlength="200"
            :placeholder="t('reservation.unavailability.field.reason_placeholder')"
            class="w-full"
            :disabled="closeSubmitting"
          />
        </div>

        <!-- 緊急休業（会員告知）への誘導 -->
        <Button
          data-testid="exception-close-goto-emergency"
          :label="t('reservation.exception_day.notify_hint')"
          icon="pi pi-megaphone"
          text
          size="small"
          @click="gotoEmergencyClosure"
        />
      </div>

      <template #footer>
        <Button
          :label="t('reservation.button.cancel')"
          severity="secondary"
          text
          @click="showCloseDialog = false"
        />
        <Button
          data-testid="exception-close-submit"
          :label="t('reservation.exception_day.close_day')"
          icon="pi pi-ban"
          severity="danger"
          :loading="closeSubmitting"
          :disabled="closeSubmitDisabled"
          @click="submitCloseDay"
        />
      </template>
    </Dialog>

    <!-- 「臨時営業する」ダイアログ（§3.3.2） -->
    <Dialog
      v-model:visible="showSpecialDialog"
      modal
      :header="`${t('reservation.exception_day.open_special')} — ${chosenDateLabel}`"
      :style="{ width: '480px' }"
    >
      <div class="flex flex-col gap-4">
        <div v-if="blockedConflictLoading" class="text-xs text-surface-500">
          {{ t('reservation.unavailability.impact.checking') }}
        </div>

        <!-- 同日全日休業との衝突警告（AC S-12） -->
        <Message
          v-if="blockedConflictItem"
          severity="warn"
          :closable="false"
        >
          <div class="space-y-2">
            <p class="text-sm font-medium">
              {{ t('reservation.exception_day.blocked_conflict') }}
            </p>
            <Button
              data-testid="exception-conflict-delete"
              :label="t('reservation.unavailability.button.delete')"
              icon="pi pi-trash"
              severity="danger"
              size="small"
              outlined
              @click="deleteBlockedConflict"
            />
          </div>
        </Message>

        <!-- 適用する曜日ダイヤ -->
        <div>
          <label class="mb-1.5 block text-sm font-medium text-surface-700 dark:text-surface-300">
            {{ t('reservation.exception_day.special_source_day') }}
          </label>
          <Select
            v-model="sourceDayOfWeek"
            data-testid="exception-special-day-select"
            :options="dayOfWeekOptions"
            option-label="label"
            option-value="value"
            class="w-full sm:w-56"
            :disabled="specialSubmitting"
          />
        </div>

        <p v-if="!specialDateValidity" class="text-xs text-amber-600 dark:text-amber-400">
          {{ t('reservation.exception_day.date_range_hint') }}
        </p>

        <!-- 成功後の単日ビュー導線 -->
        <div v-if="specialDone" class="rounded-md bg-green-50 px-4 py-3 text-sm text-green-700 dark:bg-green-900/20 dark:text-green-400">
          <p class="mb-2 flex items-center gap-2">
            <i class="pi pi-check-circle" />
            {{ t('reservation.exception_day.special_done', { date: chosenDateStr, generated: specialGeneratedCount }) }}
          </p>
          <Button
            data-testid="exception-special-goto-book"
            :label="t('reservation.exception_day.goto_book')"
            icon="pi pi-calendar"
            size="small"
            @click="gotoBookAfterSpecial"
          />
        </div>
      </div>

      <template #footer>
        <Button
          :label="t('reservation.button.cancel')"
          severity="secondary"
          text
          @click="showSpecialDialog = false"
        />
        <Button
          v-if="!specialDone"
          data-testid="exception-special-submit"
          :label="t('reservation.exception_day.open_special')"
          icon="pi pi-sun"
          :loading="specialSubmitting"
          :disabled="specialSubmitDisabled"
          @click="submitSpecialOpen"
        />
      </template>
    </Dialog>
  </div>
</template>
