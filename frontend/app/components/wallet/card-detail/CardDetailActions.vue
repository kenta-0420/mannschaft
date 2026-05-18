<script setup lang="ts">
/**
 * カード詳細ページのアクションボタン群（店舗で提示 / 使用済み / お気に入り / 編集）。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キー・ボタン並び順は元と完全同一。</p>
 */

interface Props {
  /** 自店発行カードのとき true。「店舗で提示」ボタンの表示判定に使う。 */
  isSelfIssued: boolean
  /** お気に入り状態。ボタンラベルの切替に使う。 */
  favorite: boolean
  /** 「使用済み」記録中フラグ。多重押下抑止。 */
  recordingUsed: boolean
}

defineProps<Props>()
const emit = defineEmits<(e: 'open-share' | 'record-used' | 'toggle-favorite' | 'enter-edit') => void>()

const { t } = useI18n()
</script>

<template>
  <section class="card-detail__actions">
    <button
      v-if="isSelfIssued"
      type="button"
      class="card-detail__btn card-detail__btn--primary"
      @click="emit('open-share')"
    >
      {{ t('wallet.share.open_button') }}
    </button>
    <button
      type="button"
      class="card-detail__btn"
      :class="{ 'card-detail__btn--primary': !isSelfIssued }"
      :disabled="recordingUsed"
      @click="emit('record-used')"
    >
      {{ t('wallet.detail.record_used') }}
    </button>
    <button
      type="button"
      class="card-detail__btn"
      @click="emit('toggle-favorite')"
    >
      {{ favorite ? t('wallet.detail.favorite_off') : t('wallet.detail.favorite_on') }}
    </button>
    <button
      type="button"
      class="card-detail__btn"
      @click="emit('enter-edit')"
    >
      {{ t('wallet.detail.edit') }}
    </button>
  </section>
</template>

<style scoped>
.card-detail__actions {
  display: flex;
  flex-direction: column;
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
.card-detail__btn--primary {
  background: var(--p-primary-color, #3b82f6);
  color: #fff;
  border-color: transparent;
}
</style>
