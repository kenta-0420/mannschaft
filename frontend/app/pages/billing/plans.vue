<script setup lang="ts">
/**
 * F20.1 U-1: プラン一覧（カタログ）。
 *
 * - `GET /api/v1/billing/plans` から 3 カラムのプランカードを描画する。
 * - 価格未定（`baseMonthlyPriceJpy == null`）は「価格はベータ終了後に決定」バッジ＋
 *   ベータ中は無料の説明を表示する（誇大語・「永久」の語は使用しない・誠実表示原則）。
 * - BASIC は R-3 未確定のため常に「準備中」表示（CTA 無効・機能リスト非表示・誤契約防止）。
 * - `?scope=team|organization&slug=...` でチーム/組織文脈を表す（USER 既定）。
 *   チーム/組織文脈では人数バンド表を折りたたみで段階開示する。
 * - CTA →確認モーダル（差分機能明示）→ 契約 API。`checkoutUrl` が非 null なら Stripe Checkout へ
 *   遷移し、null なら即時無償契約として反映する。
 * - `?checkout=success|cancelled` の復帰ハンドリング（webhook 反映待ちの再取得リトライを含む）。
 */
import type { BillingPlanItem, BillingScopeKind } from '~/composables/useBillingApi'

definePageMeta({ middleware: 'auth' })

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const notification = useNotification()
const { handleApiError } = useErrorHandler()
const billingApi = useBillingApi()

// === スコープ文脈の解決（?scope=team|organization&slug=...。既定は USER） ===
const queryScope = computed(() => String(route.query.scope ?? ''))
const queryScopeSlug = computed(() => String(route.query.slug ?? ''))

const scopeKind = computed<BillingScopeKind>(() => {
  if (queryScope.value === 'team') return 'TEAM'
  if (queryScope.value === 'organization') return 'ORG'
  return 'USER'
})
// USER スコープは /me 系エンドポイントを使うため scopeId は API 呼び出しに使わない（空文字で足りる）。
const scopeId = computed(() => (scopeKind.value === 'USER' ? '' : queryScopeSlug.value))

// === 状態 ===
const loading = ref(true)
const plans = ref<BillingPlanItem[]>([])
const currentPlanKey = ref<string | null>(null)
const entitledFeatureKeys = ref<Set<string>>(new Set())
const bandExpanded = ref(false)
const showHelp = ref(false)

// === 確認モーダル ===
const confirmVisible = ref(false)
const confirmTarget = ref<BillingPlanItem | null>(null)
const confirmSubmitting = ref(false)
const checkoutRedirecting = ref(false)

const isComingSoon = (plan: BillingPlanItem) => plan.planKey === 'BASIC'

async function loadPlans() {
  loading.value = true
  try {
    const res = await billingApi.getPlanCatalog()
    plans.value = res.data.plans ?? []
  }
  catch (err) {
    handleApiError(err)
  }
  finally {
    loading.value = false
  }
}

async function loadEntitlements() {
  try {
    const res = await billingApi.getEntitlements(scopeKind.value, scopeId.value)
    currentPlanKey.value = res.data.activePlan?.planKey ?? null
    entitledFeatureKeys.value = new Set((res.data.entitledFeatures ?? []).map(f => f.featureKey).filter((k): k is string => !!k))
  }
  catch {
    // 権利情報の取得失敗は非致命的（プラン一覧自体は表示できる）。ハイライトなしにフォールバックする。
    currentPlanKey.value = null
    entitledFeatureKeys.value = new Set()
  }
}

/** 現在のプランに対して、選択プランで新たに得られる機能一覧（displayNameKey）。 */
function computeGained(plan: BillingPlanItem): string[] {
  return (plan.features ?? [])
    .filter(f => f.featureKey && !entitledFeatureKeys.value.has(f.featureKey))
    .map(f => f.displayNameKey)
    .filter((k): k is string => !!k)
}

/** 現在契約中のプランの機能のうち、選択プランには含まれず失うもの。 */
function computeLost(plan: BillingPlanItem): string[] {
  const currentPlan = plans.value.find(p => p.planKey === currentPlanKey.value)
  if (!currentPlan) return []
  const targetFeatureKeys = new Set((plan.features ?? []).map(f => f.featureKey))
  return (currentPlan.features ?? [])
    .filter(f => f.featureKey && !targetFeatureKeys.has(f.featureKey))
    .map(f => f.displayNameKey)
    .filter((k): k is string => !!k)
}

function openConfirm(plan: BillingPlanItem) {
  if (isComingSoon(plan)) return
  if (plan.planKey === currentPlanKey.value) return
  confirmTarget.value = plan
  confirmVisible.value = true
}

