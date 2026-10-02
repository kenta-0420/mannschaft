<script setup lang="ts">
/**
 * Billing Center PR6b-1 H群 — 上位プラン変更（UPGRADE）の確認ダイアログ。
 *
 * 正本: docs/features/F20.1_entitlement_billing/05_billing_center.md（Billing Center PR6b-1）。
 *
 * <p><b>表示の誠実さ（AC-126/127）</b>: 確定前に必ず `amountDueNow`（Stripe 見積り由来）と
 * `effectiveAt` を見せる。税込を主表示にし、`preview.taxSnapshot` から税抜・税額・税率を併記する
 * （こちらで計算しない。BE から受け取った値をそのまま表示するだけ）。</p>
 *
 * <p><b>肯定形の観測点（AC-128）</b>: 支払い待ち（`PENDING_PAYMENT`/`REQUIRES_ACTION`）の間は
 * プラン名表示が旧プラン（`currentPlanKey`）のままであることを、否定形（「新プランを表示しない」）
 * ではなく肯定形で担保する。</p>
 *
 * <p><b>失敗の明示（AC-129）</b>: `changeError` があるときは「現在のプランのままです」を明示する
 * （一般的なエラー表示にしない）。</p>
 *
 * <p><b>進行中の抑止（AC-130）</b>: 確定ボタンは `submitting` の間 disabled。失敗直後も
 * `refetching`（再取得中）の間は disabled を維持し、再取得完了後に解除する（PR6a AC-62 と同型）。
 * 実際の再取得タイミング制御は親コンポーネントの責務で、本ダイアログは props をそのまま反映する
 * だけの表示専任コンポーネントとする（AC-63 と同じ設計方針）。</p>
 *
 * <p><b>3DS 復帰後のフォーカス（AC-132）</b>: `justReturnedFrom3ds` が true でマウントされたとき、
 * `data-testid="plan-change-status"` へフォーカスを移す（3DS の戻り先が `/billing` の契約カード
 * 内であるため、スクリーンリーダー利用者にも状態変化が伝わるようにする）。</p>
 *
 * <p>Stripe 呼び出し（3DS の発火・ポーリング）は親から注入されるハンドラ（`onConfirm` /
 * `onResumePaymentAction`）に委譲し、本コンポーネント自身は行わない
 * （`useStripeSetup.confirmPaymentAction` / `usePlanChangePolling` は親側で使う）。</p>
 */
import type { BillingPendingChange } from '~/composables/useBillingApi'

/** 事前見積り（AC-1〜24）の投影。金額・税はすべて BE（Stripe 見積り）由来でこちらで計算しない。 */
interface PlanChangePreview {
  previewId: string
  kind: string
  amountDueNow: number
  taxSnapshot: {
    amountInclTax: number
    amountExclTax: number
    taxAmount: number
    taxRate: number
  }
  effectiveAt: string
  expiresAt: string
}

/** 変更先として選べるプランの最小投影（カタログ `BillingPlanItem` の一部）。 */
interface PlanChoice {
  planKey: string
  /** 表示名の i18n キー（無ければ planKey をそのまま出す）。 */
  displayNameKey?: string
}

/**
 * `BillingActiveContract.pendingChange`（AC-133）そのもの。生成型に移行済み
 * （`docs/openapi.json` 再生成後）で、全フィールドが optional。以前はここに手書きの必須型を
 * 置いていたが、生成型が真実のソースという方針どおり撤去した。値の欠落は本物の状態
 * （例: changeId が無ければ 3DS 再開ボタンを出さない）として扱うこと。
 */
type PendingChange = BillingPendingChange

interface Props {
  /** ダイアログの開閉状態。 */
  open: boolean
  /** 事前見積り。支払い待ち中の再表示等では null になりうる。 */
  preview: PlanChangePreview | null
  /** 現在契約中のプランキー。 */
  currentPlanKey: string
  /** 変更先のプランキー。 */
  targetPlanKey: string
  /**
   * 変更先として選べるプラン一覧（カタログ由来。空なら選択 UI を出さない）。
   * 親が遅延取得して渡す（本ダイアログは API を叩かない）。
   */
  plans?: PlanChoice[]
  /** 見積りの取得に失敗した等、確定前に利用者へ伝えるべき理由。無ければ null。 */
  previewError?: string | null
  /** 進行中の変更（AC-133 投影）。無ければ null。 */
  pendingChange: PendingChange | null
  /** 直近の変更失敗理由。無ければ null（AC-129）。 */
  changeError: string | null
  /** 確定処理が進行中か（AC-130）。 */
  submitting: boolean
  /** 失敗後の再取得が進行中か（AC-130。再取得完了まで確定ボタンを解除しない）。 */
  refetching?: boolean
  /** 3DS の戻り直後にマウントされたか（AC-132。true ならステータス要素へフォーカスする）。 */
  justReturnedFrom3ds?: boolean
  /** 確定操作（見積り内容での変更実行 or 3DS 確認の実体は親が注入する）。 */
  onConfirm?: () => Promise<void>
  /** 支払い待ちからの再開（`GET .../payment-action` を叩き直す）。 */
  onResumePaymentAction?: () => Promise<void>
}

