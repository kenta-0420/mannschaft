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
import type { BillingActiveContract, BillingEntitledFeature, BillingScopeKind } from '~/composables/useBillingApi'
import BillingCancelReservationDialog from '~/components/billing/BillingCancelReservationDialog.vue'

/**
 * BE の {@code BillingActiveContract} 投影は {@code version}（05_billing_center.md:344 の
 * {@code ContractBase.version:int64}）を返す（第7隊 8f0a0bb5a1 で解消済み。Codex 検分 P1 是正）。
 * とはいえ値が欠落するケースへの安全網として、無い場合は 0 決め打ちで送らず「操作不能」を
 * 誠実に表示する（CAS の意味を失わせる対処療法はしない）。
 */
type BillingActiveContractWithVersion = BillingActiveContract & { version?: number }

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

async function load() {
  loading.value = true
  try {
    const res = await billingApi.getEntitlements(props.scopeKind, props.scopeId)
    activePlan.value = res.data.activePlan ?? null
    activeAddons.value = res.data.activeAddons ?? []
    entitledFeatures.value = res.data.entitledFeatures ?? []
  }
  catch (err) {
    handleApiError(err, 'billing.manage.load')
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
          </div>
          <Button
            v-if="canManage"
            :label="t('billing.manage.cancelCta')"
            severity="danger"
            outlined
            size="small"
            data-testid="billing-cancel-plan"
            @click="openCancel(activePlan)"
          />
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
  </div>
</template>
