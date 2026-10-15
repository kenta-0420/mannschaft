<script setup lang="ts">
/**
 * F08.9 P4b: ペイウォールロック UI コンポーネント。
 *
 * 設計書: docs/features/F08.9_membership_billing_paywall/
 *
 * 役割:
 *   - GateCheckResponse を受け取り、accessible=false のときにロック UI を表示する。
 *   - accessible=true のときは何も表示しない（slot コンテンツが通常表示される）。
 *   - titleHidden=true のときは「このコンテンツは閲覧できません」のシンプルなメッセージのみ表示
 *     （名称・金額・購入導線は一切露出しない — 存在秘匿・404相当）。
 *   - titleHidden=false のときは未払い項目の一覧と「お支払いして閲覧する」ボタンを表示。
 *   - loading=true のときはスケルトンローダーを表示する。
 *
 * セキュリティ:
 *   - titleHidden=true 時は requiredItems を表示しない（BE の存在秘匿方針と整合）。
 *   - 購入ボタンは /payments/subscribe/{paymentItemId} への navigateTo を使用。
 *
 * 親への契約:
 *   - props.loading: ゲートチェック中フラグ（true のときはスケルトン表示）。
 *   - props.gateResult: GateCheckResponse（null の場合はゲートなし=閲覧可として扱う）。
 */
import type { GateCheckResponse } from '~/types/payment'

const props = defineProps<{
  /** ゲートチェック中は true（スケルトン表示） */
  loading?: boolean
  /** ペイウォール判定結果（null = ゲートなし = 閲覧可） */
  gateResult: GateCheckResponse | null
}>()

const { t } = useI18n()

const isLocked = computed<boolean>(() => {
  if (props.loading) return false
  if (!props.gateResult) return false
  return !props.gateResult.accessible
})

const isTitleHidden = computed<boolean>(() => props.gateResult?.titleHidden ?? false)

const unsatisfiedItems = computed(() => {
  if (!props.gateResult) return []
  return props.gateResult.requiredItems.filter((item) => !item.satisfied)
})

function goToSubscribe(paymentItemId: number) {
  navigateTo(`/payments/subscribe/${paymentItemId}`)
}

function formatAmount(amount: number): string {
  return new Intl.NumberFormat('ja-JP', { style: 'currency', currency: 'JPY' }).format(amount)
}
</script>

<template>
  <!-- ローディング中: スケルトン -->
  <div v-if="loading" class="rounded-lg border border-surface-200 bg-surface-50 p-6 dark:border-surface-700 dark:bg-surface-800">
    <div class="animate-pulse space-y-3">
      <div class="h-4 w-3/4 rounded bg-surface-200 dark:bg-surface-700" />
      <div class="h-4 w-1/2 rounded bg-surface-200 dark:bg-surface-700" />
      <div class="h-10 w-40 rounded bg-surface-200 dark:bg-surface-700" />
    </div>
    <p class="mt-3 text-sm text-surface-400">{{ t('payment.paywall.loading') }}</p>
  </div>

  <!-- ペイウォールロック UI -->
  <div
    v-else-if="isLocked"
    class="rounded-lg border border-amber-200 bg-amber-50 p-6 dark:border-amber-800 dark:bg-amber-950"
    role="region"
    :aria-label="t('payment.paywall.locked')"
  >
    <div class="flex items-start gap-4">
      <span class="text-3xl" aria-hidden="true">🔒</span>
      <div class="flex-1">
        <!-- titleHidden=true: 存在秘匿 — シンプルなメッセージのみ表示 -->
        <template v-if="isTitleHidden">
          <p class="font-semibold text-amber-800 dark:text-amber-200">
            {{ t('payment.paywall.hiddenContent') }}
          </p>
        </template>

        <!-- titleHidden=false: 必要な支払い項目一覧 + 購入ボタン -->
        <template v-else>
          <p class="font-semibold text-amber-800 dark:text-amber-200">
            {{ t('payment.paywall.locked') }}
          </p>

          <ul v-if="unsatisfiedItems.length > 0" class="mt-3 space-y-2">
            <li
              v-for="item in unsatisfiedItems"
              :key="item.paymentItemId"
              class="flex items-center justify-between rounded-md bg-white px-3 py-2 shadow-sm dark:bg-surface-800"
            >
              <span class="text-sm text-surface-700 dark:text-surface-300">
                {{ t('payment.paywall.requiredItem', { name: item.name }) }}
              </span>
              <span class="ml-4 shrink-0 text-sm font-semibold text-surface-900 dark:text-surface-100">
                {{ formatAmount(item.faceAmount) }}
              </span>
            </li>
          </ul>

          <div class="mt-4 flex flex-wrap gap-2">
            <Button
              v-for="item in unsatisfiedItems"
              :key="`btn-${item.paymentItemId}`"
              :label="t('payment.paywall.unlock')"
              icon="pi pi-credit-card"
              class="p-button-warning"
              @click="goToSubscribe(item.paymentItemId)"
            />
          </div>
        </template>
      </div>
    </div>
  </div>

  <!-- 閲覧可（ゲートなし or accessible=true）: slot に委譲 -->
  <slot v-else />
</template>
