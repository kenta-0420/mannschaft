<script setup lang="ts">
/**
 * Billing Center PR6a — 解約予約（cancel_at_period_end）の確認ダイアログ・撤回導線。
 *
 * 正本: docs/features（Billing Center PR6a 05:30, 05:340, 05:359, 04_ui_i18n.md:175）。
 *
 * <p><b>誠実仕様（AC-58）</b>: 確認は一画面一確認。理由入力・電話番号・複数回確認・
 * 引き止め文言は一切置かない（ダークパターン禁止）。文言は「{endDate}まで利用できます。
 * {nextBillingDate}以降は請求されません。」の形で、期末までは利用継続でき即時失効しない
 * ことを明示する（{@code billing.manage.cancelReservationBody}）。</p>
 *
 * <p><b>撤回導線（AC-59）</b>: 月末前（{@link Props.canResume} = true）のときだけ、
 * 解約予約の取り消しボタンを表示する。期末を跨いだ後は非表示にする。</p>
 *
 * <p><b>操作中の抑止と再取得（AC-62）</b>: 確定ボタンは operation 進行中 disabled にする。
 * 失敗時はエラー要素を表示した「後」に再取得コールバックを呼ぶ（エラーを握りつぶさない）。
 * 実際の API 呼び出し・契約再取得は親から {@link Props.onConfirm} / {@link Props.onRefetch}
 * として注入される（本コンポーネントは表示専任で Stripe 同期呼出しを行わない＝AC-63）。</p>
 *
 * <p><b>a11y（AC-64）</b>: role="dialog" / aria-modal="true" / aria-labelledby、
 * Escape で取消、Tab による手動フォーカストラップ、開いた瞬間に確定ボタンへフォーカス。</p>
 */

/** 撤回導線に必要な解約予約情報（BE cancel API $.data の一部）。 */
interface CancelReservationInfo {
  /** 解約予約が発生した日時（ISO8601）。 */
  scheduledAt: string
  /** 契約終了日時（ISO8601）。 */
  endAt: string
}

interface Props {
  /** 対象契約ID。 */
  contractId: string
  /** 契約ステータス（ACTIVE/SCHEDULED/EXPIRED 等）。 */
  contractStatus: string
  /** 楽観ロック版数。 */
  version: number
  /** 現在の契約期間終了日時（ISO8601）。 */
  currentPeriodEnd: string
  /** 解約予約情報（未予約 or 撤回可能期限切れは null）。 */
  cancel?: CancelReservationInfo | null
  /** 解約実行が可能か（BE 由来の投影）。 */
  canCancel: boolean
  /** 解約予約の撤回が可能か（月末前だけ true。BE 由来の投影）。 */
  canResume: boolean
  /** ダイアログの開閉状態。 */
  open: boolean
  /** 解約確定。呼び出し元が実際の cancel API を叩く（Idempotency-Key 付与含む）。 */
  onConfirm?: () => Promise<void>
  /** 撤回確定。呼び出し元が実際の DELETE cancel API を叩く。 */
  onResume?: () => Promise<void>
  /** 確定・撤回の完了/失敗後に契約情報を再取得するコールバック。 */
  onRefetch?: () => void
}

const props = withDefaults(defineProps<Props>(), {
  cancel: null,
  onConfirm: undefined,
  onResume: undefined,
  onRefetch: undefined,
})

const emit = defineEmits<{
  cancel: []
  'update:open': [value: boolean]
}>()

const { t } = useI18n()
const { formatDate } = useDatetime()

const confirmLoading = ref(false)
const resumeLoading = ref(false)
const confirmError = ref(false)
const resumeError = ref(false)

const dialogEl = ref<HTMLElement | null>(null)
const titleId = 'billing-cancel-reservation-title'

/** 解約後に利用できなくなる基準日（BE の endAt を優先、無ければ現在期間の終了日）。 */
const endDateLabel = computed(() => formatDate(props.cancel?.endAt ?? props.currentPeriodEnd))

/** 次回請求が発生しなくなる日 = 契約終了日の翌日。 */
const nextBillingDateLabel = computed(() => {
  const base = props.cancel?.endAt ?? props.currentPeriodEnd
  if (!base) return ''
  const d = new Date(base)
  if (Number.isNaN(d.getTime())) return ''
  d.setDate(d.getDate() + 1)
  return formatDate(d.toISOString())
})

