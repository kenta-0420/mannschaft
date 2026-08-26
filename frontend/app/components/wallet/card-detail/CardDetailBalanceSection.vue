<script setup lang="ts">
/**
 * 自店発行カード（SELF_ISSUED_BALANCE / SELF_ISSUED_STAMP）の残高/スタンプ表示セクション。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キーは元と完全同一。
 * BALANCE 凍結バナー（資金決済法対応・2026-05-17 マスター御裁可）の表示判定もそのまま移植。</p>
 *
 * <p>親側で `isBalanceType` / `isStampType` の判定と
 * `balanceFormatted` / `stampCountValue` の整形を行い、その結果を props で受け取る。</p>
 */

interface Props {
  /** SELF_ISSUED_BALANCE のとき true。 */
  isBalanceType: boolean
  /** SELF_ISSUED_STAMP のとき true。 */
  isStampType: boolean
  /** 親で整形した残高表示文字列（凍結時は「---」になる前提）。 */
  balanceFormatted: string
  /** 親で計算したスタンプ累計数（無効時は null）。 */
  stampCountValue: number | null
  /** BALANCE 機能の有効フラグ。false の場合は凍結バナーを出す。 */
  balanceEnabled: boolean
}

defineProps<Props>()

const { t } = useI18n()
</script>

<template>
  <section v-if="isBalanceType && balanceFormatted" class="card-detail__balance">
    <div class="card-detail__balance-label">{{ t('wallet.balance_display.label') }}</div>
    <div class="card-detail__balance-value">{{ balanceFormatted }}</div>
    <p class="card-detail__balance-hint">{{ t('wallet.balance_display.history_hint') }}</p>
    <!-- F18 BALANCE 凍結バナー（2026-05-17 マスター御裁可）。資金決済法対応のため一時停止中。 -->
    <div
      v-if="!balanceEnabled"
      class="card-detail__balance-frozen bg-amber-50 dark:bg-amber-900/30 text-amber-900 dark:text-amber-200"
      role="status"
    >
      <p class="card-detail__balance-frozen-title">
        {{ t('wallet.balance.disabled.banner') }}
      </p>
      <p class="card-detail__balance-frozen-body">
        {{ t('wallet.balance.disabled.reason') }}
      </p>
    </div>
  </section>
  <section v-else-if="isStampType && stampCountValue !== null" class="card-detail__balance">
    <div class="card-detail__balance-label">{{ t('wallet.stamp_display.label') }}</div>
    <div class="card-detail__balance-value">{{ t('wallet.stamp_display.count', { count: stampCountValue }) }}</div>
  </section>
</template>

<style scoped>
.card-detail__balance {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.25rem;
  padding: 1rem;
  background: var(--p-primary-50, #eff6ff);
  border: 1px solid var(--p-primary-200, #bfdbfe);
  border-radius: 0.75rem;
}
.card-detail__balance-label {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--p-text-muted-color, #6b7280);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.card-detail__balance-value {
  font-size: 1.75rem;
  font-weight: 700;
  color: var(--p-primary-color, #3b82f6);
  font-variant-numeric: tabular-nums;
}
.card-detail__balance-hint {
  font-size: 0.75rem;
  color: var(--p-text-muted-color, #6b7280);
  margin: 0.25rem 0 0;
  text-align: center;
}
/* F18 BALANCE 凍結バナー（2026-05-17 マスター御裁可・資金決済法対応） */
/* 背景・文字色は Tailwind dark: クラス（bg-amber-50 dark:bg-amber-900/30 text-amber-900 dark:text-amber-200）で追従 */
.card-detail__balance-frozen {
  margin-top: 0.75rem;
  padding: 0.625rem 0.75rem;
  border: 1px solid #fcd34d; /* amber-300 */
  border-radius: 0.5rem;
  font-size: 0.8125rem;
  text-align: center;
}
.card-detail__balance-frozen-title {
  margin: 0 0 0.25rem;
  font-weight: 600;
}
.card-detail__balance-frozen-body {
  margin: 0;
  font-size: 0.75rem;
  line-height: 1.4;
}
</style>
