<script setup lang="ts">
/**
 * F18 個人ポイントカードウォレット — 提示モードのスクリーンキャプチャ警告モーダル。
 *
 * <p>初回オープン時のみ表示する。既読フラグは localStorage で親側が管理する。</p>
 *
 * <p>分割元: {@code frontend/app/pages/wallet/groups/[id]/show.vue}（リファクタリング第11弾）。
 * 属性・i18n キー・aria 設定は元実装と完全同一。</p>
 */
const emit = defineEmits<{
  (e: 'dismiss'): void
}>()

const { t } = useI18n()
</script>

<template>
  <div
    class="presentation__modal-backdrop"
    role="dialog"
    aria-modal="true"
    :aria-label="t('wallet.presentation.warning_title')"
  >
    <div class="presentation__modal">
      <h2 class="presentation__modal-title">
        {{ t('wallet.presentation.warning_title') }}
      </h2>
      <p class="presentation__modal-body">
        {{ t('wallet.presentation.warning_body') }}
      </p>
      <Button
        :label="t('wallet.presentation.warning_ack')"
        class="w-full"
        @click="emit('dismiss')"
      />
    </div>
  </div>
</template>

<style scoped>
.presentation__modal-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
  z-index: 10;
}
.presentation__modal {
  background: var(--p-content-background, #fff);
  color: var(--p-text-color, #111);
  border-radius: 0.75rem;
  padding: 1.5rem;
  max-width: 480px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 1rem;
  text-align: center;
}
.presentation__modal-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 700;
}
.presentation__modal-body {
  margin: 0;
  font-size: 0.9375rem;
  line-height: 1.5;
}
</style>