async function submitPlanSelection() {
  const plan = confirmTarget.value
  if (!plan?.planKey) return
  confirmSubmitting.value = true
  try {
    const res = await billingApi.createContract(scopeKind.value, scopeId.value, {
      contractKind: 'PLAN',
      planKey: plan.planKey,
    })
    confirmVisible.value = false
    if (res.data.checkoutUrl) {
      checkoutRedirecting.value = true
      window.location.href = res.data.checkoutUrl
      return
    }
    notification.success(
      t('billing.plans.contractSuccessTitle'),
      t('billing.plans.contractSuccessBody', { plan: t(plan.displayNameKey ?? '') }),
    )
    await loadEntitlements()
  }
  catch (err) {
    const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'ENTITLEMENT_006') {
      notification.warn(t('dialog.error'), t('billing.plans.alreadyActiveError'))
    }
    else {
      handleApiError(err, 'billing.plans.createContract')
    }
  }
  finally {
    confirmSubmitting.value = false
  }
}

/** checkout 復帰後、webhook 反映まで数秒かかる場合があるため軽くリトライする。 */
async function reflectAfterCheckoutSuccess() {
  const before = currentPlanKey.value
  for (let i = 0; i < 3; i++) {
    await loadEntitlements()
    if (currentPlanKey.value !== before) break
    await new Promise(resolve => setTimeout(resolve, 1500))
  }
  notification.success(t('billing.plans.contractSuccessTitle'), '')
}

onMounted(async () => {
  await Promise.all([loadPlans(), loadEntitlements()])

  const checkout = route.query.checkout
  if (checkout === 'success') {
    await reflectAfterCheckoutSuccess()
    await router.replace({ query: { ...route.query, checkout: undefined } })
  }
  else if (checkout === 'cancelled') {
    notification.info(t('billing.plans.checkoutCancelledTitle'), t('billing.plans.checkoutCancelledBody'))
    await router.replace({ query: { ...route.query, checkout: undefined } })
  }
})
</script>

