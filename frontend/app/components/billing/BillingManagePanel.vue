<script setup lang="ts">
/**
 * F20.1 U-3/U-4/U-5: 課金管理パネル（個人・チーム・組織で共通利用する本体コンポーネント）。
 *
 * - 閲覧（契約中プラン・アドオン・利用できる機能一覧）はメンバー以上に許可。
 * - 操作（解約）は ADMIN のみ（`canManage` prop で出し分け。BE 認可と一致させること）。
 * - 解約・解約撤回は Billing Center PR6a の期末解約 saga API
 *   （`POST/DELETE /api/v1/me/billing/contracts/{contractId}/cancel`）を
 *   `BillingCancelReservationDialog` へ委譲する（一画面一確認・撤回導線はAC-58/59/62/63/64を
 *   同コンポーネント側で担保）。旧 `DELETE .../billing/contracts/{contractId}`（即時削除）は
 *   本パネルからの呼び出しを廃止した（Codex 検分 P1 是正。詳細は下記型定義コメント）。
 */
import type {
  BillingActiveContract,
  BillingContractChangeResponse,
  BillingEntitledFeature,
  BillingPendingChange,
  BillingScopeKind,
} from '~/composables/useBillingApi'
import BillingCancelReservationDialog from '~/components/billing/BillingCancelReservationDialog.vue'
import BillingPlanChangeDialog from '~/components/billing/BillingPlanChangeDialog.vue'

/**
 * BE の {@code BillingActiveContract} 投影は {@code version}（05_billing_center.md:344 の
 * {@code ContractBase.version:int64}）を返す（第7隊 8f0a0bb5a1 で解消済み。Codex 検分 P1 是正）。
 * とはいえ値が欠落するケースへの安全網として、無い場合は 0 決め打ちで送らず「操作不能」を
 * 誠実に表示する（CAS の意味を失わせる対処療法はしない）。
 *
 * 生成型 {@link BillingActiveContract} は {@code version}/{@code pendingChange} を含め
 * 全フィールドが optional なので、以前の手動拡張（`WithPendingChange`）は撤去し生成型を直接使う。
 */
type BillingActiveContractWithVersion = BillingActiveContract

const props = defineProps<{
  scopeKind: BillingScopeKind
  /** USER スコープは API 呼び出しに使わないため空文字で可。 */
  scopeId: string
  /** 解約等の操作ボタンを表示するか（ADMIN のみ true にすること）。 */
  canManage: boolean
}>()

const { t } = useI18n()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const billingApi = useBillingApi()
const { formatDate } = useDatetime()

const loading = ref(true)
const activePlan = ref<BillingActiveContract | null>(null)
const activeAddons = ref<BillingActiveContract[]>([])
const entitledFeatures = ref<BillingEntitledFeature[]>([])

const plansLinkTo = computed(() => {
  if (props.scopeKind === 'TEAM') return { path: '/billing/plans', query: { scope: 'team', slug: props.scopeId } }
  if (props.scopeKind === 'ORG') return { path: '/billing/plans', query: { scope: 'organization', slug: props.scopeId } }
  return { path: '/billing/plans' }
})

/**
 * 契約サマリを取り直す。**成功したか否かを返す**（修繕2巡目 P2-3）。
 *
 * 失敗を内部で catch して握りつぶすと、呼び出し側は「古い投影＝最新の真実」と誤認する。
 * エラー表示は従来どおり `handleApiError` が行い（症状を隠さない）、ここでは判別可能な
 * 戻り値を足すだけにする。
 */
async function load(): Promise<boolean> {
  loading.value = true
  try {
    const res = await billingApi.getEntitlements(props.scopeKind, props.scopeId)
    activePlan.value = res.data.activePlan ?? null
    activeAddons.value = res.data.activeAddons ?? []
    entitledFeatures.value = res.data.entitledFeatures ?? []
    return true
  }
  catch (err) {
    handleApiError(err, 'billing.manage.load')
    return false
  }
  finally {
    loading.value = false
  }
}

