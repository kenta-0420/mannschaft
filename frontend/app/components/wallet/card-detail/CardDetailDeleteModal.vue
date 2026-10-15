<script setup lang="ts">
/**
 * カード削除の確認モーダル。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キーは元と完全同一。
 *
 * <p>背景クリック (`@click.self`) でキャンセル扱いになる UX も維持。</p>
 */

interface Props {
  /** モーダルを表示するか。 */
  show: boolean
  /** 削除処理中フラグ。ボタン disabled に使う。 */
  deleting: boolean
}

defineProps<Props>()
const emit = defineEmits<(e: 'cancel' | 'confirm') => void>()

const { t } = useI18n()
</script>

<template>
  <div
    v-if="show"
    class="card-detail__modal-backdrop"
    role="dialog"
    aria-modal="true"
    :aria-label="t('wallet.detail.delete_confirm_title')"
    @click.self="emit('cancel')"
  >
    <div class="card-detail__modal">
      <h2 class="card-detail__modal-title">{{ t('wallet.detail.delete_confirm_title') }}</h2>
      <p class="card-detail__modal-body">{{ t('wallet.detail.delete_confirm') }}</p>
      <div class="card-detail__modal-actions">
        <Button
          :label="t('wallet.detail.cancel')"
          severity="secondary"
          :disabled="deleting"
          @click="emit('cancel')"
        />
        <Button
          :label="deleting ? '…' : t('wallet.detail.delete_confirm_ok')"
          severity="danger"
          :disabled="deleting"
          :loading="deleting"
          @click="emit('confirm')"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.card-detail__modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 30;
  padding: 1rem;
}
.card-detail__modal {
  background: var(--p-content-background, #fff);
  border-radius: 0.75rem;
  padding: 1.25rem;
  max-width: 420px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.card-detail__modal-title {
  font-size: 1.125rem;
  font-weight: 700;
  margin: 0;
}
.card-detail__modal-body {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--p-text-color, #111827);
}
.card-detail__modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