<template>
  <div class="mx-auto max-w-6xl">
    <PageHeader :title="t('billing.plans.title')" help @help="showHelp = true" />
    <BillingHelpDialog v-model:visible="showHelp" variant="plans" />

    <p class="mb-2 text-sm text-surface-500">
      {{ t('billing.plans.subtitle') }}
    </p>
    <p class="mb-6">
      <NuxtLink
        to="/commerce-disclosure"
        target="_blank"
        class="text-xs text-surface-400 hover:text-primary hover:underline"
      >
        {{ t('landing.layout.footer_commerce') }}
      </NuxtLink>
    </p>

    <PageLoading v-if="loading" />

    <div v-else class="fade-in space-y-6">
      <div v-if="checkoutRedirecting" class="rounded-lg border border-primary/30 bg-primary/5 p-4 text-sm text-primary">
        <i class="pi pi-spin pi-spinner mr-2" />{{ t('billing.plans.checkoutRedirecting') }}
      </div>

      <div class="grid gap-6 md:grid-cols-3">
        <SectionCard v-for="plan in plans" :key="plan.planKey">
          <template #header>
            <div class="flex items-center justify-between">
              <h2 class="text-lg font-semibold">
                {{ plan.displayNameKey ? t(plan.displayNameKey) : plan.planKey }}
              </h2>
              <Tag v-if="plan.planKey === currentPlanKey" :value="t('billing.plans.currentPlan')" severity="success" />
            </div>
          </template>

          <template v-if="isComingSoon(plan)">
            <div class="flex flex-col items-center justify-center gap-3 py-8 text-center">
              <i class="pi pi-clock text-3xl text-surface-400" aria-hidden="true" />
              <p class="font-medium text-surface-600 dark:text-surface-300">
                {{ t('billing.plans.comingSoon') }}
              </p>
              <p class="text-sm text-surface-500">
                {{ t('billing.plans.comingSoonDesc') }}
              </p>
              <Button :label="t('billing.plans.comingSoon')" disabled class="mt-2" />
            </div>
          </template>

          <template v-else>
            <p class="mb-3 text-sm text-surface-500">
              {{ plan.descriptionKey ? t(plan.descriptionKey) : '' }}
            </p>

            <div class="mb-4">
              <template v-if="plan.baseMonthlyPriceJpy == null">
                <Tag :value="t('billing.plans.priceUndecided')" severity="info" class="mb-1" />
                <p class="text-xs text-surface-500">
                  {{ t('billing.plans.betaFree') }}
                </p>
              </template>
              <template v-else>
                <span class="text-2xl font-bold">¥{{ plan.baseMonthlyPriceJpy.toLocaleString('ja-JP') }}</span>
                <span class="text-sm text-surface-500">{{ t('billing.plans.perMonth') }}</span>
              </template>
            </div>

            <p class="mb-2 text-xs font-semibold uppercase tracking-wide text-surface-500">
              {{ t('billing.plans.featuresHeading') }}
            </p>
            <ul class="mb-4 space-y-1.5">
              <li
                v-for="f in plan.features"
                :key="f.featureKey"
                class="flex items-start gap-2 text-sm"
              >
                <i class="pi pi-check mt-0.5 shrink-0 text-primary" aria-hidden="true" />
                <span>{{ f.displayNameKey ? t(f.displayNameKey) : f.featureKey }}</span>
              </li>
            </ul>

            <!-- 人数バンド表（チーム/組織文脈のみ・折りたたみで段階開示） -->
            <div v-if="scopeKind !== 'USER' && plan.priceBands && plan.priceBands.length > 0" class="mb-4">
              <button
                type="button"
                class="flex w-full items-center justify-between rounded-md border border-surface-200 px-3 py-2 text-xs font-medium text-surface-600 hover:bg-surface-50 dark:border-surface-700 dark:text-surface-300 dark:hover:bg-surface-800"
                @click="bandExpanded = !bandExpanded"
              >
                <span>{{ t('billing.plans.memberBandToggle') }}</span>
                <i class="pi" :class="bandExpanded ? 'pi-chevron-up' : 'pi-chevron-down'" />
              </button>
              <table v-if="bandExpanded" class="mt-2 w-full text-xs">
                <tbody>
                  <tr
                    v-for="band in plan.priceBands.filter(b => b.scopeKind === scopeKind)"
                    :key="band.bandNo"
                    class="border-b border-surface-100 dark:border-surface-800"
                  >
                    <td class="py-1.5 text-surface-600 dark:text-surface-300">
                      {{ band.maxMembers == null
                        ? `${band.minMembers}${t('billing.plans.memberBandUnlimited')}`
                        : t('billing.plans.memberBandRange', { min: band.minMembers, max: band.maxMembers }) }}
                    </td>
                    <td class="py-1.5 text-right font-medium">
                      {{ band.monthlyPriceJpy == null
                        ? t('billing.plans.memberBandUndecided')
                        : `¥${band.monthlyPriceJpy.toLocaleString('ja-JP')}${t('billing.plans.perMonth')}` }}
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <Button
              class="w-full"
              :label="plan.planKey === currentPlanKey ? t('billing.plans.currentCta') : t('billing.plans.selectCta')"
              :disabled="plan.planKey === currentPlanKey"
              :data-testid="`billing-plan-select-${plan.planKey}`"
              @click="openConfirm(plan)"
            />
          </template>
        </SectionCard>
      </div>
    </div>

    <!-- 変更確認モーダル -->
    <Dialog
      v-model:visible="confirmVisible"
      modal
      :header="t('billing.plans.changeConfirmTitle')"
      class="w-full max-w-md"
      data-testid="billing-plan-confirm-modal"
    >
      <div v-if="confirmTarget" class="space-y-4">
        <div v-if="computeGained(confirmTarget).length > 0">
          <p class="mb-1 text-xs font-semibold text-green-600 dark:text-green-400">
            {{ t('billing.plans.changeConfirmGained') }}
          </p>
          <ul class="space-y-1 text-sm">
            <li v-for="k in computeGained(confirmTarget)" :key="k" class="flex items-center gap-2">
              <i class="pi pi-plus-circle text-green-500" />{{ t(k) }}
            </li>
          </ul>
        </div>
        <div v-if="computeLost(confirmTarget).length > 0">
          <p class="mb-1 text-xs font-semibold text-red-600 dark:text-red-400">
            {{ t('billing.plans.changeConfirmLost') }}
          </p>
          <ul class="space-y-1 text-sm">
            <li v-for="k in computeLost(confirmTarget)" :key="k" class="flex items-center gap-2">
              <i class="pi pi-minus-circle text-red-500" />{{ t(k) }}
            </li>
          </ul>
        </div>
        <p v-if="confirmTarget.baseMonthlyPriceJpy == null" class="text-xs text-surface-500">
          {{ t('billing.plans.freeInstantTitle') }}
        </p>
      </div>
      <template #footer>
        <Button :label="t('billing.plans.changeConfirmCancel')" text severity="secondary" @click="confirmVisible = false" />
        <Button
          :label="t('billing.plans.changeConfirmCta')"
          :loading="confirmSubmitting"
          data-testid="billing-plan-confirm-submit"
          @click="submitPlanSelection"
        />
      </template>
    </Dialog>
  </div>
</template>