function sourceBadgeLabel(sourceKind: string | undefined): string {
  switch (sourceKind) {
    case 'PLAN': return t('billing.manage.sourceBadge.plan')
    case 'ADDON': return t('billing.manage.sourceBadge.addon')
    case 'BETA_GRANT': return t('billing.manage.sourceBadge.betaGrant')
    case 'NONPROFIT_FREE': return t('billing.manage.sourceBadge.nonprofitFree')
    default: return t('billing.manage.sourceBadge.free')
  }
}

// === 解約（Billing Center PR6a: 期末解約の予約・撤回。AC-58/59/62/63/64 は BillingCancelReservationDialog 側） ===
const reservationTarget = ref<BillingActiveContractWithVersion | null>(null)
const reservationVisible = ref(false)

/** 通常は BE から返るが、安全網として欠落時は undefined を保つ（コンポーネント冒頭のコメント参照）。 */
const reservationVersion = computed<number | undefined>(() => {
  const v = reservationTarget.value?.version
  return typeof v === 'number' ? v : undefined
})

/** BillingScheduledCancel は両フィールドが optional だが、ダイアログ側は両方揃った形を要求する。 */
const reservationCancelInfo = computed<{ scheduledAt: string; endAt: string } | null>(() => {
  const c = reservationTarget.value?.cancel
  if (c?.scheduledAt && c?.endAt) return { scheduledAt: c.scheduledAt, endAt: c.endAt }
  return null
})

function openCancel(contract: BillingActiveContract) {
  reservationTarget.value = contract
  reservationVisible.value = true
}

function closeCancelReservationDialog() {
  reservationVisible.value = false
}

/** 契約情報を再取得し、開いているダイアログの投影（canCancel/canResume 等）も最新化する（AC-59）。 */
async function refreshCancelReservationTarget() {
  await load()
  const id = reservationTarget.value?.contractId
  if (!id) return
  const updated: BillingActiveContractWithVersion | null =
    (activePlan.value?.contractId === id ? activePlan.value : null)
    ?? activeAddons.value.find(a => a.contractId === id)
    ?? null
  reservationTarget.value = updated
  if (!updated) reservationVisible.value = false
}

async function confirmCancelReservation() {
  const target = reservationTarget.value
  if (!target?.contractId) return
  if (reservationVersion.value === undefined) {
    // 症状を隠さない: version を決め打ちで送らず、明示的に失敗させて誠実にエラー表示する。
    throw new Error('billing contract version is unavailable from BillingActiveContract projection')
  }
  const res = await billingApi.cancelContractReservation(target.contractId, reservationVersion.value)
  if (res.data.status === 'SCHEDULED' && res.data.endAt) {
    notification.success(t('billing.manage.cancelSuccessPaid', { date: formatDate(res.data.endAt) }))
  }
  else {
    notification.success(t('billing.manage.cancelSuccessFree'))
  }
}

async function confirmResumeReservation() {
  const target = reservationTarget.value
  if (!target?.contractId) return
  if (reservationVersion.value === undefined) {
    throw new Error('billing contract version is unavailable from BillingActiveContract projection')
  }
  await billingApi.resumeContractCancellation(target.contractId, reservationVersion.value)
  notification.success(t('billing.manage.resumeSuccess'))
}

// === プラン変更（Billing Center PR6b-1: 上位プラン変更・3DS。AC-125〜135） ===
//
// **PR6a と同型の欠陥の再発防止（最重要）**: 当初の実装はダイアログへ `:preview="null"`
// `:target-plan-key="''"` `:submitting="false"` を固定値で渡し、確定ハンドラを一つも繋いでいなかった。
// 「ダイアログが開く」テストは緑のまま、押しても何も起きない no-op だった（Codex 検分 P1）。
// 現在は次の実 API へ結線してある（番人: `BillingManagePanel.planChangeWiring.spec.ts`）:
//   1. カタログ取得   GET  /api/v1/billing/plans
//   2. 事前見積り     POST /api/v1/me/billing/contracts/{id}/change-previews  （AC-1〜9）
//   3. upgrade の実行 POST /api/v1/me/billing/contracts/{id}/changes           （AC-29〜31）
//   4. 3DS の追加認証 GET  .../changes/{changeId}/payment-action               （AC-48〜54）
//      → `useStripeSetup.confirmPaymentAction`（`handleNextAction`。AC-73）
//   5. 確定待ちの追跡 `usePlanChangePolling`（間隔・回数上限あり。AC-135）
// BE のエンドポイントはスコープに関わらず `/me/billing/contracts/{contractId}` 配下へ集約されている
// ため、USER/TEAM/ORG の3スコープとも同じ経路で動く（認可は BE がスコープを解決して判定する）。
const { confirmPaymentAction } = useStripeSetup()
const planChangePolling = usePlanChangePolling()

