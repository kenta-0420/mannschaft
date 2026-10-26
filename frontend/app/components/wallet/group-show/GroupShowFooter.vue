<script setup lang="ts">
import type { PointCardGroupItem } from '~/types/pointCard'

/**
 * F18 個人ポイントカードウォレット — 提示モードのフッタ。
 *
 * <p>スワイプヒント・明るさヒント・進捗ドット・再読み込みボタンを表示する。
 * 元実装の {@code <footer v-if="!loading && !loadError && total > 0">} ブロックをそのまま切り出した。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * 表示順・条件・i18n キーは元実装と完全同一。</p>
 */
defineProps<{
  items: PointCardGroupItem[]
  currentIndex: number
  total: number
}>()

const emit = defineEmits<{
  (e: 'reload'): void
}>()

const { t } = useI18n()
</script>

<template>
  <footer class="presentation__footer">
    <p class="presentation__hint">
      {{ t('wallet.presentation.hint_swipe') }}
    </p>
    <p class="presentation__hint presentation__hint--muted">
      {{ t('wallet.presentation.hint_brightness') }}
    </p>
    <div v-if="total > 1" class="presentation__progress">
      <span
        v-for="(_, idx) in items"
        :key="idx"
        class="presentation__dot"
        :class="{ 'presentation__dot--active': idx === currentIndex }"
      />
    </div>
    <Button
      :label="`↻ ${t('wallet.presentation.reload')}`"
      severity="secondary"
      size="small"
      @click="emit('reload')"
    />
  </footer>
</template>

<style scoped>
.presentation__footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem 1.25rem;
  background: rgba(0, 0, 0, 0.35);
}
.presentation__hint {
  margin: 0;
  font-size: 0.875rem;
  opacity: 0.85;
}
.presentation__hint--muted {
  font-size: 0.75rem;
  opacity: 0.6;
}
.presentation__progress {
  display: flex;
  gap: 0.375rem;
}
.presentation__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.35);
}
.presentation__dot--active {
  background: #fff;
  transform: scale(1.3);
}
</style>
