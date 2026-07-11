<script setup lang="ts">
/**
 * F20.1 U-3/U-4/U-5: 課金管理パネル（個人・チーム・組織で共通利用する本体コンポーネント）。
 *
 * - 閲覧（契約中プラン・アドオン・利用できる機能一覧）はメンバー以上に許可。
 * - 操作（解約）は ADMIN のみ（`canManage` prop で出し分け。BE 認可と一致させること）。
 * - 解約は「誠実仕様」（AC-42）: 確認モーダルは1回のみ・多段引き止め禁止・使えなくなる機能を明示。
 *   有償契約は `currentPeriodEnd` を用いて「◯月◯日まで利用できます」を明示、
 *   無償契約は「すぐに使えなくなります」を明示する。
 */
import type { BillingActiveContract, BillingEntitledFeature, BillingScopeKind } from '~/composables/useBillingApi'

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

// === 解約（誠実仕様: 確認モーダルは1回のみ） ===
const cancelTarget = ref<BillingActiveContract | null>(null)
const cancelVisible = ref(false)
const cancelSubmitting = ref(false)

/** 解約対象の契約に由来する機能キー一覧（PLAN は planKey 一致する entitledFeatures の PLAN 由来分。ADDON は featureKey 自身）。 */
const cancelAffectedFeatures = computed(() => {
  if (!cancelTarget.value) return [] as BillingEntitledFeature[]
  if (cancelTarget.value.featureKey) {
    // ADDON: featureKey が一致する 1 件
    return entitledFeatures.value.filter(f => f.featureKey === cancelTarget.value!.featureKey)
  }
  // PLAN: sourceKind=PLAN の全件（プラン契約は複数機能を束ねる）
  return entitledFeatures.value.filter(f => f.sourceKind === 'PLAN')
})

function openCancel(contract: BillingActiveContract) {
  cancelTarget.value = contract
  cancelVisible.value = true
}

async function submitCancel() {
  const target = cancelTarget.value
  if (!target?.contractId) return
  cancelSubmitting.value = true
  try {
    const res = await billingApi.cancelContract(props.scopeKind, props.scopeId, target.contractId)
    cancelVisible.value = false
    if (res.data.currentPeriodEnd) {
      // 有償解約: BE レスポンスの currentPeriodEnd を用いて「いつまで使えるか」を明示する（AC-42）。
      notification.success(t('billing.manage.cancelSuccessPaid', { date: formatDate(res.data.currentPeriodEnd) }))
    }
    else {
      notification.success(t('billing.manage.cancelSuccessFree'))
    }
    await load()
  }
  catch (err) {
    handleApiError(err, 'billing.manage.cancel')
  }
  finally {
    cancelSubmitting.value = false
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

    <!-- 解約確認モーダル（1回のみ・多段引き止め禁止） -->
    <Dialog
      v-model:visible="cancelVisible"
      modal
      :header="t('billing.manage.cancelConfirmTitle')"
      class="w-full max-w-md"
      data-testid="billing-cancel-confirm-modal"
    >
      <div v-if="cancelTarget" class="space-y-4">
        <p class="text-sm leading-relaxed text-surface-700 dark:text-surface-300">
          {{ cancelTarget.priceJpySnapshot != null
            ? t('billing.manage.cancelConfirmBodyPaid')
            : t('billing.manage.cancelConfirmBodyFree') }}
        </p>
        <div v-if="cancelAffectedFeatures.length > 0">
          <p class="mb-1 text-xs font-semibold text-red-600 dark:text-red-400">
            {{ t('billing.manage.cancelConfirmFeaturesLabel') }}
          </p>
          <ul class="space-y-1 text-sm">
            <li v-for="f in cancelAffectedFeatures" :key="f.featureKey" class="flex items-center gap-2">
              <i class="pi pi-minus-circle text-red-500" />
              {{ t(`billing.features.${(f.featureKey ?? '').replace(/\./g, '_')}.name`, f.featureKey ?? '') }}
            </li>
          </ul>
        </div>
      </div>
      <template #footer>
        <Button :label="t('billing.manage.cancelConfirmCancel')" text severity="secondary" @click="cancelVisible = false" />
        <Button
          :label="t('billing.manage.cancelConfirmCta')"
          severity="danger"
          :loading="cancelSubmitting"
          data-testid="billing-cancel-confirm-submit"
          @click="submitCancel"
        />
      </template>
    </Dialog>
  </div>
</template>