/** ダイアログへ渡す見積りの投影（BE の Money を税込主表示の形へ写すだけ。金額計算はしない）。 */
interface PlanChangePreviewView {
  previewId: string
  kind: string
  amountDueNow: number
  taxSnapshot: { amountInclTax: number; amountExclTax: number; taxAmount: number; taxRate: number }
  effectiveAt: string
  expiresAt: string
}

const planChangeTarget = ref<BillingActiveContractWithVersion | null>(null)
const planChangeDialogOpen = ref(false)
const planChangePlans = ref<{ planKey: string; displayNameKey?: string }[]>([])
const planChangeTargetPlanKey = ref('')
const planChangePreview = ref<PlanChangePreviewView | null>(null)
const planChangePreviewError = ref<string | null>(null)
const planChangeError = ref<string | null>(null)
const planChangeSubmitting = ref(false)
const planChangeRefetching = ref(false)
const planChangeId = ref<string | null>(null)
/** 実行直後の `status`（次の再取得までは BE 投影の pendingChange より新しい）。 */
const planChangeLatest = ref<BillingPendingChange | null>(null)

/** ダイアログへ渡す進行中変更。実行直後は自分の応答を優先し、以降は BE 投影に従う。 */
const planChangePending = computed<BillingPendingChange | null>(() =>
  planChangeLatest.value ?? planChangeTarget.value?.pendingChange ?? null,
)

/** 契約カードに出す「支払い待ち」表示の判定（AC-104: 押す前から見える）。 */
const activePlanPendingChange = computed<BillingPendingChange | null>(() => {
  const pc = activePlan.value?.pendingChange ?? null
  if (!pc) return null
  return pc.status === 'PENDING_PAYMENT' || pc.status === 'REQUIRES_ACTION' ? pc : null
})

/** 契約の CAS 期待値。欠落時は決め打ちせず undefined を保つ（解約と同じ流儀）。 */
const planChangeVersion = computed<number | undefined>(() => {
  const v = planChangeTarget.value?.version
  return typeof v === 'number' ? v : undefined
})

function openPlanChange(contract: BillingActiveContract) {
  planChangeTarget.value = contract
  planChangeTargetPlanKey.value = ''
  planChangePreview.value = null
  planChangePreviewError.value = null
  planChangeError.value = null
  planChangeId.value = null
  planChangeLatest.value = null
  planChangeDialogOpen.value = true
  void loadPlanChangeCandidates()
}

function closePlanChangeDialog() {
  planChangeDialogOpen.value = false
}

/**
 * 変更先候補をカタログから取る。ダイアログを開いた時だけ叩く。
 *
 * **実際にアップグレードになるもの（価格上位）だけを候補にする**（修繕2巡目 P2-4）。
 * BE の見積りは変更先金額が現在以下なら必ず 409（`CHANGE_CONFLICT`）で拒否する
 * （`BillingPlanChangePreviewService`: `toBand <= fromBand` で conflict。AC-22/23/24）ため、
 * 下位・同額のプランは「選べるが必ず失敗する」項目にしかならない。価格が分からないプラン
 * （`baseMonthlyPriceJpy` が null の無償プラン等）は上位であることを主張できないので出さない。
 * なお**下位プランへの変更は PR6b-2 の題目**であり、本 PR では扱わない。
 */
async function loadPlanChangeCandidates() {
  try {
    const res = await billingApi.getPlanCatalog()
    const plans = res.data.plans ?? []
    const current = planChangeTarget.value?.planKey
    const priceOf = (planKey: string | undefined): number =>
      plans.find(p => p.planKey === planKey)?.baseMonthlyPriceJpy ?? 0
    const currentPrice = priceOf(current)
    planChangePlans.value = plans
      .filter(p => typeof p.planKey === 'string' && p.planKey !== current)
      .filter(p => (p.baseMonthlyPriceJpy ?? 0) > currentPrice)
      .map(p => ({ planKey: p.planKey as string, displayNameKey: p.displayNameKey }))
  }
  catch (err) {
    planChangePreviewError.value = t('billing.manage.planChange.catalogLoadFailed')
    handleApiError(err, 'billing.manage.planChange.catalog')
  }
}

