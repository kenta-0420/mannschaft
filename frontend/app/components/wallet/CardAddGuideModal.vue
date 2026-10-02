<script setup lang="ts">
/**
 * カード追加ページ（/wallet/cards/new）の使い方を案内するモーダル。
 *
 * v-model:visible で開閉を制御する。
 * コンテンツは CardAddGuideContent コンポーネントを再利用。
 * WalletGuideModal・ProjectGuideModal と同一構造に揃えている。
 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('wallet.add.help.title')"
    class="w-full max-w-2xl"
    data-testid="card-add-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <CardAddGuideContent />
    </div>
    <template #footer>
      <Button
        :label="$t('button.close')"
        icon="pi pi-times"
        text
        @click="visible = false"
      />
    </template>
  </Dialog>
</template>
