<script setup lang="ts">
/**
 * F20.1 U-2: ペイウォールモーダル（402 ENTITLEMENT_003 の共通ハンドラから発火）。
 *
 * `useApi()` の onResponseError が ENTITLEMENT_003 を検知すると `usePaywallStore` を
 * open() し、本コンポーネントが表示される（app.vue にグローバルマウント）。
 *
 * BE（#2442）が返す details（featureKey/addonAvailable/addonPriceJpy/plansContaining/
 * scopeKind/scopeId）を読み、機能別のワンクリック購入導線を出す（設計書 04 §2）。
 *
 * 分岐（軍議確定）:
 * - addonAvailable=true かつベータ（addonPriceJpy が null または 0 円以下）
 *   → 主 CTA は betaCta「無料で有効にする」1 ボタン（AC-4/10/12）。
 * - addonAvailable=true かつ有償（addonPriceJpy > 0）
 *   → 主 CTA は addonCta。契約 API が checkoutUrl を返せば Stripe Checkout へ遷移する
 *     （plans.vue の流儀を流用。現時点で実料金アドオンは未提供のため通常は通らない経路）。
 * - addonAvailable=false かつ plansContaining 非空（プラン専用機能）
 *   → 主 CTA は planCta のみ。インライン契約はせず /billing/plans へ遷移する（AC-6）。
 * - details 無し（旧 BE・後方互換）またはどちらの購入手段もない場合
 *   → 汎用メッセージ＋planCta の段階的縮退を維持する（AC-23）。
 *
 * FE はここで機能を解放しない（BE ゲートが正。project_paywall_be_body_gate_required）。
 */
const store = usePaywallStore()
const { t } = useI18n()
const billingApi = useBillingApi()
const notification = useNotification()

/** 二重押下防止（AC-15）。 */
const submitting = ref(false)

const details = computed(() => store.details)

/** ベータ判定 = addonPriceJpy が null または 0 円以下（null と 0 円を等価に扱う・AC-10/12）。 */
const isBeta = computed(() => {
  const price = details.value?.addonPriceJpy
  return price == null || price <= 0
})

const canAddon = computed(() => details.value?.addonAvailable === true)
const canPlan = computed(() => (details.value?.plansContaining?.length ?? 0) > 0)

/** 機能名の表示解決（billing.features.<key>.name。未登録なら featureKey をそのまま表示）。 */
const featureName = computed(() => {
  const key = details.value?.featureKey
  if (!key) return null
  return t(`billing.features.${key.replace(/\./g, '_')}.name`, key)
})

const dialogTitle = computed(() =>
  canAddon.value && isBeta.value ? t('billing.paywall.betaTitle') : t('billing.paywall.title'),
)

const bodyMessage = computed(() => {
  if (canAddon.value && isBeta.value) return t('billing.paywall.betaDescription')
  if (details.value && !canAddon.value && !canPlan.value) return t('billing.paywall.notAvailable')
  return store.message || t('billing.paywall.description')
})

const primaryLabel = computed(() => {
  if (submitting.value) return t('billing.paywall.activating')
  if (canAddon.value) return isBeta.value ? t('billing.paywall.betaCta') : t('billing.paywall.addonCta')
  return t('billing.paywall.planCta')
})

function close() {
  store.close()
}

function goToPlans() {
  store.close()
  navigateTo('/billing/plans')
}

/** アドオン契約でワンクリック有効化する（AC-5）。 */
async function activateAddon() {
  if (submitting.value) return
  const d = details.value
  if (!d?.featureKey || !d?.scopeKind) return

  submitting.value = true
  try {
    const scopeId = d.scopeKind === 'USER' ? '' : String(d.scopeId ?? '')
    const res = await billingApi.createContract(d.scopeKind, scopeId, {
      contractKind: 'ADDON',
      featureKey: d.featureKey,
    })
    if (res.data.checkoutUrl) {
      // 有償アドオン（現時点では通常未使用経路）: Stripe Checkout へ遷移する（plans.vue と同じ流儀）。
      window.location.href = res.data.checkoutUrl
      return
    }
    notification.success(t('billing.paywall.activateSuccess'))
    store.close()
    // PaywallModal はグローバル常駐で呼び出し元ページの権利判定状態を直接知らないため、
    // ページ再読み込みで権利再取得を確実にする（AC-5）。トーストを視認できるよう一拍おく。
    setTimeout(() => window.location.reload(), 1200)
  }
  catch (err) {
    const code = (err as { data?: { error?: { code?: string } } })?.data?.error?.code
    if (code === 'ENTITLEMENT_006') {
      // 既に有効（他タブ等で先に契約済み）: 失敗ではなく成功と同義に扱う（AC-13・plans.vue:130-132 流儀）。
      notification.warn(t('dialog.error'), t('billing.plans.alreadyActiveError'))
      store.close()
      setTimeout(() => window.location.reload(), 1200)
    }
    else {
      // 422/403/その他失敗: モーダルは開いたまま再試行できるようにする（AC-14）。
      notification.error(t('billing.paywall.activateError'))
    }
  }
  finally {
    submitting.value = false
  }
}

function onPrimaryClick() {
  if (canAddon.value) {
    activateAddon()
    return
  }
  goToPlans()
}
</script>

<template>
  <Dialog
    v-model:visible="store.visible"
    modal
    :header="dialogTitle"
    class="w-full max-w-md"
    data-testid="paywall-modal"
    @update:visible="(v: boolean) => { if (!v) close() }"
  >
    <div class="space-y-4">
      <p v-if="featureName" class="text-sm font-semibold" data-testid="paywall-modal-feature-name">
        {{ featureName }}
      </p>
      <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
        {{ bodyMessage }}
      </p>
    </div>
    <template #footer>
      <Button
        :label="t('billing.paywall.close')"
        text
        severity="secondary"
        :disabled="submitting"
        data-testid="paywall-modal-close"
        @click="close"
      />
      <Button
        :label="primaryLabel"
        :icon="canAddon ? undefined : 'pi pi-arrow-right'"
        :loading="submitting"
        :disabled="submitting"
        data-testid="paywall-modal-primary-cta"
        @click="onPrimaryClick"
      />
    </template>
  </Dialog>
</template>