/** 変更先が選ばれたら事前見積りを取り直す（AC-126: 確定前に必ず金額を見せるため）。 */
async function onPlanChangeTargetSelected(planKey: string) {
  planChangeTargetPlanKey.value = planKey
  planChangePreview.value = null
  planChangePreviewError.value = null
  planChangeError.value = null
  if (!planKey) return

  const contractId = planChangeTarget.value?.contractId
  if (!contractId) return
  if (planChangeVersion.value === undefined) {
    planChangePreviewError.value = t('billing.manage.planChange.versionUnavailable')
    return
  }

  try {
    const res = await billingApi.createPlanChangePreview(contractId, {
      toProductKind: 'PLAN',
      toProductKey: planKey,
      version: planChangeVersion.value,
    })
    const { previewId, kind, amountDueNow: money, effectiveAt, expiresAt } = res.data
    // 生成型では全フィールドが optional。確定前に金額・期限を必ず見せる仕様（AC-126/127）上、
    // 欠けたものを 0 や空文字で埋めて「見積れた」と偽装せず、見積り失敗として扱う。
    if (
      !previewId || !kind || !effectiveAt || !expiresAt
      || money?.amountIncludingTax == null || money?.amountExcludingTax == null || money?.taxAmount == null
    ) {
      planChangePreviewError.value = t('billing.manage.planChange.previewFailed')
      handleApiError(
        new Error('billing plan change preview response missing required fields'),
        'billing.manage.planChange.preview',
      )
      return
    }
    planChangePreview.value = {
      previewId,
      kind,
      // 税込を主表示にする（AC-127）。税率は BE のベーシスポイントを率へ直すだけで金額計算はしない。
      amountDueNow: money.amountIncludingTax,
      taxSnapshot: {
        amountInclTax: money.amountIncludingTax,
        amountExclTax: money.amountExcludingTax,
        taxAmount: money.taxAmount,
        // 税率不明（null）の契約は 0% と決め打ちせず、率不明として 0 表示に倒す（AC-127 の範囲内の
        // 妥協点。金額自体は BE 値そのまま、計算するのは表示用の率だけ）。
        taxRate: (money.taxRateBasisPoints ?? 0) / 10000,
      },
      effectiveAt,
      expiresAt,
    }
  }
  catch (err) {
    planChangePreviewError.value = t('billing.manage.planChange.previewFailed')
    handleApiError(err, 'billing.manage.planChange.preview')
  }
}

/** 確定（AC-25〜31）。ここが no-op だった欠陥の本体。 */
async function confirmPlanChange() {
  const contractId = planChangeTarget.value?.contractId
  const preview = planChangePreview.value
  if (!contractId || !preview) return
  if (planChangeVersion.value === undefined) {
    planChangePreviewError.value = t('billing.manage.planChange.versionUnavailable')
    return
  }

  planChangeSubmitting.value = true
  planChangeError.value = null
  try {
    const res = await billingApi.executePlanChange(contractId, {
      previewId: preview.previewId,
      version: planChangeVersion.value,
    })
    const { changeId, status, effectiveAt } = res.data
    // 生成型では changeId/status/effectiveAt がすべて optional。欠けたまま「受理できた」と
    // 偽装せず、明示的に失敗として扱う（対処療法禁止・根治治療の原則）。
    if (!changeId || !status || !effectiveAt) {
      planChangeError.value = 'PLAN_CHANGE_FAILED'
      handleApiError(
        new Error('billing plan change response missing changeId/status/effectiveAt'),
        'billing.manage.planChange.execute',
      )
      await refreshPlanChangeTarget()
      return
    }
    planChangeId.value = changeId
    planChangeLatest.value = {
      changeId,
      status,
      effectiveAt,
      paymentActionRequired: status === 'REQUIRES_ACTION',
      // 実行応答は期限を返さない（`POST …/changes` は changeId/status/effectiveAt のみ・AC-25）。
      // 分からないものを埋めず未設定のままにし、期限の誤表示を作らない
      // （生成型は `string | undefined` のため null ではなく undefined を保つ）。
      pendingUpdateExpiresAt: undefined,
    }
    await afterPlanChangeAccepted(status)
  }
  catch (err) {
    // 症状を隠さない: 失敗は明示し（AC-129）、再取得の完了まで確定ボタンを解除しない（AC-130）。
    planChangeError.value = 'PLAN_CHANGE_FAILED'
    handleApiError(err, 'billing.manage.planChange.execute')
    await refreshPlanChangeTarget()
  }
  finally {
    planChangeSubmitting.value = false
  }
}

