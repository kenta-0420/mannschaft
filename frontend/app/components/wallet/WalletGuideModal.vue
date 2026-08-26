<script setup lang="ts">
/** ウォレットの使い方を案内するモーダル。v-model:visible で開閉。本体は WalletGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('wallet.wallet_guide.title')"
    class="w-full max-w-2xl"
    data-testid="wallet-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <WalletGuideContent />
    </div>
    <template #footer>
      <Button :label="$t('button.close')" icon="pi pi-times" text @click="visible = false" />
    </template>
  </Dialog>
</template>
