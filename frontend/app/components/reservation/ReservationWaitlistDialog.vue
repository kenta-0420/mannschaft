<script setup lang="ts">
/**
 * キャンセル待ち（waitlist）登録・取消ダイアログ（F03.4.5 §6.1 W2-4-FE）。
 *
 * SlotMatrixPicker / SlotGridPicker の満席（BOOKED）セルクリックから開く。
 * - 未登録: 「キャンセル待ちに登録」ボタン → POST
 * - 登録済み（親が渡す registeredSlotIds に slotId が含まれる）: 「キャンセル待ちを取消」ボタン → DELETE
 * - ADMIN: 開いたタイミングで枠別件数（ADMIN専用API）を取得して表示する（グリッド全セル分の
 *   N+1呼び出しを避けるため、ダイアログを開いたときのみ1回だけ呼ぶ）
 *
 * 文言の禁止事項（設計確定・裏目付済み）: 「優先的に確保」「自動で予約されます」等の独占確保／自動繰り上げの
 * 表現は使わない。実装は「空きが出たら WAITING 全員に通知が飛び、早い者勝ちで各自が予約する」方式のため、
 * 文言もそれに合わせる（reservation.waitlist.notice）。
 */
export interface WaitlistDialogContext {
  slotId: number
  date: string
  startTime: string
  endTime: string
  lineName: string
}

const props = defineProps<{
  visible: boolean
  teamId: string
  isAdmin: boolean
  resourceName: string
  context: WaitlistDialogContext | null
  /** このチームで自分が WAITING 登録済みの slotId 集合（親が listMyWaitlist から算出）。 */
  registeredSlotIds: Set<number>
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  /** 登録/取消の成功、または「空きあり」判明時。親はグリッド＋自分の登録集合を再取得する。 */
  changed: []
}>()

const { t } = useI18n()
const reservationApi = useReservationApi()
const notification = useNotification()
const { handleApiError } = useErrorHandler()

const submitting = ref(false)
const adminCount = ref<number | null>(null)
const adminCountLoading = ref(false)

const isRegistered = computed(() => props.context != null && props.registeredSlotIds.has(props.context.slotId))

function fmt(time: string): string {
  return time.length >= 5 ? time.slice(0, 5) : time
}

/** ダイアログを開いたときのみ1回呼ぶ（ADMIN専用API・グリッド全セル分のN+1回避）。 */
async function loadAdminCount() {
  if (!props.isAdmin || !props.context) return
  adminCountLoading.value = true
  try {
    const res = await reservationApi.getWaitlistCount(props.teamId, props.context.slotId)
    adminCount.value = res.data.waitingCount ?? 0
  }
  catch (error) {
    adminCount.value = null
    handleApiError(error)
  }
  finally {
    adminCountLoading.value = false
  }
}

watch(() => props.visible, (v) => {
  if (v) {
    adminCount.value = null
    void loadAdminCount()
  }
})

/** BE エラー応答から RESERVATION_xxx コードを取り出す（GroupBookingDialog と同じ抽出パターン）。 */
function extractErrorCode(error: unknown): string | undefined {
  const apiError = error as { data?: { error?: { code?: string } } }
  return apiError?.data?.error?.code
}

function close() {
  emit('update:visible', false)
}

async function register() {
  if (!props.context) return
  submitting.value = true
  try {
    await reservationApi.joinWaitlist(props.teamId, props.context.slotId)
    notification.success(t('reservation.waitlist.register_success'))
    emit('changed')
    close()
  }
  catch (error) {
    const code = extractErrorCode(error)
    switch (code) {
      case 'RESERVATION_047':
        // 既に登録済み（例: 別タブでの操作等）。表示を最新化する。
        notification.error(t('reservation.waitlist.already_registered'))
        emit('changed')
        close()
        break
      case 'RESERVATION_048':
        // 満席でなくなっていた（空きあり）。そのまま予約を促す。
        notification.info(t('reservation.waitlist.slot_not_full'))
        emit('changed')
        close()
        break
      case 'RESERVATION_049':
        notification.error(t('reservation.waitlist.limit_exceeded'))
        break
      case 'RESERVATION_050':
        notification.error(t('reservation.waitlist.rate_limited'))
        break
      default:
        handleApiError(error)
    }
  }
  finally {
    submitting.value = false
  }
}

async function cancelWaiting() {
  if (!props.context) return
  submitting.value = true
  try {
    await reservationApi.leaveWaitlist(props.teamId, props.context.slotId)
    notification.success(t('reservation.waitlist.cancel_success'))
    emit('changed')
    close()
  }
  catch (error) {
    if (extractErrorCode(error) === 'RESERVATION_046') {
      // 既に取消/変換済み。表示を最新化する。
      notification.error(t('reservation.waitlist.not_found'))
      emit('changed')
      close()
    }
    else {
      handleApiError(error)
    }
  }
  finally {
    submitting.value = false
  }
}
</script>

<template>
  <Dialog :visible="visible" :header="t('reservation.waitlist.dialog_title')" :style="{ width: '380px' }" modal @update:visible="close">
    <div v-if="context" class="space-y-3">
      <div class="rounded-lg bg-surface-50 p-4 text-sm dark:bg-surface-700/50">
        <div class="flex justify-between">
          <span class="text-surface-500">{{ t('reservation.field.date') }}</span>
          <span class="font-medium">{{ context.date }}</span>
        </div>
        <div class="flex justify-between">
          <span class="text-surface-500">{{ t('reservation.field.time') }}</span>
          <span class="font-medium">{{ fmt(context.startTime) }} - {{ fmt(context.endTime) }}</span>
        </div>
        <div v-if="context.lineName" class="flex justify-between">
          <span class="text-surface-500">{{ t('reservation.field.line', { resourceName }) }}</span>
          <span class="font-medium">{{ context.lineName }}</span>
        </div>
      </div>
      <p class="text-xs text-surface-600 dark:text-surface-300">
        {{ t('reservation.waitlist.notice') }}
      </p>
      <p v-if="isAdmin" class="text-xs text-surface-500" data-testid="waitlist-admin-count">
        <template v-if="adminCountLoading">…</template>
        <template v-else-if="adminCount != null">{{ t('reservation.waitlist.admin_count', { n: adminCount }) }}</template>
      </p>
    </div>
    <template #footer>
      <Button :label="t('reservation.button.cancel')" text @click="close" />
      <Button
        v-if="isRegistered"
        data-testid="waitlist-cancel"
        :label="t('reservation.waitlist.cancel_button')"
        severity="secondary"
        :loading="submitting"
        @click="cancelWaiting"
      />
      <Button
        v-else
        data-testid="waitlist-register"
        :label="t('reservation.waitlist.register_button')"
        icon="pi pi-bell"
        :loading="submitting"
        @click="register"
      />
    </template>
  </Dialog>
</template>