/** 実行応答の status に応じて、3DS・確定待ちの追跡・失敗表示へ振り分ける。 */
async function afterPlanChangeAccepted(status: NonNullable<BillingContractChangeResponse['status']>) {
  if (status === 'REQUIRES_ACTION') {
    await runPaymentAction()
    return
  }
  if (status === 'FAILED' || status === 'CANCELLED') {
    planChangeError.value = 'PLAN_CHANGE_FAILED'
    await refreshPlanChangeTarget()
    return
  }
  if (status === 'APPLIED') {
    notification.success(t('billing.manage.planChange.appliedSuccess'))
    await refreshPlanChangeTarget()
    return
  }
  // PENDING_PAYMENT: 権利発行は webhook（invoice.paid）が確定させるので、状態が動くまで追う。
  await pollPlanChangeStatus()
}

/**
 * 3DS の追加認証（AC-48〜54/AC-73）。
 *
 * clientSecret はこのローカル変数の中だけで生き、`handleNextAction` へ渡した後は捨てる。
 * ログ・URL・browser storage・DOM へ一切書かない（AC-55〜59）。
 */
async function runPaymentAction() {
  const contractId = planChangeTarget.value?.contractId
  // AC-71: ページ再読込・別端末では `planChangeId`（このセッションで実行した時だけ入る ref）が
  // null なので、BE 投影の `pendingChange.changeId` から再開する。ここを ref だけに頼ると
  // 「支払いを再開する」が無言で何もしない no-op になる（Codex 再検分 P1）。
  const changeId = planChangeId.value ?? planChangePending.value?.changeId ?? null
  if (!contractId || !changeId) return

  try {
    const res = await billingApi.getPlanChangePaymentAction(contractId, changeId)
    const clientSecret = res.data.paymentAction?.clientSecret
    if (!clientSecret) {
      // 生成型では paymentAction/clientSecret とも optional。欠けたまま Stripe SDK へ渡さず、
      // 明示的に失敗として扱う（symptom を隠さない）。
      planChangeError.value = 'PLAN_CHANGE_FAILED'
      handleApiError(
        new Error('billing plan change payment-action response missing clientSecret'),
        'billing.manage.planChange.paymentAction',
      )
      await refreshPlanChangeTarget()
      return
    }
    const result = await confirmPaymentAction({
      clientSecret,
      // リダイレクト型 3DS の戻り先（BE の `GET /billing/payment-action/return`・E3'）。
      returnUrl: `${window.location.origin}/billing/payment-action/return`,
    })
    if (result.status === 'error') {
      planChangeError.value = 'PLAN_CHANGE_FAILED'
      notification.error(result.message)
      await refreshPlanChangeTarget()
      return
    }
    // リダイレクトを伴わない 3DS（AC-72）はここへ戻ってくる。確定は webhook が行うため状態を追う。
    await pollPlanChangeStatus()
  }
  catch (err) {
    planChangeError.value = 'PLAN_CHANGE_FAILED'
    handleApiError(err, 'billing.manage.planChange.paymentAction')
    await refreshPlanChangeTarget()
  }
}

/**
 * 確定（webhook 由来）を待つ（AC-135）。
 *
 * 間隔・回数上限は `usePlanChangePolling` が持つ。契約サマリの再取得だけを行い、
 * Stripe を叩く `payment-action` はここでは呼ばない（AC-147）。
 */