const props = withDefaults(defineProps<Props>(), {
  plans: () => [],
  previewError: null,
  refetching: false,
  justReturnedFrom3ds: false,
  onConfirm: undefined,
  onResumePaymentAction: undefined,
})

const emit = defineEmits<{
  cancel: []
  'update:open': [value: boolean]
  /** 変更先プランが選ばれた（親が見積りを取り直す）。 */
  'update:targetPlanKey': [value: string]
}>()

const { t } = useI18n()
const { formatDateTime } = useDatetime()

const statusEl = ref<HTMLElement | null>(null)

/** AC-128: 支払い待ちの間は肯定形で「旧プランのまま」を示す判定。 */
const isPendingPayment = computed(() =>
  props.pendingChange?.status === 'PENDING_PAYMENT' || props.pendingChange?.status === 'REQUIRES_ACTION',
)

/**
 * 処理中の抑止（AC-130）: 確定の進行中、または失敗直後の再取得完了待ち。
 * **見積りの有無はここに入れない**（修繕2巡目 P2-2）。
 */
const busy = computed(() => props.submitting || props.refetching)

/**
 * 確定ボタンの disabled 判定（AC-130）。処理中に加え、見積りが無い状態
 * （変更先未選択・見積り取得失敗）では確定させない——`POST …/changes` は `previewId` 必須であり、
 * 見積り無しで押せる確定ボタンは必ず失敗する。
 *
 * <p><b>この判定を閉じる・再開のボタンへ流用してはならない</b>: ダイアログを開いた直後や
 * 既存の支払い待ちを表示した場合は `preview` が null なので、利用者が
 * 「プランを選んで見積りに成功するまで閉じられない」「3DS を再開できない」状態に陥る
 * （Codex 再検分 P2-2）。</p>
 */
const confirmDisabled = computed(() => busy.value || props.preview === null)

function onTargetPlanChange(event: Event) {
  const value = (event.target as HTMLSelectElement | null)?.value ?? ''
  emit('update:targetPlanKey', value)
}

function close() {
  emit('cancel')
  emit('update:open', false)
}

async function onConfirmClick() {
  if (confirmDisabled.value) return
  await props.onConfirm?.()
}

async function onResumeClick() {
  // 再開に見積りは要らない（AC-71/P2-2）。処理中だけ抑止する。
  if (busy.value) return
  await props.onResumePaymentAction?.()
}

/** AC-132: 3DS 復帰直後は契約カード内の状態要素へフォーカスを移す。 */
onMounted(() => {
  if (props.justReturnedFrom3ds) {
    statusEl.value?.focus()
  }
})
</script>

