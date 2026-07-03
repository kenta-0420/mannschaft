<script setup lang="ts">
import BarcodePreview from '~/components/wallet/BarcodePreview.vue'
import type { PointCardGroupItem } from '~/types/pointCard'

/**
 * F18 個人ポイントカードウォレット — 提示モード中央のカード表示部。
 *
 * <p>loading / error / empty / カード本体の 4 状態を表示する。
 * 元実装の {@code <main class="presentation__main">} ブロックをそのまま切り出した。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * 属性・i18n キー・条件分岐は元実装と完全同一。</p>
 */
defineProps<{
  loading: boolean
  loadError: string | null
  biometricFailed: boolean
  total: number
  currentCard: PointCardGroupItem | null
}>()

const emit = defineEmits<{
  (e: 'reload'): void
}>()

const { t } = useI18n()
</script>

<template>
  <main class="presentation__main">
    <p v-if="loading" class="presentation__loading">…</p>

    <div v-else-if="loadError" class="presentation__error" role="alert">
      <p>{{ loadError }}</p>
      <Button
        v-if="!biometricFailed"
        :label="`↻ ${t('wallet.presentation.reload')}`"
        severity="secondary"
        @click="emit('reload')"
      />
    </div>

    <p v-else-if="total === 0" class="presentation__empty">
      {{ t('wallet.presentation.no_cards') }}
    </p>

    <div v-else-if="currentCard" class="presentation__card">
      <img
        v-if="currentCard.providerLogoUrl"
        :src="currentCard.providerLogoUrl"
        :alt="currentCard.providerDisplayName ?? ''"
        class="presentation__logo"
      >
      <BarcodePreview
        :value="currentCard.barcodeValue"
        :format="currentCard.barcodeFormat"
        size="large"
      />
      <h2 class="presentation__name">{{ currentCard.displayName }}</h2>
      <p v-if="currentCard.last4" class="presentation__last4">
        ●●●● {{ currentCard.last4 }}
      </p>
    </div>
  </main>
</template>

<style scoped>
.presentation__main {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  overflow: hidden;
}
.presentation__card {
  background: var(--p-content-background, #fff);
  color: var(--p-text-color, #111);
  border-radius: 1rem;
  padding: 1.5rem 1.25rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.875rem;
  max-width: 96%;
  width: 100%;
  max-width: 520px;
}
.presentation__logo {
  height: 32px;
  max-width: 60%;
  object-fit: contain;
}
.presentation__name {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  text-align: center;
}
.presentation__last4 {
  font-size: 2rem;
  font-weight: 700;
  letter-spacing: 0.1em;
  margin: 0;
}
.presentation__loading,
.presentation__empty,
.presentation__error {
  text-align: center;
  font-size: 1rem;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1rem;
}
</style>