async function pollPlanChangeStatus() {
  await planChangePolling.start(async () => {
    // 修繕2巡目 P2-3: 再取得が失敗した周は「状態が分かった」と見なさない。暫定状態
    // （planChangeLatest）を保ったまま次の周で取り直す。ここで done を返すと、古い投影に
    // pendingChange が無いせいで完了扱いになり、以降の監視が止まる。
    const refreshed = await refreshPlanChangeTarget()
    if (!refreshed) return { done: false }
    const status = planChangeTarget.value?.pendingChange?.status
    if (!status || status === 'APPLIED' || status === 'FAILED' || status === 'CANCELLED') {
      if (status === 'FAILED' || status === 'CANCELLED') planChangeError.value = 'PLAN_CHANGE_FAILED'
      return { done: true }
    }
    return { done: false }
  })
}

/**
 * 契約情報を取り直し、ダイアログの投影（pendingChange・version）も最新化する（AC-130）。
 * **再取得に成功したかを返す**（修繕2巡目 P2-3。失敗を成功と取り違えないため）。
 */
async function refreshPlanChangeTarget(): Promise<boolean> {
  planChangeRefetching.value = true
  try {
    const ok = await load()
    // 失敗した再取得の結果（＝古いままの投影）で暫定状態を上書きしない。
    if (!ok) return false
    const id = planChangeTarget.value?.contractId
    if (!id) return true
    const updated: BillingActiveContractWithVersion | null =
      (activePlan.value?.contractId === id ? activePlan.value : null)
      ?? activeAddons.value.find(a => a.contractId === id)
      ?? null
    planChangeTarget.value = updated
    // 再取得できた時点で BE 投影が真であり、自前の暫定 status は捨てる。
    planChangeLatest.value = null
    if (!updated) planChangeDialogOpen.value = false
    return true
  }
  finally {
    planChangeRefetching.value = false
  }
}

onMounted(load)

defineExpose({ load })
</script>

