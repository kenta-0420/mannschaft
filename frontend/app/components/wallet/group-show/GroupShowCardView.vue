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
 *
 * <p>カード下部の補助テキストはニックネーム表示のみ（last4 のマスク表示は撤去済み）。
 * バーコード値は既に {@link BarcodePreview} 側で全桁プレーンテキスト表示しているため、
 * last4（下4桁）の再掲は情報として冗長であるという理由による。</p>
 *
 * <p>カード左右には total &gt; 1 のときのみ矢印ボタン（オーバーレイ）を表示する。
 * 切替ロジック（recordUsed 呼び出し含む）は親 {@code show.vue} が握るため、
 * 本コンポーネントは {@code prev} / {@code next} イベントを発行するだけに留める。</p>
 */
defineProps<{
  loading: boolean
  loadError: string | null
  biometricFailed: boolean
  total: number
  currentIndex: number
  currentCard: PointCardGroupItem | null
}>()

const emit = defineEmits<{
  (e: 'reload' | 'prev' | 'next'): void
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

    <template v-else-if="currentCard">
      <Button
        v-if="total > 1"
        class="presentation__nav presentation__nav--prev"
        rounded
        text
        :disabled="currentIndex === 0"
        :aria-label="t('wallet.presentation.prev_card')"
        @click="emit('prev')"
      >
        <i class="pi pi-chevron-left" aria-hidden="true" />
      </Button>

      <div class="presentation__card">
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
        <p v-if="currentCard.nickname" class="presentation__nickname">
          {{ currentCard.nickname }}
        </p>
      </div>

      <Button
        v-if="total > 1"
        class="presentation__nav presentation__nav--next"
        rounded
        text
        :disabled="currentIndex >= total - 1"
        :aria-label="t('wallet.presentation.next_card')"
        @click="emit('next')"
      >
        <i class="pi pi-chevron-right" aria-hidden="true" />
      </Button>
    </template>
  </main>
</template>

<style scoped>
.presentation__main {
  position: relative;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  overflow: hidden;
}
.presentation__nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 1;
  width: 44px;
  height: 44px;
  min-width: 44px;
  background: rgba(255, 255, 255, 0.15) !important;
  color: #fff !important;
  font-size: 1.25rem;
}
.presentation__nav:disabled {
  opacity: 0.3;
  pointer-events: none;
}
.presentation__nav--prev {
  left: 0.5rem;
}
.presentation__nav--next {
  right: 0.5rem;
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
.presentation__nickname {
  font-size: 1rem;
  opacity: 0.8;
  margin: 0;
  text-align: center;
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
