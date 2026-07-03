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
    <Button
      v-if="isSelfIssued"
      :label="t('wallet.share.open_button')"
      class="w-full"
      @click="emit('open-share')"
    />
    <Button
      :label="t('wallet.detail.record_used')"
      :severity="isSelfIssued ? 'secondary' : undefined"
      :disabled="recordingUsed"
      class="w-full"
      @click="emit('record-used')"
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
