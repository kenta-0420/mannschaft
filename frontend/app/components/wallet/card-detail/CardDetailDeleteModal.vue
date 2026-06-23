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
        <button
          type="button"
          class="card-detail__btn"
          :disabled="deleting"
          @click="emit('cancel')"
        >
          {{ t('wallet.detail.cancel') }}
        </button>
        <button
          type="button"
          class="card-detail__btn card-detail__btn--danger"
          :disabled="deleting"
          @click="emit('confirm')"
        >
          {{ deleting ? '…' : t('wallet.detail.delete_confirm_ok') }}
        </button>
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
  background: #fff;
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
.card-detail__btn {
  padding: 0.75rem 1rem;
  border-radius: 0.5rem;
  border: 1px solid var(--p-surface-300, #d1d5db);
  background: var(--p-surface-0, #fff);
  color: var(--p-text-color, #111827);
  font-weight: 600;
  cursor: pointer;
}
.card-detail__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.card-detail__btn--danger {
  background: #fff;
  color: #dc2626;
  border-color: #dc2626;
}
:global(.dark) .card-detail__modal {
  background: #1e1e1e;
  color: #f4f4f5;
}
:global(.dark) .card-detail__btn--danger {
  background: #27272a;
}
</style>
