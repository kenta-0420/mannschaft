<script setup lang="ts">
/** マイ予約ページの使い方を案内するモーダル。v-model:visible で開閉。本体は ReservationGuideContent に分離。 */
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('reservation.guide.title')"
    class="w-full max-w-2xl"
    data-testid="reservation-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <ReservationGuideContent />
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
