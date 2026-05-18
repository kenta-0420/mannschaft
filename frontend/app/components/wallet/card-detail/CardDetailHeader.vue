<script setup lang="ts">
/**
 * カード詳細ページのヘッダー（戻るボタン + タイトル）。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キーは元と完全同一。</p>
 */

interface Props {
  /** 表示するカード名（読込中は元実装と同じく「…」表示にしておく）。 */
  title?: string | null
}

const props = defineProps<Props>()
const emit = defineEmits<(e: 'back') => void>()

const { t } = useI18n()

const displayTitle = computed(() => props.title ?? '…')
</script>

<template>
  <header class="card-detail__header">
    <button
      type="button"
      class="card-detail__back"
      :aria-label="t('wallet.detail.back_to_list')"
      @click="emit('back')"
    >
      ←
    </button>
    <h1 class="card-detail__title">
      {{ displayTitle }}
    </h1>
  </header>
</template>

<style scoped>
.card-detail__header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.card-detail__back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 50%;
  background: var(--p-surface-100, #f3f4f6);
  border: none;
  cursor: pointer;
  font-size: 1.25rem;
}
.card-detail__title {
  font-size: 1.25rem;
  font-weight: 700;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
