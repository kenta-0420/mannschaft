<script setup lang="ts">
/**
 * カード詳細ページのアクションボタン群（店舗で提示 / お気に入り / 編集）。
 *
 * <p>F18 リファクタリング第10弾で `wallet/cards/[id].vue` から分割した子コンポーネント。
 * 振る舞い・CSS クラス・i18n キー・ボタン並び順は元と完全同一。</p>
 *
 * <p>「使用済みとして記録」ボタンは廃止。カード詳細を表示した時点で自動的に
 * last_used_at が更新されるため手動ボタンは不要（設計書 §7.1 UX 改善）。</p>
 */

interface Props {
  /** 自店発行カードのとき true。「店舗で提示」ボタンの表示判定に使う。 */
  isSelfIssued: boolean
  /** お気に入り状態。ボタンラベルの切替に使う。 */
  favorite: boolean
}

defineProps<Props>()
const emit = defineEmits<(e: 'open-share' | 'toggle-favorite' | 'enter-edit') => void>()

const { t } = useI18n()
</script>

<template>
  <section class="card-detail__actions">
    <Button
      v-if="isSelfIssued"
      :label="t('wallet.share.open_button')"
      class="w-full"
      @click="emit('open-share')"
    />
    <Button
      :label="favorite ? t('wallet.detail.favorite_off') : t('wallet.detail.favorite_on')"
      severity="secondary"
      class="w-full"
      @click="emit('toggle-favorite')"
    />
    <Button
      :label="t('wallet.detail.edit')"
      severity="secondary"
      class="w-full"
      @click="emit('enter-edit')"
    />
  </section>
</template>

<style scoped>
.card-detail__actions {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}
</style>
