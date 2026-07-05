<script setup lang="ts">
/**
 * チーム予約パネルの使い方を案内するモーダル。v-model:visible で開閉。
 * 本体は TeamReservationGuideContent に分離し、isAdmin で内容を出し分ける。
 */
defineProps<{ isAdmin: boolean }>()
const visible = defineModel<boolean>('visible', { default: false })
</script>

<template>
  <Dialog
    v-model:visible="visible"
    modal
    :header="$t('reservation.team_guide.title')"
    class="w-full max-w-2xl"
    data-testid="team-reservation-guide-modal"
  >
    <div class="overflow-y-auto py-2">
      <TeamReservationGuideContent :is-admin="isAdmin" />
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