<template>
  <div class="space-y-6">
    <PageLoading v-if="loading" />

    <template v-else>
      <!-- 契約中のプラン -->
      <SectionCard :title="t('billing.manage.activeContract')">
        <div v-if="activePlan" class="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div class="flex items-center gap-2">
              <span class="text-lg font-semibold">{{ activePlan.planKey }}</span>
              <Tag :value="sourceBadgeLabel('PLAN')" severity="info" />
              <Tag v-if="activePlan.priceJpySnapshot == null" :value="t('billing.manage.sourceBadge.betaGrant')" severity="success" />
            </div>
            <p class="mt-1 text-xs text-surface-500">
              {{ t('billing.manage.contractedAt', { date: formatDate(activePlan.contractedAt) }) }}
            </p>
            <!-- AC-104/105: 支払い待ちであることは操作を試みる前から見える -->
            <p
              v-if="activePlanPendingChange"
              data-testid="billing-plan-change-pending-notice"
              role="status"
              class="mt-1 text-xs text-orange-600"
            >
              {{ t('billing.manage.planChange.pendingPaymentNotice') }}
              <!-- 期限は pending_update の失効時刻だけを根拠にする。effectiveAt（＝変更行を作った
                   時刻）を期限として出すと「現在時刻までに払え」と読ませる誤表示になる（P2-1）。 -->
              <template v-if="activePlanPendingChange.pendingUpdateExpiresAt">
                {{ t('billing.manage.planChange.expiresAtNotice', { date: formatDate(activePlanPendingChange.pendingUpdateExpiresAt) }) }}
              </template>
              <template v-else>
                {{ t('billing.manage.planChange.expiresAtUnknownNotice') }}
              </template>
            </p>
          </div>
          <div v-if="canManage" class="flex items-center gap-2">
            <Button
              :label="t('billing.manage.planChange.title')"
              severity="secondary"
              outlined
              size="small"
              data-testid="billing-change-plan"
              @click="openPlanChange(activePlan)"
            />
            <Button
              :label="t('billing.manage.cancelCta')"
              severity="danger"
              outlined
              size="small"
              data-testid="billing-cancel-plan"
              @click="openCancel(activePlan)"
            />
          </div>
        </div>
        <p v-else class="text-sm text-surface-500">
          {{ t('billing.manage.noContract') }}
        </p>
        <p v-if="!canManage" class="mt-3 text-xs text-surface-400">
          {{ t('billing.manage.adminOnlyNotice') }}
        </p>
      </SectionCard>

      <!-- アドオン -->
      <SectionCard :title="t('billing.manage.addons')">
        <p v-if="activeAddons.length === 0" class="text-sm text-surface-500">
          {{ t('billing.manage.noAddons') }}
        </p>
        <ul v-else class="divide-y divide-surface-200 dark:divide-surface-700">
          <li v-for="addon in activeAddons" :key="addon.contractId" class="flex items-center justify-between gap-3 py-2.5">
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium">{{ addon.featureKey }}</span>
              <Tag :value="sourceBadgeLabel('ADDON')" severity="secondary" />
            </div>
            <Button
              v-if="canManage"
              :label="t('billing.manage.cancelCta')"
              severity="danger"
              outlined
              size="small"
              @click="openCancel(addon)"
            />
          </li>
        </ul>
      </SectionCard>

      <!-- 利用できる機能 -->
      <SectionCard :title="t('billing.manage.entitledFeatures')">
        <ul v-if="entitledFeatures.length > 0" class="space-y-1.5">
          <li v-for="f in entitledFeatures" :key="f.featureKey" class="flex flex-wrap items-center justify-between gap-2 text-sm">
            <span class="flex items-center gap-2">
              <i class="pi pi-check text-primary" />
              {{ t(`billing.features.${(f.featureKey ?? '').replace(/\./g, '_')}.name`, f.featureKey ?? '') }}
            </span>
            <span class="flex items-center gap-2">
              <Tag :value="sourceBadgeLabel(f.sourceKind)" severity="secondary" class="text-xs" />
              <span class="text-xs text-surface-400">
                {{ f.validUntil ? t('billing.manage.validUntil', { date: formatDate(f.validUntil) }) : t('billing.manage.noExpiry') }}
              </span>
            </span>
          </li>
        </ul>
        <p v-else class="text-sm text-surface-500">
          {{ t('billing.manage.noContract') }}
        </p>
      </SectionCard>

      <div class="text-right">
        <NuxtLink :to="plansLinkTo" class="text-sm text-primary hover:underline">
          {{ t('billing.manage.viewPlansCta') }} <i class="pi pi-arrow-right text-xs" />
        </NuxtLink>
      </div>
    </template>

    <!-- 解約確認・撤回ダイアログ（Billing Center PR6a・一画面一確認。AC-58/59/62/63/64） -->
    <BillingCancelReservationDialog
      v-if="reservationTarget"
      :open="reservationVisible"
      :contract-id="reservationTarget.contractId ?? ''"
      :contract-status="reservationTarget.status ?? 'ACTIVE'"
      :version="reservationVersion ?? 0"
      :current-period-end="reservationTarget.currentPeriodEnd ?? ''"
      :cancel="reservationCancelInfo"
      :can-cancel="reservationTarget.canCancel ?? false"
      :can-resume="reservationTarget.canResume ?? false"
      :on-confirm="confirmCancelReservation"
      :on-resume="confirmResumeReservation"
      :on-refetch="refreshCancelReservationTarget"
      @cancel="closeCancelReservationDialog"
      @update:open="closeCancelReservationDialog"
    />

    <!-- プラン変更確認ダイアログ（Billing Center PR6b-1・AC-125〜132） -->
    <BillingPlanChangeDialog
      v-if="planChangeTarget"
      :open="planChangeDialogOpen"
      :preview="planChangePreview"
      :current-plan-key="planChangeTarget.planKey ?? ''"
      :target-plan-key="planChangeTargetPlanKey"
      :plans="planChangePlans"
      :pending-change="planChangePending"
      :preview-error="planChangePreviewError"
      :change-error="planChangeError"
      :submitting="planChangeSubmitting"
      :refetching="planChangeRefetching"
      :on-confirm="confirmPlanChange"
      :on-resume-payment-action="runPaymentAction"
      @update:target-plan-key="onPlanChangeTargetSelected"
      @cancel="closePlanChangeDialog"
      @update:open="closePlanChangeDialog"
    />
  </div>
</template>
