<script setup lang="ts">
/**
 * F20.1 U-2: ペイウォールモーダル（402 ENTITLEMENT_003 の共通ハンドラから発火）。
 *
 * `useApi()` の onResponseError が ENTITLEMENT_003 を検知すると `usePaywallStore` を
 * open() し、本コンポーネントが表示される（app.vue にグローバルマウント）。
 *
 * 【段階的縮退の実装であることの注記】
 * 設計書 04 §2 は featureKey/addonAvailable/addonPriceJpy/plansContaining を使った
 * 機能別の購入導線（アドオン個別追加ボタン等）を想定するが、実装済み BE の 402 応答には
 * これらの details が含まれない（usePaywallStore.ts のコメント参照）。
 * そのため本モーダルは汎用メッセージ＋「プランを見る」導線のみを提示する。
 * FE はここで機能を解放しない（BE ゲートが正）。
 */
const store = usePaywallStore()
const { t } = useI18n()

function close() {
  store.close()
}

function goToPlans() {
  store.close()
  navigateTo('/billing/plans')
}
</script>

<template>
  <Dialog
    v-model:visible="store.visible"
    modal
    :header="t('billing.paywall.title')"
    class="w-full max-w-md"
    data-testid="paywall-modal"
    @update:visible="(v: boolean) => { if (!v) close() }"
  >
    <div class="space-y-4">
      <p class="text-sm leading-relaxed text-surface-600 dark:text-surface-300">
        {{ store.message || t('billing.paywall.description') }}
      </p>
    </div>
    <template #footer>
      <Button
        :label="t('billing.paywall.close')"
        text
        severity="secondary"
        data-testid="paywall-modal-close"
        @click="close"
      />
      <Button
        :label="t('billing.paywall.planCta')"
        icon="pi pi-arrow-right"
        data-testid="paywall-modal-plans-cta"
        @click="goToPlans"
      />
    </template>
  </Dialog>
</template>
