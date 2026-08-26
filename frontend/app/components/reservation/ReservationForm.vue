<script setup lang="ts">
import type { RecurringSeriesDto } from '~/types/reservation'

/** 段階開示: 4週までを推奨として前面に出し、5〜12週は「もっと長く」の一段奥に置く（AC-5-14・§6.2）。 */
const RECURRING_QUICK_WEEKS = [2, 3, 4] as const
/** 5〜12週（「もっと長く」を開いたときのみ表示）。12を超える値はUI上そもそも作れない。 */
const RECURRING_EXTENDED_WEEKS = [5, 6, 7, 8, 9, 10, 11, 12] as const
const RECURRING_SKIP_REASONS = [
  'NOT_GENERATED', 'FULL', 'CLOSED', 'BLOCKED', 'ALREADY_RESERVED',
  'UNAVAILABLE', 'NOT_CANCELLABLE', 'CANCEL_DEADLINE_PASSED', 'NOT_PENDING',
] as const

const props = defineProps<{
  teamId: string
  slotId: number | null
  lineId: number | null
  lineName: string
  date: string
  startTime: string
  endTime: string
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  reserved: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
/** 静かなエラー記録（トーストは出さずバックエンドへ送信。WidgetAttendanceResults.vue 等と同一パターン）。 */
const { captureQuiet } = useErrorReport()

/** 呼称の動的差し込み（F03.4.5 §5.2）: 予約確認の予約対象ラベルに使う。 */
const { resourceName, load: loadResourceName } = useResourceName(computed(() => props.teamId))

/**
 * 仮押さえ(PENDING)自動失効の会員向け注意書き（F03.4.5 §6.3 W2-6-FE）。
 * GET /reservation-settings は ADMIN 限定ではなく view ゲート（会員/公開）のため会員側からも読める
 * （`ReservationBusinessHourController` の `viewAccessGuard` Javadoc に明記）。
 * approvalMode=MANUAL かつ pendingExpireHours が非 NULL のときのみ表示する
 * （AUTO は仮押さえが発生せず無意味・自動失効なし設定のチームは誤情報になるため出さない）。
 */
const pendingExpireApprovalMode = ref<'AUTO' | 'MANUAL' | undefined>(undefined)
const pendingExpireHours = ref<number | null>(null)

async function loadPendingExpireNotice() {
  try {
    const res = await reservationApi.getReservationSettings(props.teamId)
    pendingExpireApprovalMode.value = res.data.approvalMode
    pendingExpireHours.value = res.data.pendingExpireHours ?? null
  }
  catch (error) {
    // 取得失敗は注意書きを出さない方向にフォールバック（予約確定の可否自体は BE が最終判定するため、
    // 注意書きが出ないだけで機能不全にはならない）。ただし完全に握りつぶすと恒常的な失敗が誰にも
    // 見えなくなるため、ユーザーには出さずバックエンドへ静かに記録する（captureQuiet・症状を隠さない）。
    captureQuiet(error, { context: 'ReservationForm: 仮押さえ失効設定の取得に失敗' })
    pendingExpireApprovalMode.value = undefined
    pendingExpireHours.value = null
  }
}

const showPendingExpireNotice = computed(
  () => pendingExpireApprovalMode.value === 'MANUAL' && pendingExpireHours.value != null,
)

// ダイアログを開くたびに最新の呼称・仮押さえ失効設定を取得する（設定変更が即時反映されるように）。
watch(() => props.visible, (v) => {
  if (v) {
    void loadResourceName()
    void loadPendingExpireNotice()
  }
}, { immediate: true })

const submitting = ref(false)
const serviceNotes = ref('')

/**
 * 定期予約（毎週繰り返し・§6.2 W2-5-FE）。段階開示: 既定 OFF・ON にすると週数選択が現れる。
 * 4週までを推奨表示（quick pills）、5〜12週は「もっと長く」を開いた先（一段奥）にのみ現れる。
 * 12を超える値は選択肢自体に存在しないため、FE 側でも送信できない（BE の 400 に頼り切らない）。
 */
const repeatEnabled = ref(false)
const repeatWeeks = ref<number>(4)
const showMoreWeeks = ref(false)
/** 作成結果の明細（repeatWeeks>=2 の応答でのみ非null）。非null の間はダイアログを結果表示に切り替える。 */
const recurringResult = ref<RecurringSeriesDto | null>(null)

function selectQuickWeeks(weeks: number) {
  repeatWeeks.value = weeks
  showMoreWeeks.value = false
}

/** スキップ理由(9値enum)を i18n 文言へ変換する。丸めずに全値を出し分ける（症状を隠さない原則）。 */
function skipReasonLabel(reason?: string): string {
  return reason && (RECURRING_SKIP_REASONS as readonly string[]).includes(reason)
    ? t(`reservation.recurring.skip_reason.${reason}`)
    : (reason ?? '')
}

/** BE エラー応答から RESERVATION_xxx コードを取り出す（GroupBookingDialog と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

async function submit() {
  if (!props.slotId || !props.lineId) return
  submitting.value = true
  try {
    const res = await reservationApi.createReservation(props.teamId, {
      reservationSlotId: props.slotId,
      lineId: props.lineId,
      userNote: serviceNotes.value.trim() || undefined,
      repeatWeeks: repeatEnabled.value ? repeatWeeks.value : undefined,
    })
    if (res.data.recurring) {
      // 定期予約: 成立0件（全週スキップ）でもエラーにせず、理由つき明細をダイアログ内に留めて見せる
      // （「予約できませんでした」だけで終わらせない・症状を隠さない原則）。close() はここでは呼ばない。
      recurringResult.value = res.data.recurring
      emit('reserved')
    }
    else {
      notification.success(t('reservation.message.reserve_success'))
      emit('reserved')
      close()
    }
  }
  catch (error) {
    // 429=RESERVATION_053（予約作成レートリミット・W2-6 §6.4）は汎用文言でなく専用文言で案内する。
    if (extractErrorCode(error) === 'RESERVATION_053') {
      notification.error(t('reservation.message.rate_limited'))
    }
    else {
      notification.error(t('reservation.message.reserve_failed'))
    }
  }
  finally { submitting.value = false }
}

function close() {
  emit('update:visible', false)
  serviceNotes.value = ''
  repeatEnabled.value = false
  repeatWeeks.value = 4
  showMoreWeeks.value = false
  recurringResult.value = null
}
</script>

<template>
  <Dialog :visible="visible" :header="t('reservation.dialog.reserve_confirm')" :style="{ width: '400px' }" modal @update:visible="close">
    <!-- 通常の予約フォーム（結果明細表示中は隠す） -->
    <div v-if="!recurringResult" class="space-y-4">
      <div class="rounded-lg bg-surface-50 p-4 dark:bg-surface-700/50">
        <div class="space-y-2 text-sm">
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.line', { resourceName }) }}</span><span class="font-medium">{{ lineName }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.date') }}</span><span class="font-medium">{{ date }}</span></div>
          <div class="flex justify-between"><span class="text-surface-500">{{ t('reservation.field.time') }}</span><span class="font-medium">{{ startTime }} - {{ endTime }}</span></div>
        </div>
      </div>
      <div>
        <label class="mb-1 block text-sm font-medium">{{ t('reservation.field.note') }}</label>
        <Textarea v-model="serviceNotes" rows="2" class="w-full" :placeholder="t('reservation.placeholder.note')" />
      </div>

      <!-- 定期予約（毎週繰り返し・§6.2 W2-5-FE）: 段階開示トグル -->
      <div>
        <div class="flex items-center justify-between">
          <label class="text-sm font-medium">{{ t('reservation.recurring.toggle_label') }}</label>
          <ToggleSwitch v-model="repeatEnabled" data-testid="recurring-toggle" />
        </div>
        <div v-if="repeatEnabled" class="mt-2 space-y-2">
          <div class="flex flex-wrap gap-1.5">
            <button
              v-for="w in RECURRING_QUICK_WEEKS"
              :key="w"
              type="button"
              class="h-8 rounded-full border px-3 text-xs font-medium transition-colors"
              :class="repeatWeeks === w && !showMoreWeeks
                ? 'bg-primary text-white border-primary'
                : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
              :data-testid="`recurring-weeks-${w}`"
              @click="selectQuickWeeks(w)"
            >
              {{ t('reservation.recurring.weeks_option', { n: w }) }}
            </button>
            <button
              type="button"
              class="h-8 rounded-full border px-3 text-xs font-medium transition-colors"
              :class="showMoreWeeks
                ? 'bg-primary text-white border-primary'
                : 'border-surface-300 dark:border-surface-600 text-surface-600 dark:text-surface-300 hover:border-primary'"
              data-testid="recurring-weeks-more-toggle"
              @click="showMoreWeeks = !showMoreWeeks"
            >
              {{ t('reservation.recurring.weeks_more') }}
            </button>
          </div>
          <div v-if="showMoreWeeks" class="flex items-center gap-2">
            <label class="text-sm text-surface-500">{{ t('reservation.recurring.weeks_label') }}</label>
            <Select
              v-model="repeatWeeks"
              :options="[...RECURRING_EXTENDED_WEEKS]"
              class="w-24"
              data-testid="recurring-weeks-select"
            />
          </div>
          <p class="text-xs text-surface-500">
            {{ t('reservation.recurring.horizon_hint') }}
          </p>
        </div>
      </div>

      <!-- 仮押さえ(PENDING)自動失効の会員向け注意書き（F03.4.5 §6.3 W2-6-FE）。
           承認制(MANUAL)かつ自動失効が有効(pendingExpireHours非NULL)のときのみ表示する。 -->
      <Message
        v-if="showPendingExpireNotice"
        severity="info"
        :closable="false"
        data-testid="pending-expire-notice"
      >
        {{ t('reservation.pending_expire_notice.form_note', { n: pendingExpireHours }) }}
      </Message>
    </div>

    <!-- 定期予約の結果明細（成立0件でも黙殺せず理由つきで見せる・§6.2 W2-5-FE） -->
    <div v-else class="space-y-3" data-testid="recurring-result-panel">
      <p class="font-medium" data-testid="recurring-result-summary">
        {{ t('reservation.recurring.result.summary', {
          created: recurringResult.createdCount ?? 0,
          skipped: recurringResult.skippedCount ?? recurringResult.skippedWeeks?.length ?? 0,
        }) }}
      </p>
      <Message v-if="(recurringResult.createdCount ?? 0) === 0" severity="warn" :closable="false">
        {{ t('reservation.recurring.result.all_skipped_notice') }}
      </Message>
      <div
        v-if="(recurringResult.skippedWeeks?.length ?? 0) > 0"
        class="rounded-lg bg-surface-50 p-3 text-sm dark:bg-surface-700/50"
      >
        <p class="mb-1 font-medium text-surface-600 dark:text-surface-300">
          {{ t('reservation.recurring.result.skipped_list_title') }}
        </p>
        <ul class="space-y-1">
          <li
            v-for="(w, idx) in recurringResult.skippedWeeks"
            :key="`${w.date}-${idx}`"
            class="flex justify-between gap-2 text-xs"
          >
            <span>{{ w.date }}</span>
            <span class="text-surface-500">{{ skipReasonLabel(w.reason) }}</span>
          </li>
        </ul>
      </div>
    </div>

    <template #footer>
      <template v-if="!recurringResult">
        <Button :label="t('reservation.button.cancel')" text @click="close" />
        <Button :label="t('reservation.button.reserve')" icon="pi pi-check" :loading="submitting" @click="submit" />
      </template>
      <template v-else>
        <Button :label="t('reservation.recurring.close_button')" data-testid="recurring-result-close" @click="close" />
      </template>
    </template>
  </Dialog>
</template>