<template>
  <div
    v-if="open"
    class="billing-plan-change-dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby="billing-plan-change-title"
    data-testid="billing-plan-change-dialog"
  >
    <div class="billing-plan-change-dialog__panel">
      <h2 id="billing-plan-change-title" class="billing-plan-change-dialog__title">
        {{ t('billing.manage.planChange.title') }}
      </h2>

      <!-- AC-128（肯定形）: 支払い待ちの間はプラン名表示が旧プランのまま -->
      <p class="billing-plan-change-dialog__plan-row">
        <span data-testid="plan-change-current-plan-label">{{ currentPlanKey }}</span>
        <template v-if="!isPendingPayment">
          <i class="pi pi-arrow-right text-xs" aria-hidden="true" />
          <span data-testid="plan-change-target-plan-label">{{ targetPlanKey }}</span>
        </template>
      </p>

      <!-- AC-128 対（陽性対照）: pendingChange が無ければバナー自体を出さない -->
      <p
        v-if="isPendingPayment"
        data-testid="plan-change-pending-payment-banner"
        role="status"
        class="billing-plan-change-dialog__notice"
      >
        {{ t('billing.manage.planChange.pendingPaymentNotice') }}
        <!-- P2-1: 期限は pending_update の失効時刻のみ。effectiveAt は期限ではない。 -->
        <template v-if="pendingChange?.pendingUpdateExpiresAt">
          {{ t('billing.manage.planChange.expiresAtNotice', { date: formatDateTime(pendingChange.pendingUpdateExpiresAt) }) }}
        </template>
        <template v-else>
          {{ t('billing.manage.planChange.expiresAtUnknownNotice') }}
        </template>
      </p>

      <!-- 変更先プランの選択。支払い待ちの間は新しい変更を始めさせない（AC-117/118 と整合） -->
      <div v-if="!isPendingPayment && plans.length > 0" class="billing-plan-change-dialog__field">
        <label class="billing-plan-change-dialog__label" for="billing-plan-change-target">
          {{ t('billing.manage.planChange.targetPlanLabel') }}
        </label>
        <select
          id="billing-plan-change-target"
          class="billing-plan-change-dialog__select"
          data-testid="plan-change-target-select"
          :value="targetPlanKey"
          :disabled="submitting"
          @change="onTargetPlanChange"
        >
          <option value="">{{ t('billing.manage.planChange.targetPlanPlaceholder') }}</option>
          <option v-for="p in plans" :key="p.planKey" :value="p.planKey">
            {{ p.displayNameKey ? t(p.displayNameKey) : p.planKey }}
          </option>
        </select>
      </div>

      <!-- 見積りが取れない理由を隠さずに伝える（確定ボタンは disabled のままにする） -->
      <p
        v-if="previewError"
        data-testid="plan-change-preview-error"
        role="alert"
        class="billing-plan-change-dialog__error"
      >
        {{ previewError }}
      </p>

      <!-- AC-126/127: 確定前に amountDueNow / effectiveAt / 税の内訳を必ず見せる（preview 由来） -->
      <div v-if="preview" class="billing-plan-change-dialog__amounts">
        <p data-testid="plan-change-amount-due-now">
          {{ t('billing.manage.planChange.amountDueNow') }}: {{ preview.amountDueNow }}
        </p>
        <p data-testid="plan-change-effective-at">
          {{ t('billing.manage.planChange.effectiveAt') }}: {{ formatDateTime(preview.effectiveAt) }}
        </p>

        <!-- 税込を主表示（DOM順で税抜より先） -->
        <p data-testid="plan-change-amount-incl-tax" class="billing-plan-change-dialog__amount-primary">
          {{ t('billing.manage.planChange.amountInclTax') }}: {{ preview.taxSnapshot.amountInclTax }}
        </p>
        <p data-testid="plan-change-amount-excl-tax">
          {{ t('billing.manage.planChange.amountExclTax') }}: {{ preview.taxSnapshot.amountExclTax }}
        </p>
        <p data-testid="plan-change-tax-amount">
          {{ t('billing.manage.planChange.taxAmount') }}: {{ preview.taxSnapshot.taxAmount }}
        </p>
        <p data-testid="plan-change-tax-rate">
          {{ t('billing.manage.planChange.taxRate') }}: {{ preview.taxSnapshot.taxRate }}
        </p>
      </div>

      <!-- AC-129: 失敗時は「旧プランのままです」を明示する -->
      <p
        v-if="changeError"
        data-testid="plan-change-failed-notice"
        role="alert"
        class="billing-plan-change-dialog__error"
      >
        {{ t('billing.manage.planChange.staysOnOldPlan') }}
      </p>

      <!-- AC-132: 3DS 戻り後のフォーカス先（契約カード内の「プラン変更の状態」要素） -->
      <p
        ref="statusEl"
        data-testid="plan-change-status"
        tabindex="-1"
        role="status"
        class="billing-plan-change-dialog__status"
      >
        <template v-if="pendingChange">{{ pendingChange.status }}</template>
      </p>

      <div class="billing-plan-change-dialog__actions">
        <Button
          :label="t('billing.manage.planChange.cancelCta')"
          severity="secondary"
          text
          :disabled="busy"
          data-testid="plan-change-dismiss-button"
          @click="close"
        />

        <!-- changeId が無ければ再開ボタン自体を出さない（無言の no-op 禁止。生成型は optional のため
             再検分で一度指摘された「押しても何も起きない」欠陥を再発させない）。 -->
        <Button
          v-if="isPendingPayment && pendingChange?.paymentActionRequired && pendingChange?.changeId"
          :label="t('billing.manage.planChange.resumePaymentActionCta')"
          severity="secondary"
          :disabled="busy"
          data-testid="plan-change-resume-payment-action-button"
          @click="onResumeClick"
        />

        <Button
          :label="t('billing.manage.planChange.confirmCta')"
          severity="primary"
          :loading="submitting"
          :disabled="confirmDisabled"
          data-testid="plan-change-confirm-button"
          @click="onConfirmClick"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.billing-plan-change-dialog {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 40;
  padding: 1rem;
}
.billing-plan-change-dialog__panel {
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  padding: 1.25rem;
  max-width: 480px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.billing-plan-change-dialog__title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
}
.billing-plan-change-dialog__plan-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin: 0;
  font-size: 0.9375rem;
}
.billing-plan-change-dialog__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.billing-plan-change-dialog__label {
  font-size: 0.8125rem;
  font-weight: 600;
}
.billing-plan-change-dialog__select {
  border: 1px solid var(--p-content-border-color, #d4d4d8);
  border-radius: 0.375rem;
  padding: 0.375rem 0.5rem;
  font-size: 0.875rem;
  background: var(--p-content-background, #fff);
  color: inherit;
}
.billing-plan-change-dialog__amounts {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.875rem;
}
.billing-plan-change-dialog__amount-primary {
  font-weight: 700;
  font-size: 1rem;
}
.billing-plan-change-dialog__notice {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-orange-600, #d97706);
}
.billing-plan-change-dialog__error {
  margin: 0;
  font-size: 0.875rem;
  color: var(--p-red-600, #dc2626);
}
.billing-plan-change-dialog__status:empty {
  display: none;
}
.billing-plan-change-dialog__actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  flex-wrap: wrap;
}
</style>
