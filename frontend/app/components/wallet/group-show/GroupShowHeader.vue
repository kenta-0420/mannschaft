<script setup lang="ts">
/**
 * F18 個人ポイントカードウォレット — 提示モードのヘッダ。
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * 表示順・属性・i18n キーは元実装と完全同一。</p>
 */
defineProps<{
  total: number
  pageIndicator: string
  providerDisplayName: string | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const { t } = useI18n()
</script>

<template>
  <header class="presentation__header">
    <button
      type="button"
      class="presentation__close"
      :aria-label="t('wallet.presentation.close')"
      @click="emit('close')"
    >
      ×
    </button>
    <div v-if="total > 0" class="presentation__indicator">
      {{ pageIndicator }}
    </div>
    <div v-if="providerDisplayName" class="presentation__provider">
      {{ providerDisplayName }}
    </div>
    <div v-else class="presentation__provider" aria-hidden="true">&nbsp;</div>
  </header>
</template>

<style scoped>
.presentation__header {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.75rem 1rem;
  background: rgba(0, 0, 0, 0.35);
}
.presentation__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  border: none;
  cursor: pointer;
  font-size: 1.5rem;
  line-height: 1;
}
.presentation__indicator {
  flex: 1;
  text-align: center;
  font-weight: 600;
  font-size: 0.9375rem;
  letter-spacing: 0.05em;
}
.presentation__provider {
  font-size: 0.875rem;
  font-weight: 600;
  max-width: 40%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}
</style>
