<script setup lang="ts">
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
  catch {
    // 取得失敗は注意書きを出さない方向にフォールバック（予約確定の可否自体は BE が最終判定するため、
    // 注意書きが出ないだけで機能不全にはならない。初回ロード時の一時的な失敗は想定内）。
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

/** BE エラー応答から RESERVATION_xxx コードを取り出す（GroupBookingDialog と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

async function submit() {
  if (!props.slotId || !props.lineId) return
  submitting.value = true
  try {
    await reservationApi.createReservation(props.teamId, {
      reservationSlotId: props.slotId,
      lineId: props.lineId,
      userNote: serviceNotes.value.trim() || undefined,
    })
    notification.success(t('reservation.message.reserve_success'))
    emit('reserved')
    close()
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
}
</script>

<template>
  <Dialog :visible="visible" :header="t('reservation.dialog.reserve_confirm')" :style="{ width: '400px' }" modal @update:visible="close">
    <div class="space-y-4">
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
    <template #footer>
      <Button :label="t('reservation.button.cancel')" text @click="close" />
      <Button :label="t('reservation.button.reserve')" icon="pi pi-check" :loading="submitting" @click="submit" />
    </template>
  </Dialog>
</template>