const confirmBody = computed(() =>
  t('billing.manage.cancelReservationBody', {
    endDate: endDateLabel.value,
    nextBillingDate: nextBillingDateLabel.value,
  }),
)

async function onConfirmClick() {
  if (confirmLoading.value) return
  confirmLoading.value = true
  confirmError.value = false
  try {
    await props.onConfirm?.()
  } catch {
    // 症状を隠さない: 明示エラー要素を出してから再取得する（対処療法禁止）。
    confirmError.value = true
  } finally {
    confirmLoading.value = false
    props.onRefetch?.()
  }
}

async function onResumeClick() {
  if (resumeLoading.value) return
  resumeLoading.value = true
  resumeError.value = false
  try {
    await props.onResume?.()
  } catch {
    resumeError.value = true
  } finally {
    resumeLoading.value = false
    props.onRefetch?.()
  }
}

function close() {
  emit('cancel')
  emit('update:open', false)
}

/** Tab キーによる手動フォーカストラップ（先頭⇄末尾で循環させ、ダイアログ外へ出さない）。 */
function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
    return
  }
  if (event.key !== 'Tab' || !dialogEl.value) return

  const focusable = dialogEl.value.querySelectorAll<HTMLElement>(
    'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])',
  )
  if (focusable.length === 0) return

  const first = focusable[0] as HTMLElement
  const last = focusable[focusable.length - 1] as HTMLElement
  const active = document.activeElement

  if (event.shiftKey) {
    if (active === first || !dialogEl.value.contains(active)) {
      event.preventDefault()
      last.focus()
    }
  } else {
    if (active === last || !dialogEl.value.contains(active)) {
      event.preventDefault()
      first.focus()
    }
  }
}

/** 開いた瞬間に確定ボタンへフォーカスする（キーボードのみで到達可能にする）。 */
watch(
  () => props.open,
  async (isOpen) => {
    if (!isOpen) return
    await nextTick()
    const confirmButton = dialogEl.value?.querySelector<HTMLElement>('[data-testid="cancel-confirm-button"]')
    confirmButton?.focus()
  },
  { immediate: true },
)
</script>

<template>
  <div
    v-if="open"
    ref="dialogEl"
    class="billing-cancel-reservation-dialog"
    role="dialog"
    aria-modal="true"
    :aria-labelledby="titleId"
    data-testid="billing-cancel-reservation-dialog"
    @keydown="onKeydown"
  >
    <div class="billing-cancel-reservation-dialog__panel">
      <h2 :id="titleId" class="billing-cancel-reservation-dialog__title">
        {{ t('billing.manage.cancelReservationTitle') }}
      </h2>

      <p class="billing-cancel-reservation-dialog__body">
        {{ confirmBody }}
      </p>

      <p
        v-if="confirmError"
        data-testid="cancel-error"
        role="alert"
        class="billing-cancel-reservation-dialog__error"
      >
        {{ t('billing.manage.cancelError') }}
      </p>

      <p
        v-if="resumeError"
        data-testid="resume-error"
        role="alert"
        class="billing-cancel-reservation-dialog__error"
      >
        {{ t('billing.manage.cancelError') }}
      </p>

      <div class="billing-cancel-reservation-dialog__actions">
        <Button
          :label="t('billing.manage.cancelConfirmCancel')"
          severity="secondary"
          text
          :disabled="confirmLoading || resumeLoading"
          data-testid="cancel-dismiss-button"
          @click="close"
        />

        <Button
          v-if="canResume"
          :label="t('billing.manage.resumeCancelCta')"
          severity="secondary"
          :disabled="confirmLoading || resumeLoading"
          :loading="resumeLoading"
          data-testid="resume-cancel-button"
          @click="onResumeClick"
        />

        <Button
          v-if="canCancel"
          :label="t('billing.manage.cancelConfirmCta')"
          severity="danger"
          :disabled="confirmLoading || resumeLoading"
          :loading="confirmLoading"
          data-testid="cancel-confirm-button"
          @click="onConfirmClick"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.billing-cancel-reservation-dialog {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 1rem;
}
.billing-cancel-reservation-dialog__panel {
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  padding: 1.25rem;
  max-width: 480px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.billing-cancel-reservation-dialog__title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
}
.billing-cancel-reservation-dialog__body {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--p-text-color, #111827);
}
.billing-cancel-reservation-dialog__error {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-red-600, #dc2626);
}
.billing-cancel-reservation-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  flex-wrap: wrap;
}
</style>
